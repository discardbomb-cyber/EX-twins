package dev.hurtify.relicsaddon.contract;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * Golden masters for what must never change by accident: the bytes of every resource
 * ({@code resources.sha256}) and every string constant of the compiled mod ({@code literals.txt}):
 * ids, keys, NBT tags, config keys and messages. A literal that disappears on purpose is listed in
 * {@code literals-retired.txt} as {@code literal<TAB>stage<TAB>reason}.
 *
 * <pre>
 * --root &lt;project dir&gt; --golden &lt;dir&gt; --resources &lt;dir&gt;... --classes &lt;dir&gt;... [--record]
 * </pre>
 */
public final class ContractCheck {
    static final String RETIRED_HEADER = "literal\tstage\treason";
    private static final Set<String> BINARY = Set.of("png", "gif", "ogg", "jar", "nbt");

    public static void main(String[] args) throws IOException {
        Path root = null, golden = null;
        List<Path> resources = new ArrayList<>(), classes = new ArrayList<>();
        boolean record = false;
        for (int index = 0; index < args.length; index++) {
            switch (args[index]) {
                case "--root" -> root = Path.of(args[++index]);
                case "--golden" -> golden = Path.of(args[++index]);
                case "--resources" -> resources.add(Path.of(args[++index]));
                case "--classes" -> classes.add(Path.of(args[++index]));
                case "--record" -> record = true;
                default -> throw new IllegalArgumentException("Unknown argument " + args[index]);
            }
        }
        if (root == null || golden == null || resources.isEmpty() || classes.isEmpty()) {
            throw new IllegalArgumentException("Usage: --root <dir> --golden <dir> --resources <dir>... --classes <dir>... [--record]");
        }

        Map<String, String> hashes = hashResources(root, resources);
        List<ClassFile> compiled = readClasses(classes);
        Set<String> literals = literals(compiled);
        Path hashFile = golden.resolve("resources.sha256"), literalFile = golden.resolve("literals.txt");
        Path retiredFile = golden.resolve("literals-retired.txt");

        if (record) {
            Files.createDirectories(golden);
            List<String> hashLines = new ArrayList<>();
            hashes.forEach((path, hash) -> hashLines.add(hash + "  " + path));
            Files.write(hashFile, hashLines, StandardCharsets.UTF_8);
            Files.write(literalFile, literals.stream().map(ContractCheck::escape).toList(), StandardCharsets.UTF_8);
            if (!Files.exists(retiredFile)) Files.write(retiredFile, List.of(RETIRED_HEADER), StandardCharsets.UTF_8);
            System.out.println("Contracts recorded: " + hashes.size() + " resources, " + literals.size() + " literals from " + compiled.size()
                    + " classes");
            return;
        }

        List<String> problems = new ArrayList<>();
        Map<String, String> expected = new TreeMap<>();
        for (String line : Files.readAllLines(hashFile, StandardCharsets.UTF_8)) {
            if (line.isEmpty()) continue;
            int split = line.indexOf("  ");
            if (split != 64) throw new IllegalStateException("Malformed line in " + hashFile + ": " + line);
            expected.put(line.substring(split + 2), line.substring(0, split));
        }
        for (Map.Entry<String, String> entry : expected.entrySet()) {
            String now = hashes.get(entry.getKey());
            if (now == null) problems.add("resource removed: " + entry.getKey());
            else if (!now.equals(entry.getValue())) problems.add("resource changed: " + entry.getKey());
        }
        for (String path : hashes.keySet()) if (!expected.containsKey(path)) problems.add("resource added: " + path);

        Set<String> retired = new HashSet<>();
        for (String line : Files.readAllLines(retiredFile, StandardCharsets.UTF_8)) {
            if (line.isEmpty() || line.equals(RETIRED_HEADER)) continue;
            String[] columns = line.split("\t", -1);
            if (columns.length != 3 || columns[1].isBlank() || columns[2].isBlank()) {
                throw new IllegalStateException("Malformed line in " + retiredFile + " (literal<TAB>stage<TAB>reason): " + line);
            }
            retired.add(unescape(columns[0]));
        }
        Set<String> entries = new HashSet<>();
        StringBuilder joined = new StringBuilder();
        for (ClassFile type : compiled) {
            for (String entry : type.utf8) {
                if (entries.add(entry)) joined.append(entry).append('\0');
            }
        }
        String haystack = joined.toString();
        int checked = 0;
        for (String line : Files.readAllLines(literalFile, StandardCharsets.UTF_8)) {
            String literal = unescape(line);
            if (retired.contains(literal)) continue;
            checked++;
            if (entries.contains(literal)) continue;
            boolean found = literal.indexOf('\0') < 0 ? haystack.contains(literal) : entries.stream().anyMatch(entry -> entry.contains(literal));
            if (!found) problems.add("literal missing: \"" + line + "\"");
        }

        problems.forEach(System.out::println);
        if (!problems.isEmpty()) {
            throw new AssertionError(problems.size() + " contract differences; resources and goldens are frozen "
                    + "(retire removed literals in golden/literals-retired.txt with the stage and a reason)");
        }
        System.out.println("Contracts: " + hashes.size() + " resources unchanged, " + checked + " literals present (" + retired.size()
                + " retired) across " + compiled.size() + " classes");
    }

