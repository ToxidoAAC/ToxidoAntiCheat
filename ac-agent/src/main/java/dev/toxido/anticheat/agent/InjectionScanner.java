package dev.toxido.anticheat.agent;

import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.lang.management.ManagementFactory;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.CodeSource;
import java.security.MessageDigest;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * JVM argumanlari, yuklu sinif kaynaklari ve mod dosyalarini tarar.
 *
 * <p>0.2.1: yanlis pozitifleri azaltmak icin array siniflari guvenli sekilde
 * ele alinir, genis substring keyword taramasi varsayilan olarak kapalidir ve
 * Discord'a yalnizca bilinen hile imzalari / yuksek guvenli bulgular gonderilir.</p>
 */
public final class InjectionScanner {
    /** Bilinen hile istemcisi paketleri; substring degil paket baslangici. */
    private static final String[] CHEAT_PACKAGE_PREFIXES = {
        "net.wurstclient.",
        "meteordevelopment.",
        "org.liquidbounce.",
        "net.ccbluex.liquidbounce.",
        "baritone.api.",
        "baritone.",
        "com.impactclient.",
        "com.futureclient.",
        "me.rusherhack.",
        "com.rusherhack.",
        "org.rusherhack.",
        "com.sigma.",
        "org.sigma.",
        "de.novoline.",
        "com.novoline.",
        "novoline.",
        "com.cheatbreaker."
    };

    /** Yalnizca hile modullerinde gorulen belirgin sinif adlari (tam eslesme). */
    private static final Set<String> CHEAT_CLASS_NAMES = Set.of(
        "killaura",
        "killauramodule",
        "autoclicker",
        "autoclickermodule",
        "triggerbot",
        "triggerbotmodule",
        "aimbot",
        "aimbotmodule",
        "crystalaura",
        "cheststealer",
        "autototem",
        "autoarmor",
        "bowaimbot",
        "autopot",
        "autoheal",
        "antiknockback"
    );

    /** Bilinen hile modu dosya adlari; yalnizca baslangic/eslesme, substring degil. */
    private static final String[] CHEAT_MOD_SIGNATURES = {
        "wurst",
        "meteor-client",
        "liquidbounce",
        "baritone",
        "rusherhack",
        "novoline",
        "impactclient",
        "futureclient",
        "sigmaclient",
        "cheatbreaker"
    };

    /**
     * Guvenli kabul edilen paketler. Buradaki liste yanlis pozitifleri
     * engellemek icin genistir; bilinmeyen modlar otomatik olarak hile
     * sayilmaz.
     */
    private static final String[] BENIGN_PREFIXES = {
        "java.", "javax.", "jdk.", "sun.", "com.sun.", "net.minecraft.",
        "net.fabricmc.", "net.minecraftforge.", "net.neoforged.", "cpw.mods.",
        "org.spongepowered.", "com.mojang.", "io.netty.", "org.lwjgl.",
        "org.slf4j.", "com.google.", "org.apache.", "it.unimi.", "oshi.",
        "dev.toxido.", "org.objectweb.asm.", "dev.toxido.anticheat.agent.asm.",
        "de.maxhenkel.", "dev.architectury.", "com.replaymod.", "com.viaversion.",
        "xaero.", "net.caffeinemc.", "net.digitalingot.", "io.reactivex.",
        "com.electronwill.", "gg.dawn.", "dev.isxander.", "okhttp3.",
        "retrofit2.", "com.squareup.", "kotlin.", "kotlinx.", "org.jetbrains.",
        "net.optifine.", "me.jellysquid.", "com.terraformersmc.",
        "net.irisshaders.", "org.quiltmc."
    };

