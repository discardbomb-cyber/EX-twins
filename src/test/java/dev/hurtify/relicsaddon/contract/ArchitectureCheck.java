package dev.hurtify.relicsaddon.contract;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Dependency rules of the hexagonal layout (docs/architecture/hexagonal-migration.md, section 3), checked
 * on the compiled classes and, for domain and application, on the sources. JDK only.
 *
 * <pre>
 * &lt;classes dir&gt; [&lt;classes dir&gt; ...] --sources &lt;root&gt; [--sources &lt;root&gt; ...] --max-legacy &lt;n&gt;
 *     --min-domain &lt;n&gt; --min-application &lt;n&gt; --min-adapter &lt;n&gt; [--client-strict]
 * </pre>
 */
public final class ArchitectureCheck {
    enum Layer {
        DOMAIN, PORT, APPLICATION, ADAPTER_INJECTED, ADAPTER_OUT, ADAPTER, BOOTSTRAP, ROOT, CLIENT, GAMETEST_CLIENT, GAMETEST, LEGACY,
        JDK, CLIENT_LIBS, LIB
    }

    private static final String BASE = "dev/hurtify/relicsaddon/";
    private static final String ROOT_CLASS = BASE + "RelicsAddon";
    private static final String MOD_RUNTIME = BASE + "bootstrap/ModRuntime";
    private static final String ADDON_CLIENT_CONFIG = BASE + "client/AddonClientConfig";
    private static final String MOD_DATA_COMPONENTS = BASE + "adapter/registry/ModDataComponents";
    private static final List<String> CLIENT_LIBS = List.of("net/minecraft/client/", "com/mojang/blaze3d/", "net/neoforged/neoforge/client/",
            "org/lwjgl/", "com/lowdragmc/", "dev/lambdaurora/");
    private static final List<String> FORBIDDEN_JDK_PACKAGES = List.of("java/io/", "java/nio/", "java/net/", "java/lang/reflect/",
            "java/util/concurrent/", "java/util/logging/", "java/sql/", "java/awt/", "javax/");
    /** Classes rather than packages: java/lang/RuntimeException or java/lang/ThreadLocal... stay allowed, nested classes do not. */
    private static final List<String> FORBIDDEN_JDK_CLASSES = List.of("java/lang/Thread", "java/lang/Runtime", "java/lang/ProcessBuilder");
    private static final List<String> REFLECTION_ESCAPES = List.of("net.minecraft.", "net.neoforged.", "com.mojang.", "com.lowdragmc.",
            "top.theillusivec4.", "dev.lambdaurora.", "io.netty.", "dev.hurtify.relicsaddon.");

    private record Violation(String from, String to, String rule) implements Comparable<Violation> {
        @Override public int compareTo(Violation other) {
            int byFrom = from.compareTo(other.from);
            if (byFrom != 0) return byFrom;
            int byTo = to.compareTo(other.to);
            return byTo != 0 ? byTo : rule.compareTo(other.rule);
        }

        @Override public String toString() {
            return from + " -> " + to + " [" + rule + "]";
        }
    }

