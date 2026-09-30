package dev.hurtify.relicsaddon.contract;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

/**
 * Classes that are only named in strings must still resolve: the reflective client hook in RelicsAddon
 * and the LambDynamicLights entrypoint in neoforge.mods.toml. Nothing is initialised.
 */
public final class EntrypointCheck {
    private static final String REGISTRAR = "dev.hurtify.relicsaddon.client.ClientEventRegistrar";
    private static final String BRIDGE = "dev.hurtify.relicsaddon.client.light.DynamicLightsBridge";
    private static final String ENTRYPOINT = "\"lambdynlights:initializer\"=\"" + BRIDGE + "\"";

    public static void main(String[] args) throws Exception {
        ClassLoader loader = EntrypointCheck.class.getClassLoader();

        Class<?> registrar = Class.forName(REGISTRAR, false, loader);
        Method register = registrar.getMethod("register", Class.forName("net.neoforged.bus.api.IEventBus", false, loader));
        require(Modifier.isPublic(register.getModifiers()) && Modifier.isStatic(register.getModifiers()) && register.getReturnType() == void.class,
                REGISTRAR + ".register(IEventBus) must be public static void");

        ClassFile root;
        try (InputStream in = loader.getResourceAsStream("dev/hurtify/relicsaddon/RelicsAddon.class")) {
            require(in != null, "RelicsAddon.class not found");
            root = ClassFile.parse(in.readAllBytes());
        }
        require(root.strings.contains(REGISTRAR), "RelicsAddon no longer names " + REGISTRAR + " in its constant pool");

        boolean declared = false;
        for (URL url : Collections.list(loader.getResources("META-INF/neoforge.mods.toml"))) {
            String toml = read(url);
            if (toml.contains("modId=\"relics_addon\"")) declared |= toml.lines().anyMatch(line -> line.trim().equals(ENTRYPOINT));
        }
        require(declared, "neoforge.mods.toml of relics_addon must declare " + ENTRYPOINT);

        Class<?> bridge = Class.forName(BRIDGE, false, loader);
        Class<?> initializer = Class.forName("dev.lambdaurora.lambdynlights.api.DynamicLightsInitializer", false, loader);
        require(initializer.isAssignableFrom(bridge), BRIDGE + " must implement " + initializer.getName());

        System.out.println("Entrypoints: " + REGISTRAR + ".register(IEventBus) and the LambDynamicLights initializer " + BRIDGE + " resolve");
    }

    private static String read(URL url) throws IOException {
        try (InputStream in = url.openStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private EntrypointCheck() { }
}
