package dev.toxido.anticheat.agent;

import java.lang.instrument.Instrumentation;
import java.lang.management.ManagementFactory;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.MessageDigest;
import java.security.ProtectionDomain;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** JVM argumanlari, yuklu sinif kaynaklari ve mod dosyalarini tarar. */
public final class InjectionScanner {
    private static final String[] MOD_KEYWORDS = {
        "cheat", "hack", "triggerbot", "autoclick", "auto-click", "killaura",
        "kill-aura", "aimbot", "aim-bot", "xray", "x-ray", "reach", "velocity",
        "wurst", "meteor", "baritone", "impact", "future", "rusher", "liquidbounce",
        "sigma", "novoline", "esp", "freecam", "fly"
    };

    private static final String[] BENIGN_PREFIXES = {
        "java.", "javax.", "jdk.", "sun.", "com.sun.", "net.minecraft.",
        "net.fabricmc.", "net.minecraftforge.", "net.neoforged.", "cpw.mods.",
        "org.spongepowered.", "com.mojang.", "io.netty.", "org.lwjgl.",
        "org.slf4j.", "com.google.", "org.apache.", "it.unimi.", "oshi.",
        "dev.toxido.", "org.objectweb.asm.", "dev.toxido.anticheat.agent.asm."
    };

    private final Instrumentation instrumentation;
    private final AgentClient client;
    private final Set<String> seen = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "40x1d0AC-injection-scan");
        thread.setDaemon(true);
        return thread;
    });

    public InjectionScanner(Instrumentation instrumentation, AgentClient client) {
        this.instrumentation = instrumentation;
        this.client = client;
    }

    public void start() {
        scheduler.scheduleAtFixedRate(this::scanSafely, 3, 30, TimeUnit.SECONDS);
    }

    private void scanSafely() {
        try {
            scanJvmArguments();
            scanLoadedClasses();
            scanMods();
        } catch (Throwable throwable) {
            AgentMain.log("Enjeksiyon taramasi hatasi: " + throwable);
        }
    }

    private void scanJvmArguments() {
        try {
            List<String> arguments = ManagementFactory.getRuntimeMXBean().getInputArguments();
            for (String argument : arguments) {
                String lower = argument.toLowerCase(Locale.ROOT);
                boolean suspicious = lower.startsWith("-javaagent:")
                    || lower.startsWith("-agentlib:")
                    || lower.startsWith("-agentpath:")
                    || lower.startsWith("-xbootclasspath")
                    || lower.startsWith("-djava.system.class.loader=");
                if (!suspicious) {
                    continue;
                }
                String path = argument;
                int separator = argument.indexOf(':');
                if (separator > 0) {
                    path = argument.substring(separator + 1);
                    int equals = path.indexOf('=');
                    if (equals > 0) {
                        path = path.substring(0, equals);
                    }
                }
                boolean exists = !path.isBlank() && Files.exists(Path.of(path));
                String key = "inj:jvm:" + sha1(argument);
                if (!seen.add(key)) {
                    continue;
                }
                client.sendInjection(new InjectionFinding(
                    key,
                    "jvm_argument",
                    "JVM argumani",
                    path,
                    null,
                    null,
                    "Minecraft JVM'i harici bir ajan veya classloader ile baslatilmis.",
                    "high",
                    argument,
                    exists,
                    false
                ));
            }
        } catch (Throwable throwable) {
            AgentMain.log("JVM arguman taramasi hatasi: " + throwable);
        }
    }

    private void scanLoadedClasses() {
        if (instrumentation == null) {
            return;
        }
        for (Class<?> loaded : instrumentation.getAllLoadedClasses()) {
            try {
                String name = loaded.getName();
                String lower = name.toLowerCase(Locale.ROOT);
                if (isBenign(name) || name.contains("$$Lambda") || name.contains("$Proxy")) {
                    continue;
                }

                String path = sourcePath(loaded);
                boolean keyword = containsKeyword(lower) || (path != null && containsKeyword(path.toLowerCase(Locale.ROOT)));

                if (path != null && isSuspiciousPath(path)) {
                    String key = "inj:source:" + path.toLowerCase(Locale.ROOT);
                    if (seen.add(key)) {
                        client.sendInjection(new InjectionFinding(
                            key,
                            "class_source",
                            loaded.getSimpleName(),
                            path,
                            sha256(Path.of(path)),
                            null,
                            "Minecraft sinifi guvenilmeyen bir konumdan yuklenmis.",
                            "medium",
                            name,
                            Files.exists(Path.of(path)),
                            false
                        ));
                    }
                    continue;
                }

                if (keyword) {
                    String key = "inj:class:" + name;
                    if (seen.add(key)) {
                        client.sendInjection(new InjectionFinding(
                            key,
                            "loaded_class",
                            loaded.getSimpleName(),
                            path,
                            path == null ? null : sha256(Path.of(path)),
                            null,
                            "Adi hile/enjeksiyon kalibiyla eslesen sinif yuklu.",
                            path == null ? "high" : "medium",
                            name,
                            path == null || Files.exists(Path.of(path)),
                            path != null && !Files.exists(Path.of(path))
                        ));
                    }
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private void scanMods() {
        String appData = System.getenv("APPDATA");
        if (appData == null || appData.isBlank()) {
            return;
        }
        Path mods = Path.of(appData, ".minecraft", "mods");
        if (!Files.isDirectory(mods)) {
            return;
        }
        try (var stream = Files.list(mods)) {
            stream.filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                .forEach(this::inspectMod);
        } catch (Throwable throwable) {
            AgentMain.log("Mod taramasi hatasi: " + throwable);
        }
    }

    private void inspectMod(Path path) {
        String fileName = path.getFileName().toString();
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (!containsKeyword(lower)) {
            return;
        }
        String hash = sha256(path);
        String key = "inj:mod:" + (hash == null ? lower : hash);
        if (!seen.add(key)) {
            return;
        }
        client.sendInjection(new InjectionFinding(
            key,
            "mod_file",
            fileName,
            path.toString(),
            hash,
            null,
            "Mod dosyasi adi hile kalibiyla eslesiyor.",
            "high",
            fileName,
            Files.exists(path),
            false
        ));
    }

    private static boolean isBenign(String name) {
        for (String prefix : BENIGN_PREFIXES) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsKeyword(String value) {
        for (String keyword : MOD_KEYWORDS) {
            if (value.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isSuspiciousPath(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.contains("\\minecraft\\libraries\\")
            || lower.contains("\\minecraft\\versions\\")
            || lower.contains("\\40x1d0ac\\")
            || lower.contains("\\runtime\\")
            || lower.contains("\\program files")
            || lower.contains("\\windows\\")
            || lower.contains("\\.m2\\")) {
            return false;
        }
        return lower.contains("\\temp\\")
            || lower.contains("\\downloads\\")
            || lower.contains("\\desktop\\")
            || lower.contains("\\appdata\\local\\temp\\");
    }

    private static String sourcePath(Class<?> type) {
        try {
            ProtectionDomain domain = type.getProtectionDomain();
            CodeSource source = domain == null ? null : domain.getCodeSource();
            URL location = source == null ? null : source.getLocation();
            if (location == null || !"file".equalsIgnoreCase(location.getProtocol())) {
                return null;
            }
            return Path.of(location.toURI()).toString();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String sha256(Path path) {
        try {
            if (path == null || !Files.isRegularFile(path)) {
                return null;
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(path)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) > 0) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String sha1(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Throwable ignored) {
            return Integer.toHexString(value.hashCode());
        }
    }
}