    public static void main(String[] args) throws IOException {
        List<Path> classDirs = new ArrayList<>(), sourceRoots = new ArrayList<>();
        int maxLegacy = -1, minDomain = -1, minApplication = -1, minAdapter = -1;
        boolean clientStrict = false;
        for (int index = 0; index < args.length; index++) {
            switch (args[index]) {
                case "--sources" -> sourceRoots.add(Path.of(args[++index]));
                case "--max-legacy" -> maxLegacy = Integer.parseInt(args[++index]);
                case "--min-domain" -> minDomain = Integer.parseInt(args[++index]);
                case "--min-application" -> minApplication = Integer.parseInt(args[++index]);
                case "--min-adapter" -> minAdapter = Integer.parseInt(args[++index]);
                case "--client-strict" -> clientStrict = true;
                default -> {
                    if (args[index].startsWith("--")) throw new IllegalArgumentException("Unknown option " + args[index]);
                    classDirs.add(Path.of(args[index]));
                }
            }
        }
        if (classDirs.isEmpty() || sourceRoots.isEmpty() || maxLegacy < 0 || minDomain < 0 || minApplication < 0 || minAdapter < 0) {
            throw new IllegalArgumentException("Usage: <classes dir>... --sources <root>... --max-legacy <n> --min-domain <n> "
                    + "--min-application <n> --min-adapter <n> [--client-strict]");
        }

        List<ClassFile> classes = new ArrayList<>();
        for (Path dir : classDirs) {
            if (!Files.isDirectory(dir)) throw new AssertionError("Classes directory missing: " + dir);
            try (Stream<Path> files = Files.walk(dir)) {
                for (Path file : files.filter(path -> path.toString().endsWith(".class")).sorted().toList()) {
                    ClassFile parsed = ClassFile.read(file);
                    if (!parsed.name.equals("module-info")) classes.add(parsed);
                }
            }
        }

        Set<Violation> violations = new TreeSet<>();
        Map<Layer, int[]> counts = new EnumMap<>(Layer.class);
        ClassFile canary = null;
        for (ClassFile type : classes) {
            Layer from = classify(type.name);
            int[] count = counts.computeIfAbsent(from, layer -> new int[2]);
            count[1]++;
            if (type.topLevel()) count[0]++;
            if (type.name.equals(ROOT_CLASS)) canary = type;
            for (String to : type.referencedTypes()) check(type.name, from, to, clientStrict, violations);
            if (from == Layer.DOMAIN || isApplication(from)) {
                for (ClassFile.Field field : type.fields) {
                    if (field.isStatic() && !field.isFinal()) violations.add(new Violation(type.name, "field " + field.name(), "S1"));
                }
                for (String constant : type.strings) {
                    for (String prefix : REFLECTION_ESCAPES) {
                        if (constant.startsWith(prefix)) violations.add(new Violation(type.name, "\"" + constant + "\"", "S2"));
                    }
                }
            }
        }

        List<String> sourceViolations = new ArrayList<>();
        for (Path root : sourceRoots) sourceViolations.addAll(SourcePass.check(root));

        int domain = top(counts, Layer.DOMAIN), application = top(counts, Layer.APPLICATION) + top(counts, Layer.PORT);
        int adapter = top(counts, Layer.ADAPTER) + top(counts, Layer.ADAPTER_INJECTED) + top(counts, Layer.ADAPTER_OUT);
        int legacy = top(counts, Layer.LEGACY);
        List<String> failures = new ArrayList<>();
        if (legacy > maxLegacy) failures.add("L1: " + legacy + " legacy top-level classes, at most " + maxLegacy + " allowed");
        if (domain < minDomain) failures.add("M1: " + domain + " domain classes, at least " + minDomain + " expected");
        if (application < minApplication) failures.add("M1: " + application + " application classes, at least " + minApplication + " expected");
        if (adapter < minAdapter) failures.add("M1: " + adapter + " adapter classes, at least " + minAdapter + " expected");

        int canaryHits = 0;
        if (canary == null) {
            failures.add("K1: canary class " + ROOT_CLASS + " not found");
        } else {
            for (String to : canary.referencedTypes()) if (!domainMayReference(to)) canaryHits++;
            if (canaryHits == 0) failures.add("K1: rule D1 found nothing in " + ROOT_CLASS + "; the class-file parser is broken");
        }

        violations.forEach(System.out::println);
        sourceViolations.forEach(System.out::println);
        StringBuilder layers = new StringBuilder();
        for (Layer layer : Layer.values()) {
            if (layer.ordinal() >= Layer.JDK.ordinal()) break;
            int[] count = counts.getOrDefault(layer, new int[2]);
            if (!layers.isEmpty()) layers.append(", ");
            layers.append(layer).append(' ').append(count[0]).append(" (").append(count[1]).append(" class files)");
        }
        System.out.println("Architecture layers (top-level classes): " + layers);
        System.out.println("Architecture counts: domain " + domain + " (min " + minDomain + "), application " + application
                + " (min " + minApplication + "), adapter " + adapter + " (min " + minAdapter + "), legacy " + legacy + " (max " + maxLegacy
                + "); canary D1 hits in RelicsAddon: " + canaryHits + (clientStrict ? "; client-strict" : ""));
        failures.forEach(System.out::println);
        if (!violations.isEmpty() || !sourceViolations.isEmpty() || !failures.isEmpty()) {
            throw new AssertionError("Architecture check failed: " + violations.size() + " class violations, " + sourceViolations.size()
                    + " source violations, " + failures.size() + " other failures");
        }
        System.out.println("Architecture: " + classes.size() + " class files follow the dependency rules");
    }

    private static int top(Map<Layer, int[]> counts, Layer layer) {
        int[] count = counts.get(layer);
        return count == null ? 0 : count[0];
    }