    /**
     * Eski genis keyword listesi. Varsayilan olarak KAPALIDIR; yalnizca
     * teshis icin scan.keywords=true yapilabilir. Backend bu tur bulgulari
     * (loaded_class) varsayilan olarak Discord'a gondermez.
     */
    private static final String[] LEGACY_KEYWORDS = {
        "cheat", "hack", "triggerbot", "autoclick", "auto-click", "killaura",
        "kill-aura", "aimbot", "aim-bot", "xray", "x-ray", "reach", "velocity",
        "wurst", "meteor", "baritone", "impact", "future", "rusher",
        "liquidbounce", "sigma", "novoline", "esp", "freecam", "fly"
    };

    private static final String DEFAULT_ALLOWLIST_PACKAGES =
        "de.maxhenkel.,dev.architectury.,com.replaymod.,com.viaversion.,"
        + "xaero.,net.caffeinemc.,net.digitalingot.,io.reactivex.,"
        + "com.electronwill.,gg.dawn.,dev.isxander.";

    private static final String DEFAULT_ALLOWLIST_PATHS =
        "n3sttr-agent,client-runtime,.minecraft/versions/n3sttr";

    private static final String DEFAULT_PROPERTIES = String.join("\n",
        "# Toxido AC scanner configuration (0.2.1)",
        "# Bu dosya her taramada yeniden okunur.",
        "# Genis keyword/class-source taramasi varsayilan olarak kapalidir;",
        "# yalnizca bilinen hile imzalari Discord'a gonderilir.",
        "scan.arrays=false",
        "scan.keywords=false",
        "scan.classSource=false",
        "scan.signatures=true",
        "scan.jvmArguments=true",
        "scan.modFiles=true",
        "scan.externalAgent=false",
        "scan.minConfidence=high",
        "report.aggregate=true",
        "report.once=true",
        "report.maxPerScan=5",
        "report.file=reported-findings.txt",
        "allowlist.packages=" + DEFAULT_ALLOWLIST_PACKAGES,
        "allowlist.paths=" + DEFAULT_ALLOWLIST_PATHS,
        ""
    );

    private final Instrumentation instrumentation;
    private final AgentClient client;
    private final ScheduledExecutorService scheduler;

