package dev.toxido.anticheat.app;

import com.sun.tools.attach.VirtualMachine;
import com.sun.tools.attach.VirtualMachineDescriptor;

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
    String displayName
) {
    private static final Pattern USERNAME_PATTERN =
        Pattern.compile("--username\\s+(?:\"([^\"]*)\"|(\\S+))");
    private static final Pattern UUID_PATTERN =
        Pattern.compile("--uuid\\s+(?:\"([^\"]*)\"|(\\S+))");
    private static final Pattern VERSION_PATTERN =
        Pattern.compile("--version\\s+(?:\"([^\"]*)\"|(\\S+))");

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
                .map(descriptor -> parse(descriptor))
                .filter(Objects::nonNull)
                .findFirst();
        } catch (Exception exception) {
            return Optional.empty();
        }
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
        try {
            return new MinecraftProcess(
                Long.parseLong(descriptor.id()),
                username,
                value(displayName, UUID_PATTERN),
                value(displayName, VERSION_PATTERN),
                displayName
            );
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static String value(String displayName, Pattern pattern) {
        Matcher matcher = pattern.matcher(displayName);
        if (!matcher.find()) {
            return null;
        }
        return matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
    }
}
