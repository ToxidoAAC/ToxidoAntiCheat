package dev.toxido.anticheat.app;

import com.sun.tools.attach.VirtualMachine;
import com.sun.tools.attach.VirtualMachineDescriptor;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Calisan Minecraft istemcisini (Java Edition) temsil eder.
 *
 * Tespit icin ProcessHandle yerine JVM Attach API'nin VirtualMachine.list()
 * kullanilir; Windows'ta ProcessHandle harici sureclerin komut satirini
 * vermedigi icin bu yol daha guvenilirdir. Boylece sadece attach edilebilir
 * JVM'ler listelenir ve modlu istemciler de gorulebilir.
 */
public record MinecraftProcess(
    long pid,
    String username,
    String uuid,
    String version,
    String gameDir,
    String displayName
) {
    private static final Pattern USERNAME_PATTERN =
        Pattern.compile("--username\\s+(?:\"([^\"]*)\"|(\\S+))");
    private static final Pattern UUID_PATTERN =
        Pattern.compile("--uuid\\s+(?:\"([^\"]*)\"|(\\S+))");
    private static final Pattern VERSION_PATTERN =
        Pattern.compile("--version\\s+(?:\"([^\"]*)\"|(\\S+))");
    private static final Pattern GAME_DIR_PATTERN =
        Pattern.compile("--gameDir\\s+(?:\"([^\"]*)\"|(\\S+))");

    private static final String[] KNOWN_MAIN_CLASSES = {
        "net.minecraft.client.main.main",
        "net.fabricmc.loader.impl.launch.knot.knotclient",
        "org.quiltmc.loader.impl.launch.knot.knotclient",
        "cpw.mods.bootstraplauncher.bootstraplauncher",
        "net.neoforged.bootstrap.bootstraplauncher",
        "net.minecraftforge.bootstrap.forgebootstrap"
    };

    public static Optional<MinecraftProcess> find() {
        try {
            long selfPid = ProcessHandle.current().pid();
            return VirtualMachine.list().stream()
                .filter(descriptor -> !Long.toString(selfPid).equals(descriptor.id()))
                .filter(descriptor -> isMinecraft(descriptor.displayName()))
                .map(MinecraftProcess::parse)
                .filter(Objects::nonNull)
                // Launcher JVM'leri (orn. TLauncher) oyun sanilmasin.
                .filter(process -> score(process.displayName()) > 0)
                .max(Comparator.comparingInt(
                    process -> score(process.displayName())
                ));
        } catch (Exception exception) {
            return Optional.empty();
        }
    }

    /**
     * TLauncher gibi launcher surecleri de komut satirinda --username ve
     * --version tasiyabilir. Gercek oyun JVM'ini one cikar, launcher'i ele.
     */
    private static int score(String displayName) {
        if (displayName == null || displayName.isBlank()) {
            return -100;
        }
        String lower = displayName.toLowerCase(Locale.ROOT);
        if (lower.contains("tlauncher")
            || lower.contains("org.tlauncher")
            || lower.contains("com.tlauncher")) {
            return -100;
        }
        int score = 0;
        for (String mainClass : KNOWN_MAIN_CLASSES) {
            if (lower.contains(mainClass)) {
                score += 20;
                break;
            }
        }
        if (lower.contains("--username")) {
            score += 5;
        }
        if (lower.contains("--version")) {
            score += 5;
        }
        if (lower.contains("--gamedir")) {
            score += 2;
        }
        return score;
    }

    private static boolean isMinecraft(String displayName) {
        if (displayName == null || displayName.isBlank()) {
            return false;
        }
        String lower = displayName.toLowerCase(Locale.ROOT);
        for (String mainClass : KNOWN_MAIN_CLASSES) {
            if (lower.contains(mainClass)) {
                return true;
            }
        }
        // Jar uzerinden baslatilan istemciler icin yedek kontrol.
        return lower.contains("--username") && lower.contains("--version");
    }

    private static MinecraftProcess parse(VirtualMachineDescriptor descriptor) {
        String displayName = descriptor.displayName();
        String username = value(displayName, USERNAME_PATTERN);
        if (username == null || username.isBlank()) {
            return null;
        }
        String gameDir = value(displayName, GAME_DIR_PATTERN);
        if (gameDir == null || gameDir.isBlank()) {
            gameDir = defaultGameDir();
        }
        try {
            return new MinecraftProcess(
                Long.parseLong(descriptor.id()),
                username,
                value(displayName, UUID_PATTERN),
                value(displayName, VERSION_PATTERN),
                gameDir,
                displayName
            );
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static String defaultGameDir() {
        String appData = System.getenv("APPDATA");
        if (appData != null && !appData.isBlank()) {
            return Path.of(appData, ".minecraft").toString();
        }
        return Path.of(System.getProperty("user.home", "."), ".minecraft")
            .toString();
    }

    private static String value(String displayName, Pattern pattern) {
        Matcher matcher = pattern.matcher(displayName);
        if (!matcher.find()) {
            return null;
        }
        return matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
    }
}
