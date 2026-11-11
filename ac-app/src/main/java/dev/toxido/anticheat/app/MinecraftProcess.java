package dev.toxido.anticheat.app;

import com.sun.tools.attach.VirtualMachine;
import com.sun.tools.attach.VirtualMachineDescriptor;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Calisan Minecraft istemcisini (Java Edition) temsil eder.
 *
 * Tespit icin ProcessHandle yerine JVM Attach API'nin VirtualMachine.list()
 * kullanilir; Windows'ta ProcessHandle harici sureclerin komut satirini
 * vermedigi icin bu yol daha guvenilirdir. Launcher'a ozel main class
 * listesine bagimli kalmamak icin JVM'in komut satiri, classpath'i ve
 * launcher property'leri birlikte degerlendirilir.
 */
public record MinecraftProcess(
    long pid,
    String username,
    String uuid,
    String version,
    String gameDir,
    String displayName,
    String launcher,
    String detectedBy
) {
    private static final Pattern USERNAME_PATTERN =
        Pattern.compile("--username(?:\\s+|=)(?:\"([^\"]*)\"|(\\S+))");
    private static final Pattern UUID_PATTERN =
        Pattern.compile("--uuid(?:\\s+|=)(?:\"([^\"]*)\"|(\\S+))");
    private static final Pattern VERSION_PATTERN =
        Pattern.compile("--version(?:\\s+|=)(?:\"([^\"]*)\"|(.+?)(?=\\s+--|$))");
    private static final Pattern GAME_DIR_PATTERN =
        Pattern.compile(
            "--gameDir(?:\\s+|=)(?:\"([^\"]*)\"|(.+?)(?=\\s+--|$))"
        );
    private static final Pattern ACCESS_TOKEN_PATTERN =
        Pattern.compile("(?i)(--accessToken(?:\\s+|=))(\"[^\"]*\"|\\S+)");

    private static final String[] KNOWN_MAIN_CLASSES = {
        "net.minecraft.client.main.main",
        "net.fabricmc.loader.impl.launch.knot.knotclient",
        "org.quiltmc.loader.impl.launch.knot.knotclient",
        "cpw.mods.bootstraplauncher.bootstraplauncher",
        "net.neoforged.bootstrap.bootstraplauncher",
        "net.minecraftforge.bootstrap.forgebootstrap",
        // Modrinth App, oyunu bu wrapper uzerinden baslatir. Gercek
        // Minecraft main class'i wrapper'a arguman olarak verilir.
        "com.modrinth.theseus.minecraftlaunch",
        // Yaygin ozel istemcilerin bilinen paketleri.
        "com.moonsworth.lunar",
        "net.badlion",
        "net.labymod"
    };

    private static final String[] GAME_CLASSPATH_MARKERS = {
        "net/minecraft",
        "net.minecraft",
        "minecraft/client",
        "fabric-loader",
        "net.fabricmc",
        "net.minecraftforge",
        "neoforge",
        "quiltmc",
        "lunar",
        "badlion",
        "labymod",
        "minecraft/client.jar",
        "client.jar",
        "versions/"
    };

    private static final String[] NON_GAME_MARKERS = {
        "org.jetbrains",
        "com.intellij",
        "org.apache.maven",
        "org.gradle",
        "org.jetbrains.kotlin",
        "com.sun.tools.javac",
        "jdk.jcmd",
        "surefire",
        "eclipse",
        "netbeans",
        "springframework",
        "org.springframework",
        "dev.toxido.anticheat.app"
    };

    public static Optional<MinecraftProcess> find() {
        try {
            long selfPid = ProcessHandle.current().pid();
            List<Scored> candidates = new ArrayList<>();

            for (VirtualMachineDescriptor descriptor : VirtualMachine.list()) {
                String pid = descriptor.id();
                if (Long.toString(selfPid).equals(pid)) {
                    continue;
                }
                String displayed = descriptor.displayName();
                if (isNonGame(displayed)) {
                    continue;
                }
                JvmInfo info = JvmInfo.read(pid, displayed);
                if (info == null || isNonGame(info.command())) {
                    continue;
                }
                if (!isMinecraft(info)) {
                    continue;
                }
                MinecraftProcess parsed = parse(pid, info);
                if (parsed == null) {
                    continue;
                }
                int score = score(info);
                if (score > 0) {
                    candidates.add(new Scored(parsed, score));
                }
            }

            return candidates.stream()
                .max(
                    Comparator.comparingInt(Scored::score)
                        .thenComparingLong(candidate -> candidate.process().pid())
                )
                .map(Scored::process);
        } catch (Exception exception) {
            return Optional.empty();
        }
    }

    private static boolean isMinecraft(JvmInfo info) {
        String evidence = info.evidence().toLowerCase(Locale.ROOT);
        for (String mainClass : KNOWN_MAIN_CLASSES) {
            if (evidence.contains(mainClass)) {
                return true;
            }
        }
        if (hasArg(evidence, "--username")
            && (hasArg(evidence, "--version")
                || hasArg(evidence, "--assetIndex")
                || hasArg(evidence, "--gameDir"))) {
            return true;
        }
        for (String marker : GAME_CLASSPATH_MARKERS) {
            if (info.classPath().toLowerCase(Locale.ROOT).contains(marker)) {
                return true;
            }
        }
        return !info.brand().isBlank();
    }

    private static int score(JvmInfo info) {
        String evidence = info.evidence().toLowerCase(Locale.ROOT);
        if (isNonGame(info.command())) {
            return -100;
        }
        int score = 0;
        for (String mainClass : KNOWN_MAIN_CLASSES) {
            if (evidence.contains(mainClass)) {
                score += 50;
                break;
            }
        }
        if (hasArg(evidence, "--username")) {
            score += 20;
        }
        if (hasArg(evidence, "--version")) {
            score += 15;
        }
        if (hasArg(evidence, "--gameDir")) {
            score += 10;
        }
        String classPath = info.classPath().toLowerCase(Locale.ROOT);
        for (String marker : GAME_CLASSPATH_MARKERS) {
            if (classPath.contains(marker)) {
                score += 15;
                break;
            }
        }
        if (!info.brand().isBlank()) {
            score += 20;
        }
        if (!"unknown".equals(detectLauncher(info))) {
            score += 10;
        }
        return score;
    }

    private static MinecraftProcess parse(String pid, JvmInfo info) {
        String source = join(
            info.command(),
            info.modrinth(),
            info.displayed()
        );
        String username = value(source, USERNAME_PATTERN);
        if (username == null || username.isBlank()) {
            return null;
        }
        String gameDir = value(source, GAME_DIR_PATTERN);
        if (gameDir == null || gameDir.isBlank()) {
            gameDir = !info.userDir().isBlank()
                ? info.userDir()
                : defaultGameDir();
        }
        String display = info.command().isBlank()
            ? info.displayed()
            : info.command();
        try {
            return new MinecraftProcess(
                Long.parseLong(pid),
                username,
                value(source, UUID_PATTERN),
                value(source, VERSION_PATTERN),
                gameDir,
                redact(display),
                detectLauncher(info),
                detectedBy(info)
            );
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static String detectLauncher(JvmInfo info) {
        String lower = join(
            info.command(),
            info.classPath(),
            info.brand()
        ).toLowerCase(Locale.ROOT);
        if (lower.contains("lunar")) {
            return "lunar";
        }
        if (lower.contains("badlion")) {
            return "badlion";
        }
        if (lower.contains("labymod")) {
            return "labymod";
        }
        if (lower.contains("modrinth")) {
            return "modrinth";
        }
        if (lower.contains("org.tlauncher")
            || lower.contains("com.tlauncher")) {
            return "tlauncher";
        }
        if (lower.contains("prism")
            || lower.contains("multimc")) {
            return "prism";
        }
        if (lower.contains("atlauncher")) {
            return "atlauncher";
        }
        if (lower.contains("curseforge")) {
            return "curseforge";
        }
        if (lower.contains("gdlauncher")) {
            return "gdlauncher";
        }
        if (lower.contains("net.fabricmc")
            || lower.contains("fabric-loader")) {
            return "fabric";
        }
        if (lower.contains("neoforge")) {
            return "neoforge";
        }
        if (lower.contains("minecraftforge")
            || lower.contains("cpw.mods")) {
            return "forge";
        }
        if (lower.contains("quiltmc")) {
            return "quilt";
        }
        if (!info.brand().isBlank()) {
            return info.brand();
        }
        if (lower.contains("net.minecraft.client.main.main")) {
            return "vanilla";
        }
        return "unknown";
    }

    private static String detectedBy(JvmInfo info) {
        String evidence = info.evidence().toLowerCase(Locale.ROOT);
        for (String mainClass : KNOWN_MAIN_CLASSES) {
            if (evidence.contains(mainClass)) {
                return "main-class";
            }
        }
        if (!info.brand().isBlank()) {
            return "launcher-brand";
        }
        if (hasArg(evidence, "--username")
            && (hasArg(evidence, "--version")
                || hasArg(evidence, "--assetIndex")
                || hasArg(evidence, "--gameDir"))) {
            return "game-args";
        }
        return "classpath";
    }

    private static boolean hasArg(String lower, String argument) {
        return lower.contains(argument.toLowerCase(Locale.ROOT));
    }

    /**
     * Launcher sureclerini ve gelistirme araclarini oyun JVM'i sanmamak icin
     * erken eleme. Genis "tlauncher" kontrolu yapilmaz; aksi halde TLauncher
     * uzerinden acilan gercek oyun da elenebilir.
     */
    private static boolean isNonGame(String text) {
        if (text == null || text.isBlank()) {
            return true;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        for (String marker : NON_GAME_MARKERS) {
            if (lower.contains(marker)) {
                return true;
            }
        }
        return lower.startsWith("org.tlauncher.")
            || lower.startsWith("com.tlauncher.")
            || lower.contains("org.tlauncher.tlauncher");
    }

    private static String defaultGameDir() {
        String appData = System.getenv("APPDATA");
        if (appData != null && !appData.isBlank()) {
            return Path.of(appData, ".minecraft").toString();
        }
        return Path.of(System.getProperty("user.home", "."), ".minecraft")
            .toString();
    }

    private static String value(String text, Pattern pattern) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        String value = matcher.group(1) != null
            ? matcher.group(1)
            : matcher.group(2);
        return value == null ? null : value.trim();
    }

    private static String redact(String text) {
        if (text == null) {
            return null;
        }
        return ACCESS_TOKEN_PATTERN.matcher(text).replaceAll("$1***");
    }

    private static String join(String... parts) {
        StringBuilder result = new StringBuilder();
        for (String part : parts) {
            if (part == null || part.isBlank()) {
                continue;
            }
            if (result.length() > 0) {
                result.append(' ');
            }
            result.append(part);
        }
        return result.toString();
    }

    private record Scored(MinecraftProcess process, int score) {
    }

    private record JvmInfo(
        String displayed,
        String command,
        String classPath,
        String userDir,
        String brand,
        String modrinth,
        String evidence
    ) {
        private static JvmInfo read(String pid, String displayed) {
            VirtualMachine machine = null;
            try {
                machine = VirtualMachine.attach(pid);
                Properties properties = machine.getSystemProperties();
                String command = property(properties, "sun.java.command");
                String classPath = property(properties, "java.class.path");
                String userDir = property(properties, "user.dir");
                String brand = property(properties, "minecraft.launcher.brand");
                String modrinth = property(
                    properties,
                    "modrinth.process.args"
                ).replace('\u001f', ' ');
                String evidence = join(
                    displayed,
                    command,
                    classPath,
                    brand,
                    modrinth
                );
                return new JvmInfo(
                    displayed,
                    command,
                    classPath,
                    userDir,
                    brand,
                    modrinth,
                    evidence
                );
            } catch (Exception ignored) {
                // Attach edilemeyen JVM'ler (farkli kullanici, kapali attach
                // mekanizmasi vb.) icin gorunen ad yeterli olabilir.
                if (displayed == null || displayed.isBlank()) {
                    return null;
                }
                return new JvmInfo(
                    displayed,
                    displayed,
                    "",
                    "",
                    "",
                    "",
                    displayed
                );
            } finally {
                if (machine != null) {
                    try {
                        machine.detach();
                    } catch (Exception ignored) {
                        // Detach basarisiz olsa da okuma tamamlanmis olabilir.
                    }
                }
            }
        }

        private static String property(
            Properties properties,
            String name
        ) {
            String value = properties.getProperty(name);
            return value == null ? "" : value;
        }
    }
}
