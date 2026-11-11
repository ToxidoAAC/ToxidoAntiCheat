package dev.toxido.anticheat.agent;

import org.objectweb.asm.Type;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Locale;

/**
 * Calisan surum/loader icin katalogdan gelen attack zincirini cozer.
 * Katalog satiri yoksa tahminle yanlis profile gecmez; nedeni panoya yazar.
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
        public final VersionProfile profile;

        Target(
            Class<?> owner,
            Class<?> minecraft,
            String methodName,
            String methodDesc,
            String loader,
            String version,
            String family,
            boolean instance,
            VersionProfile profile
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
            this.profile = profile;
        }

        public String hook() {
            return ownerName + "#" + methodName;
        }
    }

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

        // Erken tespit yalnizca dosyadan surum/loader verir. Vanilla
        // obfuscated istemcide Minecraft sinifi ancak bu surumun katalog
        // satirindaki adla bulunabilir; bu yuzden adaylar sirayla denenir.
        GameVersionDetector.Result early = GameVersionDetector.detect(
            null, gameDir, profileName
        );
        String hintVersion = early.known() ? early.version : versionHint;
        Class<?> minecraft = findMinecraft(loaded, hintVersion);
        if (minecraft == null) {
            if (logFailure) {
                AgentMain.log(
                    "Minecraft sinifi henuz yuklu degil; katalog cozumlemesi bekliyor."
                );
            }
            return null;
        }

        GameVersionDetector.Result detected = GameVersionDetector.detect(
            minecraft, gameDir, profileName
        );
        String loader = detected.known() ? detected.loader : detectLoader(loaded);
        String version = detected.known() ? detected.version : hintVersion;
        if (!GameVersionDetector.looksLikeVersion(version)) {
            version = detectVersion(minecraft);
        }

        VersionCatalog.Resolved resolved = VersionProfiles.resolve(
            version, loader, detected.mapping
        );
        Phase2.versionDetected(detected, resolved.profile, resolved);
        if (!resolved.ok()) {
            AgentMain.log(
                "Desteklenmeyen istemci: surum=" + version
                    + " loader=" + loader
                    + " mapping=" + detected.mapping
                    + " katalog=" + resolved.reason
                    + " eksik=" + resolved.missing
            );
            return null;
        }

        VersionProfile profile = resolved.profile;
        VersionProfiles.setActive(profile);

        Class<?> owner = findLoaded(loaded, profile.attackClass);
        if (owner == null) {
            if (logFailure) {
                AgentMain.log(
                    "Katalog attack sinifi henuz yuklu degil: "
                        + profile.attackClass + " (" + profile.id + ")"
                );
            }
            return null;
        }
        Method attack = findAttack(owner, profile.attackMethod);
        if (attack == null) {
            attack = findAttackShape(owner);
            if (attack != null) {
                AgentMain.log(
                    "Attack metodu katalog adiyla bulunamadi; imza yedegi: "
                        + owner.getName() + "#" + attack.getName()
                        + " (beklenen=" + profile.attackMethod + ")"
                );
            }
        }
        if (attack == null) {
            if (logFailure) {
                AgentMain.log(
                    "Attack metodu bulunamadi: " + owner.getName()
                        + "#" + profile.attackMethod
                );
            }
            return null;
        }

        String family = family(version, owner.getName());
        AgentMain.log(
            "Surum cozuldu: surum=" + version
                + " kaynak=" + detected.source
                + " loader=" + loader
                + " mapping=" + detected.mapping
                + " profil=" + profile.id
                + " katalog=" + resolved.reason
                + " dogrulandi=" + profile.verified
                + " loaderDogrulandi=" + profile.loaderVerified
                + " eksik=" + (resolved.missing.isEmpty() ? "-" : resolved.missing)
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
            !Modifier.isStatic(attack.getModifiers()),
            profile
        );
    }

    private static Class<?> findMinecraft(Class<?>[] loaded, String version) {
        for (String name : MINECRAFT_NAMES) {
            Class<?> found = findLoaded(loaded, name);
            if (found != null) {
                return found;
            }
        }
        for (VersionProfile candidate : VersionProfiles.candidates(version)) {
            Class<?> found = findLoaded(loaded, candidate.minecraftClass);
            if (found != null) {
                return found;
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

    private static Class<?> findLoaded(Class<?>[] loaded, String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        for (Class<?> candidate : loaded) {
            if (name.equals(candidate.getName())) {
                return candidate;
            }
        }
        return null;
    }

    private static Method findAttack(Class<?> owner, String methodName) {
        if (methodName == null || methodName.isEmpty()) {
            return null;
        }
        for (Method method : owner.getDeclaredMethods()) {
            if (method.isBridge()
                || method.isSynthetic()
                || method.getParameterCount() != 2
                || method.getReturnType() != void.class
                || !methodName.equals(method.getName())) {
                continue;
            }
            return method;
        }
        return null;
    }

    /**
     * Katalogdaki ad guncellenmis olabilir; ayni sinif icinde oyuncu+entity
     * alan 2 parametreli void metodu dar kapsamda ara. Global sinif taramasi
     * yoktur.
     */
    private static Method findAttackShape(Class<?> owner) {
        try {
            for (Method method : owner.getDeclaredMethods()) {
                if (method.isBridge()
                    || method.isSynthetic()
                    || method.getParameterCount() != 2
                    || method.getReturnType() != void.class
                    || Modifier.isAbstract(method.getModifiers())
                    || Modifier.isNative(method.getModifiers())) {
                    continue;
                }
                if (looksLikePlayer(method.getParameterTypes()[0])
                    && looksLikeEntity(method.getParameterTypes()[1])) {
                    return method;
                }
            }
        } catch (Throwable throwable) {
            AgentMain.log("Attack imza yedegi basarisiz: " + throwable);
        }
        return null;
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
                // getVersion ornege bagli olabilir; asagidaki ipuclarina gec.
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
