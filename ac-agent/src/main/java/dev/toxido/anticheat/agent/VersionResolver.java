package dev.toxido.anticheat.agent;

import org.objectweb.asm.Type;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Locale;

/**
 * Minecraft surumune ve loader'a gore attack metodunu cozer.
 * Bilinen isim -> imza taramasi -> kapali mod (yanlis rapor yok).
 */
public final class VersionResolver {
    public static final class Target {
        public final Class<?> owner;
        public final Class<?> minecraft;
        public final String ownerName;
        public final String methodName;
        public final String methodDesc;
        public final String loader;
        public final String version;
        public final String family;
        public final boolean instance;

        Target(
            Class<?> owner,
            Class<?> minecraft,
            String methodName,
            String methodDesc,
            String loader,
            String version,
            String family,
            boolean instance
        ) {
            this.owner = owner;
            this.minecraft = minecraft;
            this.ownerName = owner.getName();
            this.methodName = methodName;
            this.methodDesc = methodDesc;
            this.loader = loader;
            this.version = version;
            this.family = family;
            this.instance = instance;
        }

        public String hook() {
            return ownerName + "#" + methodName;
        }
    }

    private static final String[] OWNER_NAMES = {
        "net.minecraft.class_636",
        "net.minecraft.client.multiplayer.MultiPlayerGameMode",
        "net.minecraft.client.network.ClientPlayerInteractionManager",
        "net.minecraft.client.multiplayer.PlayerControllerMP"
    };

    private static final String[] MINECRAFT_NAMES = {
        "net.minecraft.client.Minecraft",
        "net.minecraft.class_310",
        "net.minecraft.client.MinecraftClient"
    };

    private VersionResolver() {
    }

    public static Target resolve(Instrumentation instrumentation, String versionHint) {
        return resolve(instrumentation, versionHint, true);
    }

    public static Target resolve(
        Instrumentation instrumentation,
        String versionHint,
        boolean logFailure
    ) {
        if (instrumentation == null) {
            return null;
        }

        Class<?>[] loaded = instrumentation.getAllLoadedClasses();
        Class<?> owner = findOwner(loaded);
        if (owner == null) {
            if (logFailure) {
                AgentMain.log("Etkilesim sinifi bulunamadi; triggerbot kapali.");
            }
            return null;
        }

        Method attack = findAttack(owner);
        if (attack == null) {
            if (logFailure) {
                AgentMain.log("Attack metodu bulunamadi: " + owner.getName());
            }
            return null;
        }

        Class<?> minecraft = findMinecraft(loaded);
        String loader = detectLoader(loaded);
        String version = versionHint;
        if (version == null || version.isBlank() || "unknown".equalsIgnoreCase(version)) {
            version = detectVersion(minecraft);
        }
        String family = family(version, owner.getName());
        boolean instance = !Modifier.isStatic(attack.getModifiers());

        AgentMain.log(
            "Surum cozuldu: surum=" + version
                + " loader=" + loader
                + " sinif=" + owner.getName()
                + " metot=" + attack.getName()
                + " aile=" + family
        );

        return new Target(
            owner,
            minecraft,
            attack.getName(),
            Type.getMethodDescriptor(attack),
            loader,
            version,
            family,
            instance
        );
    }

    private static Class<?> findOwner(Class<?>[] loaded) {
        for (String name : OWNER_NAMES) {
            for (Class<?> candidate : loaded) {
                if (name.equals(candidate.getName())) {
                    return candidate;
                }
            }
        }
        for (Class<?> candidate : loaded) {
            String simple = candidate.getSimpleName();
            if ("MultiPlayerGameMode".equals(simple)
                || "ClientPlayerInteractionManager".equals(simple)
                || "PlayerControllerMP".equals(simple)) {
                return candidate;
            }
        }
        return null;
    }

    private static Class<?> findMinecraft(Class<?>[] loaded) {
        for (String name : MINECRAFT_NAMES) {
            for (Class<?> candidate : loaded) {
                if (name.equals(candidate.getName())) {
                    return candidate;
                }
            }
        }
        for (Class<?> candidate : loaded) {
            String simple = candidate.getSimpleName();
            if ("Minecraft".equals(simple) || "MinecraftClient".equals(simple)) {
                return candidate;
            }
        }
        return null;
    }

    private static Method findAttack(Class<?> owner) {
        Method byName = null;
        Method byShape = null;
        try {
            for (Method method : owner.getDeclaredMethods()) {
                if (method.isBridge() || method.isSynthetic()) {
                    continue;
                }
                if (method.getParameterCount() != 2
                    || method.getReturnType() != void.class
                    || Modifier.isAbstract(method.getModifiers())
                    || Modifier.isNative(method.getModifiers())) {
                    continue;
                }

                String name = method.getName();
                String lower = name.toLowerCase(Locale.ROOT);
                boolean named = lower.equals("attackentity")
                    || lower.equals("attack")
                    || lower.equals("method_2918")
                    || lower.equals("m_105268_")
                    || lower.equals("func_78764_a");

                Class<?> first = method.getParameterTypes()[0];
                Class<?> second = method.getParameterTypes()[1];
                boolean shape = looksLikePlayer(first) && looksLikeEntity(second);

                if (named && shape) {
                    if (byName == null || lower.equals("attackentity") || lower.equals("method_2918")) {
                        byName = method;
                    }
                } else if (shape && byShape == null) {
                    byShape = method;
                }
            }
        } catch (Throwable throwable) {
            AgentMain.log("Attack metodu taramasi basarisiz: " + throwable);
        }
        return byName != null ? byName : byShape;
    }

    private static boolean looksLikePlayer(Class<?> type) {
        String name = type.getName().toLowerCase(Locale.ROOT);
        return name.contains("player") || name.contains("class_1657");
    }

    private static boolean looksLikeEntity(Class<?> type) {
        String name = type.getName().toLowerCase(Locale.ROOT);
        return name.contains("entity") || name.contains("class_1297");
    }

    private static String detectLoader(Class<?>[] loaded) {
        boolean forge = false;
        boolean neoforge = false;
        for (Class<?> candidate : loaded) {
            String name = candidate.getName();
            if (name.startsWith("net.fabricmc.loader")) {
                return "fabric";
            }
            if (name.startsWith("net.neoforged")) {
                neoforge = true;
            }
            if (name.startsWith("net.minecraftforge") || name.startsWith("cpw.mods")) {
                forge = true;
            }
        }
        if (neoforge) {
            return "neoforge";
        }
        if (forge) {
            return "forge";
        }
        return "vanilla";
    }

    private static String detectVersion(Class<?> minecraft) {
        if (minecraft != null) {
            try {
                Method getVersion = minecraft.getMethod("getVersion");
                Object value = getVersion.invoke(null);
                if (value != null) {
                    return value.toString();
                }
            } catch (Throwable ignored) {
            }
        }
        String property = System.getProperty("minecraft.version");
        return property == null || property.isBlank() ? "unknown" : property;
    }

    private static String family(String version, String ownerName) {
        if (version != null) {
            if (version.startsWith("26.")) {
                return "modern";
            }
            if (version.startsWith("1.")) {
                return "legacy";
            }
        }
        return ownerName.startsWith("net.minecraft.class_") ? "legacy" : "modern";
    }
}