    static Layer classify(String name) {
        if (name.startsWith(BASE + "domain/")) return Layer.DOMAIN;
        if (name.startsWith(BASE + "application/port/")) return Layer.PORT;
        if (name.startsWith(BASE + "application/")) return Layer.APPLICATION;
        if (name.startsWith(BASE + "adapter/in/event/") || name.startsWith(BASE + "adapter/in/command/")) return Layer.ADAPTER_INJECTED;
        if (name.startsWith(BASE + "adapter/out/")) return Layer.ADAPTER_OUT;
        if (name.startsWith(BASE + "adapter/")) return Layer.ADAPTER;
        if (name.startsWith(BASE + "bootstrap/")) return Layer.BOOTSTRAP;
        if (isClass(name, ROOT_CLASS)) return Layer.ROOT;
        if (name.startsWith(BASE + "client/")) return Layer.CLIENT;
        if (name.startsWith(BASE + "gametest/client/")) return Layer.GAMETEST_CLIENT;
        if (name.startsWith(BASE + "gametest/")) return Layer.GAMETEST;
        if (name.startsWith(BASE)) return Layer.LEGACY;
        if (name.startsWith("java/")) return Layer.JDK;
        for (String prefix : CLIENT_LIBS) if (name.startsWith(prefix)) return Layer.CLIENT_LIBS;
        return Layer.LIB;
    }

    /** {@code name} is {@code type} itself or one of its nested classes. */
    private static boolean isClass(String name, String type) {
        return name.equals(type) || name.startsWith(type + "$");
    }

    private static boolean isApplication(Layer layer) {
        return layer == Layer.PORT || layer == Layer.APPLICATION;
    }

    private static boolean isAdapter(Layer layer) {
        return layer == Layer.ADAPTER || layer == Layer.ADAPTER_INJECTED || layer == Layer.ADAPTER_OUT;
    }

    private static boolean isGametest(Layer layer) {
        return layer == Layer.GAMETEST || layer == Layer.GAMETEST_CLIENT;
    }

    private static boolean forbiddenJdk(String name) {
        for (String prefix : FORBIDDEN_JDK_PACKAGES) if (name.startsWith(prefix)) return true;
        for (String type : FORBIDDEN_JDK_CLASSES) if (isClass(name, type)) return true;
        return false;
    }

    /** Rule D1: domain code may name the JDK (minus FORBIDDEN_JDK) and the domain. */
    private static boolean domainMayReference(String to) {
        Layer layer = classify(to);
        return layer == Layer.DOMAIN || layer == Layer.JDK && !forbiddenJdk(to);
    }

    private static void check(String fromName, Layer from, String to, boolean clientStrict, Set<Violation> violations) {
        Layer target = classify(to);
        if (from == Layer.DOMAIN && !domainMayReference(to)) violations.add(new Violation(fromName, to, "D1"));
        if (isApplication(from) && !(domainMayReference(to) || isApplication(target))) violations.add(new Violation(fromName, to, "A1"));
        if (from != Layer.CLIENT && from != Layer.GAMETEST_CLIENT && (target == Layer.CLIENT_LIBS || target == Layer.CLIENT)
                && !(from == Layer.ROOT && to.equals(ADDON_CLIENT_CONFIG))) {
            violations.add(new Violation(fromName, to, "X1"));
        }
        if (from == Layer.CLIENT) {
            if (to.startsWith("com/lowdragmc/") && !fromName.startsWith(BASE + "client/fx/")) violations.add(new Violation(fromName, to, "X2"));
            if (to.startsWith("dev/lambdaurora/") && !fromName.startsWith(BASE + "client/light/")) violations.add(new Violation(fromName, to, "X2"));
        }
        if ((isAdapter(from) || from == Layer.CLIENT || from == Layer.ROOT) && target == Layer.APPLICATION) {
            violations.add(new Violation(fromName, to, "P1"));
        }
        if (target == Layer.BOOTSTRAP && !isClass(to, MOD_RUNTIME) && from != Layer.ROOT && from != Layer.BOOTSTRAP && !isGametest(from)) {
            violations.add(new Violation(fromName, to, "P2"));
        }
        if (isClass(to, MOD_RUNTIME) && !(from == Layer.ROOT || from == Layer.BOOTSTRAP || from == Layer.ADAPTER || from == Layer.CLIENT
                || isGametest(from) || from == Layer.LEGACY)) {
            violations.add(new Violation(fromName, to, "P3"));
        }
        if (isGametest(target) && !isGametest(from)) violations.add(new Violation(fromName, to, "G1"));
        if (clientStrict && from == Layer.CLIENT && (target == Layer.LEGACY || to.startsWith(BASE + "adapter/in/event/")
                || to.startsWith(BASE + "adapter/in/command/") || isClass(to, MOD_DATA_COMPONENTS))) {
            violations.add(new Violation(fromName, to, "C1"));
        }
    }