    /** SHA-256 of every file under the resource roots, keyed by its path relative to the project; text with CRLF read as LF. */
    static Map<String, String> hashResources(Path root, List<Path> resources) throws IOException {
        Map<String, String> result = new TreeMap<>();
        for (Path dir : resources) {
            if (!Files.isDirectory(dir)) throw new IllegalStateException("Resource directory missing: " + dir);
            try (Stream<Path> files = Files.walk(dir)) {
                for (Path file : files.filter(Files::isRegularFile).toList()) {
                    String path = root.toAbsolutePath().normalize().relativize(file.toAbsolutePath().normalize()).toString().replace('\\', '/');
                    byte[] bytes = Files.readAllBytes(file);
                    String name = file.getFileName().toString();
                    String extension = name.lastIndexOf('.') < 0 ? "" : name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
                    if (!BINARY.contains(extension)) bytes = lf(bytes);
                    result.put(path, sha256(bytes));
                }
            }
        }
        return result;
    }

    private static byte[] lf(byte[] bytes) {
        byte[] out = new byte[bytes.length];
        int length = 0;
        for (int index = 0; index < bytes.length; index++) {
            if (bytes[index] == '\r' && index + 1 < bytes.length && bytes[index + 1] == '\n') continue;
            out[length++] = bytes[index];
        }
        return java.util.Arrays.copyOf(out, length);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static List<ClassFile> readClasses(List<Path> dirs) throws IOException {
        List<ClassFile> result = new ArrayList<>();
        for (Path dir : dirs) {
            if (!Files.isDirectory(dir)) throw new IllegalStateException("Classes directory missing: " + dir);
            try (Stream<Path> files = Files.walk(dir)) {
                for (Path file : files.filter(path -> path.toString().endsWith(".class")).sorted().toList()) result.add(ClassFile.read(file));
            }
        }
        return result;
    }

    /**
     * Every CONSTANT_String payload. String-concatenation recipes (holding U+0001 or U+0002) are split
     * into their constant parts, and parts shorter than three characters are dropped.
     */
    static Set<String> literals(List<ClassFile> classes) {
        Set<String> result = new TreeSet<>();
        for (ClassFile type : classes) {
            for (String constant : type.strings) {
                if (constant.indexOf('\u0001') < 0 && constant.indexOf('\u0002') < 0) {
                    result.add(constant);
                    continue;
                }
                for (String part : constant.split("[\u0001\u0002]", -1)) if (part.length() >= 3) result.add(part);
            }
        }
        return result;
    }

    /** One literal per line: backslash, control, non-ASCII and line-break characters as Java-style escapes. */
    static String escape(String literal) {
        StringBuilder out = new StringBuilder(literal.length());
        for (int index = 0; index < literal.length(); index++) {
            char c = literal.charAt(index);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '\t' -> out.append("\\t");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                default -> {
                    if (c < 0x20 || c > 0x7E) out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        return out.toString();
    }

    static String unescape(String line) {
        StringBuilder out = new StringBuilder(line.length());
        for (int index = 0; index < line.length(); index++) {
            char c = line.charAt(index);
            if (c != '\\' || index + 1 >= line.length()) {
                out.append(c);
                continue;
            }
            char next = line.charAt(++index);
            switch (next) {
                case 't' -> out.append('\t');
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 'u' -> {
                    out.append((char) Integer.parseInt(line.substring(index + 1, index + 5), 16));
                    index += 4;
                }
                default -> out.append(next);
            }
        }
        return out.toString();
    }

    private ContractCheck() { }
}
