package dev.toxido.anticheat.agent;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Calisan Minecraft istemcisinin gercek surumunu ve loader/mapping ailesini
 * bulur. Launcher profil adi (orn. TLauncher'in N3stTR profili) gercek
 * surum olarak kullanilmaz.
 *
 * <p>Sirayla: oyun classloader'indaki version.json -> FabricLoader ->
 * launcher profil JSON'u -> profil jar'indaki version.json -> launcher
 * ipucu. Ipucu yalnizca gercek surum gibi gorunuyorsa kabul edilir.</p>
 */
public final class GameVersionDetector {
    public static final class Result {
        public final String version;
        public final String source;
        public final String loader;
        public final String mapping;

        Result(String version, String source, String loader, String mapping) {
            this.version = normalize(version);
            this.source = normalize(source);
            this.loader = normalize(loader);
            this.mapping = normalize(mapping);
        }

        public boolean known() {
            return !"unknown".equals(version);
        }

        public String summary() {
            return "version=" + version
                + " source=" + source
                + " loader=" + loader
                + " mapping=" + mapping;
        }
    }

    private static final Pattern JSON_ID =
        Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern JSON_NAME =
        Pattern.compile("\"name\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern JSON_INHERITS =
        Pattern.compile("\"inheritsFrom\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern JSON_JAR =
        Pattern.compile("\"jar\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern JSON_ASSET_INDEX =
        Pattern.compile("\"assetIndex\"\\s*:\\s*\\{[^}]*\"id\"\\s*:\\s*\"([^\"]+)\"",
            Pattern.DOTALL);
    private static final Pattern MINECRAFT_CLIENT_LIB =
        Pattern.compile("net\\.minecraft:client:([^\"\\s,}]+)");
    private static final Pattern VERSION_PATTERN = Pattern.compile(
        "^(?:\\d{1,2}\\.\\d{1,2}(?:\\.\\d{1,2})?"
            + "(?:[- ]?(?:pre|rc|snapshot)[- ]?\\d+)?"
            + "|\\d{2}w\\d{2}[a-z])$",
        Pattern.CASE_INSENSITIVE
    );

    private static volatile Result cached;

    static void clearCache() {
        cached = null;
    }

    private GameVersionDetector() {
    }

    public static Result detect(Class<?> minecraftClass) {
        return detect(minecraftClass, "", "");
    }

    public static Result detect(
        Class<?> minecraftClass,
        String gameDir,
        String profileName
    ) {
        Result previous = cached;
        if (previous != null && previous.known()) {
            return previous;
        }

        ClassLoader loader = loaderOf(minecraftClass);
        String loaderName = detectLoader(loader);
        String mapping = detectMapping(loader, minecraftClass);

        Result result = fromVersionJson(loader, minecraftClass, loaderName, mapping);
        if (result == null) {
            result = fromFabric(loader, loaderName, mapping);
        }
        if (result == null) {
            result = fromProfile(gameDir, profileName, loaderName, mapping);
        }
        if (result == null && looksLikeVersion(profileName)) {
            result = new Result(profileName, "launcher_profile", loaderName, mapping);
        }
        if (result == null) {
            result = new Result("unknown", "not_found", loaderName, mapping);
        }

        if (result.known() && minecraftClass != null) {
            // Sinif elde yokken loader/mapping guvenilir olmayabilir; boyle
            // bir sonucu onbellege alma, aksi halde Fabric istemcisi vanilla
            // sanilip desteklenmeyen surum olarak isaretlenebilir.
            cached = result;
            AgentMain.log("Surum tespiti: " + result.summary());
        }
        return result;
    }

    public static boolean looksLikeVersion(String value) {
        return value != null && VERSION_PATTERN.matcher(value.trim()).matches();
    }

    private static Result fromVersionJson(
        ClassLoader loader,
        Class<?> minecraftClass,
        String loaderName,
        String mapping
    ) {
        String text = readResource(loader, minecraftClass);
        Result parsed = parseVersionJson(text, loaderName, mapping);
        if (parsed != null) {
            return parsed;
        }
        return null;
    }

    private static Result parseVersionJson(
        String text,
        String loaderName,
        String mapping
    ) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String id = first(JSON_ID, text);
        if (!looksLikeVersion(id)) {
            String name = first(JSON_NAME, text);
            if (looksLikeVersion(name)) {
                id = name;
            }
        }
        if (!looksLikeVersion(id)) {
            return null;
        }
        return new Result(id, "version_json", loaderName, mapping);
    }

    private static Result fromFabric(
        ClassLoader loader,
        String loaderName,
        String mapping
    ) {
        if (!"fabric".equals(loaderName) && !"quilt".equals(loaderName)) {
            return null;
        }
        try {
            Class<?> fabricLoader = load(
                "net.fabricmc.loader.api.FabricLoader", loader
            );
            if (fabricLoader == null) {
                return null;
            }
            Method getInstance = fabricLoader.getMethod("getInstance");
            Object instance = getInstance.invoke(null);
            Method getModContainer = fabricLoader.getMethod(
                "getModContainer", String.class
            );
            Object optional = getModContainer.invoke(instance, "minecraft");
            if (!(optional instanceof Optional<?> containerOptional)
                || containerOptional.isEmpty()) {
                return null;
            }
            Object container = containerOptional.get();
            Object metadata = container.getClass().getMethod("getMetadata")
                .invoke(container);
            Object version = metadata.getClass().getMethod("getVersion")
                .invoke(metadata);
            Object friendly = version.getClass().getMethod("getFriendlyString")
                .invoke(version);
            String value = friendly == null ? null : friendly.toString();
            if (!looksLikeVersion(value)) {
                return null;
            }
            return new Result(value, "fabric_loader", loaderName, mapping);
        } catch (Throwable throwable) {
            AgentMain.debug("[VERSION] FabricLoader okunamadi: " + throwable);
            return null;
        }
    }

    private static Result fromProfile(
        String gameDir,
        String profileName,
        String loaderName,
        String mapping
    ) {
        if (gameDir == null || gameDir.isBlank()
            || profileName == null || profileName.isBlank()) {
            return null;
        }
        try {
            Path directory = Path.of(gameDir)
                .resolve("versions")
                .resolve(profileName);
            Path json = directory.resolve(profileName + ".json");
            if (!Files.isRegularFile(json)) {
                return null;
            }
            String text = Files.readString(json, StandardCharsets.UTF_8);
            String version = first(JSON_INHERITS, text);
            if (looksLikeVersion(version)) {
                return new Result(version, "profile_inherits", loaderName, mapping);
            }
            version = first(JSON_JAR, text);
            if (looksLikeVersion(version)) {
                return new Result(version, "profile_jar_field", loaderName, mapping);
            }

            Result fromJar = fromJar(directory.resolve(profileName + ".jar"),
                loaderName, mapping);
            if (fromJar != null) {
                return fromJar;
            }

            version = first(JSON_ASSET_INDEX, text);
            if (looksLikeVersion(version)) {
                return new Result(version, "profile_asset_index", loaderName, mapping);
            }

            Matcher library = MINECRAFT_CLIENT_LIB.matcher(text);
            while (library.find()) {
                if (looksLikeVersion(library.group(1))) {
                    return new Result(
                        library.group(1), "profile_library", loaderName, mapping
                    );
                }
            }

            version = first(JSON_ID, text);
            if (looksLikeVersion(version)) {
                return new Result(version, "profile_id", loaderName, mapping);
            }
        } catch (Throwable throwable) {
            AgentMain.debug("[VERSION] Profil JSON okunamadi: " + throwable);
        }
        return null;
    }

    private static Result fromJar(
        Path path,
        String loaderName,
        String mapping
    ) {
        if (path == null || !Files.isRegularFile(path)) {
            return null;
        }
        try (JarFile jar = new JarFile(path.toFile())) {
            JarEntry entry = jar.getJarEntry("version.json");
            if (entry == null) {
                return null;
            }
            try (InputStream input = jar.getInputStream(entry)) {
                String text = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                String version = first(JSON_ID, text);
                if (!looksLikeVersion(version)) {
                    version = first(JSON_NAME, text);
                }
                if (looksLikeVersion(version)) {
                    return new Result(version, "profile_jar_version", loaderName, mapping);
                }
            }
        } catch (Throwable throwable) {
            AgentMain.debug("[VERSION] Profil jar version.json okunamadi: "
                + throwable);
        }
        return null;
    }

    private static String readResource(ClassLoader loader, Class<?> minecraftClass) {
        String text = readStream(loader == null
            ? null
            : loader.getResourceAsStream("version.json"));
        if (text == null && minecraftClass != null) {
            text = readStream(minecraftClass.getResourceAsStream("/version.json"));
        }
        if (text == null) {
            ClassLoader context = Thread.currentThread().getContextClassLoader();
            if (context != null && context != loader) {
                text = readStream(context.getResourceAsStream("version.json"));
            }
        }
        return text;
    }

    private static String readStream(InputStream input) {
        if (input == null) {
            return null;
        }
        try (InputStream stream = input) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Throwable throwable) {
            return null;
        }
    }

    private static String detectLoader(ClassLoader loader) {
        if (load("net.fabricmc.loader.api.FabricLoader", loader) != null) {
            return "fabric";
        }
        if (load("org.quiltmc.loader.api.QuiltLoader", loader) != null) {
            return "quilt";
        }
        if (load("net.neoforged.fml.loading.FMLPaths", loader) != null
            || load("net.neoforged.bootstrap.Bootstraplauncher", loader) != null) {
            return "neoforge";
        }
        if (load("net.minecraftforge.fml.loading.FMLPaths", loader) != null
            || load("cpw.mods.bootstraplauncher.BootstrapLauncher", loader) != null) {
            return "forge";
        }
        return "vanilla";
    }

    private static String detectMapping(ClassLoader loader, Class<?> minecraftClass) {
        if (load("net.minecraft.class_310", loader) != null) {
            return "intermediary";
        }
        if (load("net.minecraft.client.Minecraft", loader) != null
            || load("net.minecraft.client.MinecraftClient", loader) != null) {
            return "mojang";
        }
        if (minecraftClass != null) {
            String name = minecraftClass.getName();
            if (name.contains("class_")) {
                return "intermediary";
            }
            if ("net.minecraft.client.Minecraft".equals(name)
                || "net.minecraft.client.MinecraftClient".equals(name)) {
                return "mojang";
            }
        }
        return "unknown";
    }

    private static Class<?> load(String name, ClassLoader loader) {
        try {
            return Class.forName(name, false, loader);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static ClassLoader loaderOf(Class<?> type) {
        if (type != null && type.getClassLoader() != null) {
            return type.getClassLoader();
        }
        ClassLoader context = Thread.currentThread().getContextClassLoader();
        return context == null ? GameVersionDetector.class.getClassLoader() : context;
    }

    private static String first(Pattern pattern, String text) {
        if (text == null) {
            return null;
        }
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String normalize(String value) {
        return value == null || value.isBlank()
            ? "unknown"
            : value.trim().toLowerCase(Locale.ROOT);
    }
}