    /** The source pass over {@code domain/} and {@code application/}: imports, qualified names and impure calls. */
    static final class SourcePass {
        private static final Pattern IMPORT = Pattern.compile("^\\s*import\\s+(static\\s+)?([\\w$.\\s*]+?)\\s*;");
        private static final Pattern FOREIGN_ROOT = Pattern.compile("(?<![\\w$.])(net\\.minecraft\\.|net\\.neoforged\\.|com\\.mojang\\."
                + "|com\\.lowdragmc\\.|com\\.google\\.|top\\.theillusivec4\\.|dev\\.lambdaurora\\.|io\\.netty\\.|org\\.lwjgl\\.|org\\.joml\\."
                + "|org\\.slf4j\\.|javax\\.)");
        /** The mod's own names outside the allowed packages; the allowed package itself ({@code ...domain;}) is fine too. */
        private static final Pattern OWN_ROOT_DOMAIN = Pattern.compile("(?<![\\w$.])dev\\.hurtify\\.relicsaddon\\.(?!domain(?![\\w$]))");
        private static final Pattern OWN_ROOT_APPLICATION = Pattern.compile(
                "(?<![\\w$.])dev\\.hurtify\\.relicsaddon\\.(?!(?:domain|application)(?![\\w$]))");
        private static final Pattern IMPURE = Pattern.compile("(?<![\\w$])(System\\s*\\.\\s*(out|err|currentTimeMillis|nanoTime|getProperty|getenv)\\b"
                + "|new\\s+Thread\\b|Thread\\s*\\.|Math\\s*\\.\\s*random\\b|ThreadLocalRandom\\b|new\\s+Random\\s*\\(\\s*\\))");

        static List<String> check(Path root) throws IOException {
            List<String> result = new ArrayList<>();
            for (String area : List.of("domain", "application")) {
                Path dir = root.resolve(area);
                if (!Files.isDirectory(dir)) continue;
                boolean application = area.equals("application");
                try (Stream<Path> files = Files.walk(dir)) {
                    for (Path file : files.filter(path -> path.toString().endsWith(".java")).sorted().toList()) {
                        String[] lines = strip(Files.readString(file, StandardCharsets.UTF_8)).split("\n", -1);
                        for (int index = 0; index < lines.length; index++) {
                            for (String problem : problems(lines[index], application)) {
                                result.add(file + ":" + (index + 1) + " -> " + problem + " [SRC]");
                            }
                        }
                    }
                }
            }
            return result;
        }

        private static List<String> problems(String line, boolean application) {
            List<String> result = new ArrayList<>();
            Matcher imported = IMPORT.matcher(line);
            if (imported.find()) {
                String name = imported.group(2).replaceAll("\\s+", "");
                if (!(name.startsWith("java.") || name.startsWith("dev.hurtify.relicsaddon.domain.")
                        || application && name.startsWith("dev.hurtify.relicsaddon.application."))) {
                    result.add("import " + name);
                }
            }
            Matcher foreign = FOREIGN_ROOT.matcher(line);
            while (foreign.find()) result.add("qualified name " + foreign.group(1));
            Matcher own = (application ? OWN_ROOT_APPLICATION : OWN_ROOT_DOMAIN).matcher(line);
            while (own.find()) result.add("qualified name outside the " + (application ? "domain and application" : "domain"));
            Matcher impure = IMPURE.matcher(line);
            while (impure.find()) result.add("impure call " + impure.group(1).replaceAll("\\s+", " "));
            return result;
        }

        /** Blanks comments, string and char literals and text blocks, keeping line breaks so line numbers stay right. */
        static String strip(String source) {
            StringBuilder out = new StringBuilder(source.length());
            int length = source.length();
            int index = 0;
            while (index < length) {
                char c = source.charAt(index);
                char next = index + 1 < length ? source.charAt(index + 1) : '\0';
                if (c == '/' && next == '/') {
                    while (index < length && source.charAt(index) != '\n') index++;
                } else if (c == '/' && next == '*') {
                    index += 2;
                    while (index < length && !(source.charAt(index) == '*' && index + 1 < length && source.charAt(index + 1) == '/')) {
                        if (source.charAt(index) == '\n') out.append('\n');
                        index++;
                    }
                    index += 2;
                    out.append(' ');
                } else if (c == '"' && source.startsWith("\"\"\"", index)) {
                    index += 3;
                    while (index < length && !source.startsWith("\"\"\"", index)) {
                        char inner = source.charAt(index);
                        if (inner == '\\' && index + 1 < length) {
                            if (source.charAt(index + 1) == '\n') out.append('\n');
                            index += 2;
                            continue;
                        }
                        if (inner == '\n') out.append('\n');
                        index++;
                    }
                    index += 3;
                    out.append("\"\"");
                } else if (c == '"' || c == '\'') {
                    index++;
                    while (index < length && source.charAt(index) != c && source.charAt(index) != '\n') {
                        index += source.charAt(index) == '\\' ? 2 : 1;
                    }
                    if (index < length && source.charAt(index) == c) index++;
                    out.append(c).append(c);
                } else {
                    out.append(c);
                    index++;
                }
            }
            return out.toString().replace("\r", "");
        }

        private SourcePass() { }
    }

    private ArchitectureCheck() { }
}