    public InjectionScanner(Instrumentation instrumentation, AgentClient client) {
        this.instrumentation = instrumentation;
        this.client = client;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "40x1d0AC-injection-scan");
            thread.setDaemon(true);
            return thread;
        });
    }

    public void start() {
        scheduler.scheduleWithFixedDelay(this::scanSafely, 3, 30, TimeUnit.SECONDS);
    }

    private void scanSafely() {
        try {
            ScannerConfig config = ScannerConfig.load();
            ScanContext context = new ScanContext(config);
            if (config.scanJvmArguments) {
                scanJvmArguments(context);
            }
            if (config.scanSignatures || config.scanKeywords
                || config.scanClassSource || config.scanExternalAgent) {
                scanLoadedClasses(context);
            }
            if (config.scanModFiles) {
                scanMods(context);
            }
            flush(context);
        } catch (Throwable throwable) {
            AgentMain.log("Enjeksiyon taramasi basarisiz: " + throwable);
        }
    }

    private void scanJvmArguments(ScanContext context) {
        try {
            for (String argument : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
                String lower = argument.toLowerCase(Locale.ROOT);
                boolean suspicious = lower.startsWith("-javaagent:")
                    || lower.startsWith("-agentlib:")
                    || lower.startsWith("-agentpath:")
                    || lower.startsWith("-xbootclasspath")
                    || lower.startsWith("-djava.system.class.loader=");
                if (!suspicious) {
                    continue;
                }
                String path = jvmPath(argument, lower);
                if (path != null && isAllowedPath(path, context.config)) {
                    continue;
                }
                String key = "inj:jvm:" + sha1(argument);
                InjectionFinding finding = new InjectionFinding(
                    key,
                    "jvm_argument",
                    "JVM argumani",
                    path,
                    null,
                    null,
                    "Minecraft JVM'i harici bir ajan veya classloader ile baslatilmis.",
                    "high",
                    argument,
                    exists(path),
                    false
                );
                if (!context.emit(key, finding)) {
                    return;
                }
            }
        } catch (Throwable throwable) {
            AgentMain.log("JVM arguman taramasi hatasi: " + throwable);
        }
    }

    private static String jvmPath(String argument, String lower) {
        String[] prefixes = {"-javaagent:", "-agentlib:", "-agentpath:"};
        for (String prefix : prefixes) {
            if (!lower.startsWith(prefix)) {
                continue;
            }
            String value = argument.substring(prefix.length());
            int equals = value.indexOf('=');
            if (equals >= 0) {
                value = value.substring(0, equals);
            }
            return stripQuotes(value);
        }
        if (lower.startsWith("-xbootclasspath")) {
            int colon = argument.indexOf(':');
            if (colon >= 0 && colon + 1 < argument.length()) {
                return stripQuotes(argument.substring(colon + 1));
            }
        }
        if (lower.startsWith("-djava.system.class.loader=")) {
            int equals = argument.indexOf('=');
            if (equals >= 0 && equals + 1 < argument.length()) {
                return stripQuotes(argument.substring(equals + 1));
            }
        }
        return null;
    }

    private static String stripQuotes(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }

    private void scanLoadedClasses(ScanContext context) {
        if (instrumentation == null) {
            return;
        }
        Class<?>[] classes;
        try {
            classes = instrumentation.getAllLoadedClasses();
        } catch (Throwable throwable) {
            AgentMain.log("Yuklu sinif listesi alinamadi: " + throwable);
            return;
        }
        if (classes == null) {
            return;
        }

        for (Class<?> loaded : classes) {
            try {
                Class<?> target = loaded;
                while (target.isArray()) {
                    target = target.getComponentType();
                }
                if (target.isPrimitive()) {
                    continue;
                }
                if (target != loaded && !context.config.scanArrays) {
                    continue;
                }

                String name = target.getName();
                if (name.contains("$$Lambda") || name.contains("$Proxy")) {
                    continue;
                }
                if (isBenign(name, context.config)) {
                    continue;
                }

                String path = sourcePath(target);
                String signature = context.config.scanSignatures ? cheatSignature(name) : null;
                if (signature != null) {
                    String key = "inj:signature:" + name;
                    InjectionFinding finding = new InjectionFinding(
                        key,
                        "cheat_signature",
                        target.getSimpleName(),
                        path,
                        sha256(path),
                        signature,
                        "Bilinen hile istemcisi paketi/sinifi yuklu.",
                        "high",
                        name,
                        exists(path),
                        false
                    );
                    if (!context.emit(key, finding)) {
                        return;
                    }
                    continue;
                }

                if (context.config.scanClassSource && path != null
                    && !isAllowedPath(path, context.config)
                    && isSuspiciousPath(path)) {
                    String key = "inj:source:" + normalizePath(path);
                    InjectionFinding finding = new InjectionFinding(
                        key,
                        "class_source",
                        target.getSimpleName(),
                        path,
                        sha256(path),
                        null,
                        "Minecraft sinifi guvenilmeyen bir konumdan yuklenmis.",
                        "medium",
                        name,
                        exists(path),
                        false
                    );
                    if (!context.emit(key, finding)) {
                        return;
                    }
                    continue;
                }

                if (context.config.scanKeywords && containsLegacyKeyword(name.toLowerCase(Locale.ROOT))) {
                    String key = "inj:class:" + name;
                    InjectionFinding finding = new InjectionFinding(
                        key,
                        "loaded_class",
                        target.getSimpleName(),
                        path,
                        sha256(path),
                        null,
                        "Adi hile kalibiyla eslesen sinif yuklu.",
                        "medium",
                        name,
                        exists(path),
                        false
                    );
                    if (!context.emit(key, finding)) {
                        return;
                    }
                    continue;
                }

                if (context.config.scanExternalAgent && isUnknownClassLoader(target)
                    && (path == null || !isAllowedPath(path, context.config))) {
                    String key = "inj:loader:" + name;
                    InjectionFinding finding = new InjectionFinding(
                        key,
                        "external_agent",
                        target.getSimpleName(),
                        path,
                        sha256(path),
                        null,
                        "Sinif bilinmeyen bir classloader tarafindan yuklenmis.",
                        "high",
                        "classloader=" + className(target.getClassLoader()) + "; class=" + name,
                        exists(path),
                        false
                    );
                    if (!context.emit(key, finding)) {
                        return;
                    }
                }
            } catch (Throwable ignored) {
                // Tek bir sinifin taranamaması digerlerini engellemez.
            }
        }
    }

    private static String cheatSignature(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        for (String prefix : CHEAT_PACKAGE_PREFIXES) {
            if (lower.startsWith(prefix)) {
                return prefix;
            }
        }
        String simple = simpleName(lower);
        int dollar = simple.indexOf('$');
        if (dollar > 0) {
            simple = simple.substring(0, dollar);
        }
        if (CHEAT_CLASS_NAMES.contains(simple)) {
            return "class:" + simple;
        }
        return null;
    }

    private static boolean isBenign(String name, ScannerConfig config) {
        String lower = name.toLowerCase(Locale.ROOT);
        for (String prefix : BENIGN_PREFIXES) {
            if (lower.startsWith(prefix)) {
                return true;
            }
        }
        return isAllowedPackage(lower, config.allowlistPackages);
    }

    private static boolean isAllowedPackage(String lowerName, List<String> allowed) {
        for (String prefix : allowed) {
            if (prefix == null || prefix.isBlank()) {
                continue;
            }
            if (prefix.endsWith(".")) {
                if (lowerName.startsWith(prefix)) {
                    return true;
                }
            } else if (lowerName.equals(prefix) || lowerName.startsWith(prefix + ".")) {
                return true;
            }
        }
        return false;
    }

    private static String simpleName(String name) {
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1) : name;
    }

    private static boolean containsLegacyKeyword(String lower) {
        for (String keyword : LEGACY_KEYWORDS) {
            if (lower.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private static String sourcePath(Class<?> type) {
        try {
            ProtectionDomain domain = type.getProtectionDomain();
            if (domain == null) {
                return null;
            }
            CodeSource source = domain.getCodeSource();
            if (source == null) {
                return null;
            }
            URL location = source.getLocation();
            if (location == null) {
                return null;
            }
            Path path = Path.of(location.toURI());
            return Files.isRegularFile(path) ? path.toString() : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean isUnknownClassLoader(Class<?> type) {
        ClassLoader loader = type.getClassLoader();
        if (loader == null) {
            return false;
        }
        ClassLoader current = loader;
        for (int depth = 0; depth < 8 && current != null; depth++) {
            String name = current.getClass().getName().toLowerCase(Locale.ROOT);
            if (name.contains("knot")
                || name.contains("fabric")
                || name.contains("forge")
                || name.contains("neoforge")
                || name.contains("quilt")
                || name.contains("n3sttr")
                || name.contains("launch")
                || name.contains("appclassloader")
                || name.contains("platformclassloader")
                || name.contains("agent")
                || name.contains("urlclassloader")) {
                return false;
            }
            current = current.getParent();
        }
        return true;
    }

    private static String className(ClassLoader loader) {
        return loader == null ? "bootstrap" : loader.getClass().getName();
    }

    private void scanMods(ScanContext context) {
        String appData = System.getenv("APPDATA");
        if (appData == null || appData.isBlank()) {
            return;
        }
        Path minecraft = Path.of(appData, ".minecraft");
        Path mods = minecraft.resolve("mods");
        if (Files.isDirectory(mods)) {
            scanModDirectory(mods, context);
        }
        Path versions = minecraft.resolve("versions");
        if (!Files.isDirectory(versions)) {
            return;
        }
        try (var stream = Files.list(versions)) {
            stream.filter(Files::isDirectory).forEach(version -> {
                Path versionMods = version.resolve("mods");
                if (Files.isDirectory(versionMods)) {
                    scanModDirectory(versionMods, context);
                }
            });
        } catch (Throwable throwable) {
            AgentMain.log("Versiyon mod taramasi hatasi: " + throwable);
        }
    }

    private boolean scanModDirectory(Path directory, ScanContext context) {
        try (var stream = Files.list(directory)) {
            List<Path> files = stream.filter(Files::isRegularFile).toList();
            for (Path path : files) {
                String fileName = path.getFileName().toString();
                String lower = fileName.toLowerCase(Locale.ROOT);
                if (!lower.endsWith(".jar")) {
                    continue;
                }
                String base = lower.substring(0, lower.length() - 4);
                String signature = modSignature(base);
                if (signature == null) {
                    continue;
                }
                String hash = sha256(path.toString());
                String key = "inj:mod:" + (hash == null ? base : hash);
                String evidence = fileName + (hash == null ? "" : " | " + hash);
                InjectionFinding finding = new InjectionFinding(
                    key,
                    "mod_file",
                    fileName,
                    path.toString(),
                    hash,
                    signature,
                    "Mod dosyasi bilinen hile istemcisiyle eslesiyor.",
                    "high",
                    evidence,
                    Files.isRegularFile(path),
                    false
                );
                if (!context.emit(key, finding)) {
                    return false;
                }
            }
            return true;
        } catch (Throwable throwable) {
            AgentMain.log("Mod dizini taramasi hatasi: " + throwable);
            return true;
        }
    }

    private static String modSignature(String base) {
        for (String signature : CHEAT_MOD_SIGNATURES) {
            if (base.equals(signature)
                || base.startsWith(signature + "-")
                || base.startsWith(signature + "_")
                || base.startsWith(signature + ".")) {
                return signature;
            }
        }
        return null;
    }

    private void flush(ScanContext context) {
        List<InjectionFinding> findings = context.pending;
        if (findings.isEmpty()) {
            return;
        }
        if (context.config.reportAggregate && findings.size() > 1) {
            StringBuilder keys = new StringBuilder();
            for (InjectionFinding finding : findings) {
                keys.append(finding.key).append('|');
            }
            String batchKey = "inj:batch:" + sha1(keys.toString());
            if (!context.store.contains(batchKey)) {
                InjectionFinding batch = new InjectionFinding(
                    batchKey,
                    "cheat_signature",
                    findings.size() + " hile imzasi",
                    null,
                    null,
                    "toplu",
                    "Ayni taramada birden fazla hile imzasi bulundu.",
                    "high",
                    summarize(findings),
                    false,
                    false
                );
                context.store.mark(batchKey);
                for (InjectionFinding finding : findings) {
                    context.store.mark(finding.key);
                }
                client.sendInjection(batch);
            } else {
                for (InjectionFinding finding : findings) {
                    context.store.mark(finding.key);
                }
            }
        } else {
            for (InjectionFinding finding : findings) {
                context.store.mark(finding.key);
                client.sendInjection(finding);
            }
        }
        AgentMain.log("Enjeksiyon taramasi: " + findings.size() + " yeni bulgu");
    }

    private static String summarize(List<InjectionFinding> findings) {
        StringBuilder builder = new StringBuilder();
        for (InjectionFinding finding : findings) {
            String line = finding.name
                + " | " + (finding.path == null ? "-" : finding.path)
                + " | " + (finding.signature == null ? "-" : finding.signature);
            if (builder.length() + line.length() + 1 > 900) {
                builder.append("\n...");
                break;
            }
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(line);
        }
        return builder.toString();
    }

    private static final class ScanContext {
        final ScannerConfig config;
        final ReportedStore store;
        final ScanBudget budget;
        final Set<String> seen = ConcurrentHashMap.newKeySet();
        final List<InjectionFinding> pending = new ArrayList<>();

        ScanContext(ScannerConfig config) {
            this.config = config;
            this.store = new ReportedStore(config.reportOnce, config.reportFile);
            this.budget = new ScanBudget(config.reportMaxPerScan);
        }

        boolean emit(String key, InjectionFinding finding) {
            if (confidenceRank(finding.confidence) < confidenceRank(config.minConfidence)) {
                return true;
            }
            if (seen.contains(key)) {
                return true;
            }
            if (store.contains(key)) {
                seen.add(key);
                return true;
            }
            if (!budget.take()) {
                return false;
            }
            pending.add(finding);
            seen.add(key);
            return true;
        }
    }

    private static final class ScanBudget {
        private int remaining;

        ScanBudget(int max) {
            this.remaining = Math.max(1, max);
        }

        boolean take() {
            if (remaining <= 0) {
                return false;
            }
            remaining--;
            return true;
        }
    }

    private static final class ScannerConfig {
        boolean scanArrays;
        boolean scanKeywords;
        boolean scanClassSource;
        boolean scanSignatures;
        boolean scanJvmArguments;
        boolean scanModFiles;
        boolean scanExternalAgent;
        String minConfidence;
        boolean reportAggregate;
        boolean reportOnce;
        int reportMaxPerScan;
        Path reportFile;
        List<String> allowlistPackages;
        List<String> allowlistPaths;

        static ScannerConfig load() {
            ScannerConfig config = new ScannerConfig();
            Path directory = appDirectory();
            Path file = directory.resolve("scanner.properties");
            if (!Files.isRegularFile(file)) {
                writeDefault(file);
            }
            Properties properties = new Properties();
            if (Files.isRegularFile(file)) {
                try (InputStream input = Files.newInputStream(file)) {
                    properties.load(input);
                } catch (Throwable throwable) {
                    AgentMain.log("scanner.properties okunamadi: " + throwable);
                }
            }

            config.scanArrays = bool(properties, "scan.arrays", false);
            config.scanKeywords = bool(properties, "scan.keywords", false);
            config.scanClassSource = bool(properties, "scan.classSource", false);
            config.scanSignatures = bool(properties, "scan.signatures", true);
            config.scanJvmArguments = bool(properties, "scan.jvmArguments", true);
            config.scanModFiles = bool(properties, "scan.modFiles", true);
            config.scanExternalAgent = bool(properties, "scan.externalAgent", false);

            String min = properties.getProperty("scan.minConfidence", "high")
                .trim().toLowerCase(Locale.ROOT);
            if (!min.equals("low") && !min.equals("medium") && !min.equals("high")) {
                min = "high";
            }
            config.minConfidence = min;

            config.reportAggregate = bool(properties, "report.aggregate", true);
            config.reportOnce = bool(properties, "report.once", true);
            config.reportMaxPerScan = intValue(properties, "report.maxPerScan", 5);
            if (config.reportMaxPerScan < 1) {
                config.reportMaxPerScan = 1;
            }
            if (config.reportMaxPerScan > 50) {
                config.reportMaxPerScan = 50;
            }

            config.reportFile = resolveReportFile(
                directory,
                properties.getProperty("report.file", "reported-findings.txt")
            );
            config.allowlistPackages = list(
                properties.getProperty("allowlist.packages"),
                DEFAULT_ALLOWLIST_PACKAGES,
                false
            );
            config.allowlistPaths = list(
                properties.getProperty("allowlist.paths"),
                DEFAULT_ALLOWLIST_PATHS,
                true
            );
            return config;
        }

        private static Path appDirectory() {
            String appData = System.getenv("APPDATA");
            if (appData == null || appData.isBlank()) {
                appData = System.getProperty("user.home");
            }
            return Path.of(appData, "40x1d0AC");
        }

        private static void writeDefault(Path file) {
            try {
                Path parent = file.toAbsolutePath().getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                if (!Files.exists(file)) {
                    Files.writeString(file, DEFAULT_PROPERTIES, StandardOpenOption.CREATE_NEW);
                }
            } catch (Throwable throwable) {
                AgentMain.log("Varsayilan scanner.properties yazilamadi: " + throwable);
            }
        }

        private static Path resolveReportFile(Path directory, String value) {
            String trimmed = value == null ? "" : value.trim();
            if (trimmed.isEmpty()) {
                trimmed = "reported-findings.txt";
            }
            Path path = Path.of(trimmed);
            return path.isAbsolute() ? path : directory.resolve(path);
        }

        private static boolean bool(Properties properties, String key, boolean fallback) {
            String value = properties.getProperty(key);
            if (value == null || value.isBlank()) {
                return fallback;
            }
            String lower = value.trim().toLowerCase(Locale.ROOT);
            return lower.equals("1") || lower.equals("true")
                || lower.equals("yes") || lower.equals("on");
        }

        private static int intValue(Properties properties, String key, int fallback) {
            try {
                return Integer.parseInt(properties.getProperty(key, Integer.toString(fallback)).trim());
            } catch (Throwable throwable) {
                return fallback;
            }
        }

        private static List<String> list(String value, String fallback, boolean path) {
            String raw = value == null || value.isBlank() ? fallback : value;
            List<String> result = new ArrayList<>();
            for (String part : raw.split(",")) {
                String trimmed = part.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                result.add(path ? normalizePath(trimmed) : trimmed.toLowerCase(Locale.ROOT));
            }
            return result;
        }
    }

    private static final class ReportedStore {
        private final boolean enabled;
        private final Path file;
        private final Set<String> keys = ConcurrentHashMap.newKeySet();

        ReportedStore(boolean enabled, Path file) {
            this.enabled = enabled;
            this.file = file;
            if (enabled) {
                load();
            }
        }

        boolean contains(String key) {
            return enabled && keys.contains(key);
        }

        void mark(String key) {
            if (!enabled || !keys.add(key)) {
                return;
            }
            try {
                Path parent = file.toAbsolutePath().getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.writeString(
                    file,
                    key + System.lineSeparator(),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
                );
            } catch (Throwable throwable) {
                AgentMain.log("Rapor kaydi yazilamadi: " + throwable);
            }
        }

        private void load() {
            try {
                if (!Files.isRegularFile(file)) {
                    return;
                }
                for (String line : Files.readAllLines(file)) {
                    String trimmed = line.trim();
                    if (!trimmed.isEmpty()) {
                        keys.add(trimmed);
                    }
                }
            } catch (Throwable throwable) {
                AgentMain.log("Rapor kaydi okunamadi: " + throwable);
            }
        }
    }

    private static int confidenceRank(String value) {
        if (value == null) {
            return -1;
        }
        switch (value.toLowerCase(Locale.ROOT)) {
            case "high":
                return 2;
            case "medium":
                return 1;
            case "low":
                return 0;
            default:
                return -1;
        }
    }

    private static boolean exists(String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        try {
            return Files.isRegularFile(Path.of(path));
        } catch (Throwable throwable) {
            return false;
        }
    }

    private static String sha256(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        try {
            return digest("SHA-256", Path.of(path));
        } catch (Throwable throwable) {
            return null;
        }
    }

    private static String sha1(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] bytes = digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (Throwable throwable) {
            return Integer.toHexString(value.hashCode());
        }
    }

    private static String digest(String algorithm, Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance(algorithm);
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String normalizePath(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replace('\\', '/');
    }

    private static boolean isAllowedPath(String path, ScannerConfig config) {
        String normalized = normalizePath(path);
        for (String allowed : config.allowlistPaths) {
            if (normalized.contains(allowed)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isSuspiciousPath(String path) {
        String lower = normalizePath(path);
        if (lower.contains("/minecraft/libraries/")
            || lower.contains("/minecraft/versions/")
            || lower.contains("/40x1d0ac/")
            || lower.contains("/runtime/")
            || lower.contains("/program files")
            || lower.contains("/windows/")
            || lower.contains("/.m2/")) {
            return false;
        }
        return lower.contains("/temp/")
            || lower.contains("/downloads/")
            || lower.contains("/desktop/")
            || lower.contains("/appdata/local/temp/");
    }
}
