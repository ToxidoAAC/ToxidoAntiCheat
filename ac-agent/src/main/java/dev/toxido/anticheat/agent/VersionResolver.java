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

    public static final class PacketTarget {
        public final Class<?> owner;
        public final String ownerName;
        public final String methodName;
        public final String methodDesc;
        public final boolean instance;

        PacketTarget(
            Class<?> owner,
            String methodName,
            String methodDesc,
            boolean instance
        ) {
            this.owner = owner;
            this.ownerName = owner.getName();
            this.methodName = methodName;
            this.methodDesc = methodDesc;
            this.instance = instance;
        }

        public String hook() {
            return ownerName + "#" + methodName;
        }
    }

    private static final String[] CONNECTION_NAMES = {
        "net.minecraft.class_2535",
        "net.minecraft.network.Connection",
        "net.minecraft.network.ClientConnection",
        "net.minecraft.client.multiplayer.ClientConnection"
    };

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
        return resolve(instrumentation, versionHint, "", "", logFailure);
    }

    public static Target resolve(
        Instrumentation instrumentation,
        String versionHint,
        String gameDir,
        String profileName,
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
        GameVersionDetector.Result detected = GameVersionDetector.detect(
            minecraft, gameDir, profileName
        );
        String loader = detected.known() ? detected.loader : detectLoader(loaded);
        String version = detected.known() ? detected.version : versionHint;
        if (version == null || version.isBlank()
            || "unknown".equalsIgnoreCase(version)
            || !GameVersionDetector.looksLikeVersion(version)) {
            version = detectVersion(minecraft);
        }
        String family = family(version, owner.getName());
        VersionProfile profile = VersionProfiles.select(
            version, loader, detected.mapping
        );
        Phase2.versionDetected(
            detected,
            profile == null ? "-" : profile.id
        );
        boolean instance = !Modifier.isStatic(attack.getModifiers());

        AgentMain.log(
            "Surum cozuldu: surum=" + version
                + " kaynak=" + detected.source
                + " loader=" + loader
                + " mapping=" + detected.mapping
                + " profil=" + (profile == null ? "-" : profile.id)
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

    public static PacketTarget resolvePacket(Instrumentation instrumentation) {
        if (instrumentation == null) {
            return null;
        }
        Class<?> owner = findConnection(instrumentation.getAllLoadedClasses());
        if (owner == null) {
            return null;
        }
        Method send = findSend(owner);
        if (send == null) {
            AgentMain.log("Baglanti sinifi bulundu ama send metodu yok: " + owner.getName());
            return null;
        }
        boolean instance = !Modifier.isStatic(send.getModifiers());
        AgentMain.log(
            "Paket hook cozuldu: sinif=" + owner.getName()
                + " metot=" + send.getName()
                + " imza=" + Type.getMethodDescriptor(send)
        );
        return new PacketTarget(
            owner, send.getName(), Type.getMethodDescriptor(send), instance
        );
    }

    private static Class<?> findConnection(Class<?>[] loaded) {
        for (String name : CONNECTION_NAMES) {
            for (Class<?> candidate : loaded) {
                if (name.equals(candidate.getName())) {
                    return candidate;
                }
            }
        }
        for (Class<?> candidate : loaded) {
            String simple = candidate.getSimpleName();
            if ("ClientConnection".equals(simple) || "Connection".equals(simple)) {
                if (findSend(candidate) != null) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private static Method findSend(Class<?> owner) {
        Method named = null;
        Method shaped = null;
        for (Method method : owner.getDeclaredMethods()) {
            if (method.isBridge() || method.isSynthetic()
                || method.getParameterCount() != 1
                || method.getReturnType() != void.class
                || Modifier.isAbstract(method.getModifiers())
                || Modifier.isNative(method.getModifiers())
                || Modifier.isStatic(method.getModifiers())) {
                continue;
            }
            String parameter = method.getParameterTypes()[0].getName()
                .toLowerCase(Locale.ROOT);
            boolean packet = parameter.contains("class_2596")
                || parameter.contains("packet");
            if (!packet) {
                continue;
            }
            String name = method.getName();
            String lower = name.toLowerCase(Locale.ROOT);
            if (lower.equals("send")
                || lower.equals("sendpacket")
                || lower.equals("method_10743")
                || lower.equals("m_129512_")
                || lower.equals("func_147298_b")) {
                if (named == null || lower.equals("send") || lower.equals("method_10743")) {
                    named = method;
                }
            } else if (shaped == null) {
                shaped = method;
            }
        }
        return named != null ? named : shaped;
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