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
        Pattern.compile("--version\\s+(?:\"([^\"]*)\"|(.+?)(?=\\s+--|$))");
    private static final Pattern GAME_DIR_PATTERN =
        Pattern.compile("--gameDir\\s+(?:\"([^\"]*)\"|(.+?)(?=\\s+--|$))");

    private static final String MODRINTH_MAIN_CLASS =
        "com.modrinth.theseus.minecraftlaunch";

    private static final String[] KNOWN_MAIN_CLASSES = {
        "net.minecraft.client.main.main",
        "net.fabricmc.loader.impl.launch.knot.knotclient",
        "org.quiltmc.loader.impl.launch.knot.knotclient",
        "cpw.mods.bootstraplauncher.bootstraplauncher",
        "net.neoforged.bootstrap.bootstraplauncher",
        "net.minecraftforge.bootstrap.forgebootstrap",
        // Modrinth App, oyunu bu wrapper uzerinden baslatir. Gercek
        // Minecraft main class'i wrapper'a arguman olarak verilir.
        MODRINTH_MAIN_CLASS
    };

    public static Optional<MinecraftProcess> find() {
        try {
            long selfPid = ProcessHandle.current().pid();
            List<MinecraftProcess> candidates = new ArrayList<>();

            for (VirtualMachineDescriptor descriptor : VirtualMachine.list()) {
                if (Long.toString(selfPid).equals(descriptor.id())) {
                    continue;
                }
                String displayName = descriptor.displayName();
                if (!isMinecraft(displayName)) {
                    continue;
                }
                MinecraftProcess parsed = parse(descriptor.id(), displayName);
                if (parsed == null) {
                    // Bazi launcher'lar gorunen adda yalnizca main class'i
                    // birakir; oyun argumanlarini attach ile okumayi dene.
                    parsed = parse(descriptor.id(), commandLine(descriptor.id()));
                }
                if (parsed != null) {
                    candidates.add(parsed);
                }
            }

            return candidates.stream()
                .filter(process -> score(process.displayName()) > 0)
                .max(Comparator.comparingInt(
                    process -> score(process.displayName())
                ));
        } catch (Exception exception) {
            return Optional.empty();
        }
    }

    /**
     * Hedef JVM'in tam komut satirini okur. Modrinth App, gercek oyun
     * argumanlarini modrinth.process.args property'sinde \u001f ile ayirir.
     * Bu property, wrapper'in gorunen adinda arguman olmasa bile kullanilir.
     */
    private static String commandLine(String pid) {
        try {
            VirtualMachine machine = VirtualMachine.attach(pid);
            try {
                Properties properties = machine.getSystemProperties();
                String command = properties.getProperty("sun.java.command");
                String modrinthArgs = properties.getProperty("modrinth.process.args");

                StringBuilder combined = new StringBuilder();
                if (command != null && !command.isBlank()) {
                    combined.append(command);
                }
                if (modrinthArgs != null && !modrinthArgs.isBlank()) {
                    if (combined.length() > 0) {
                        combined.append(' ');
                    }
                    combined.append(modrinthArgs.replace('\u001f', ' '));
                }
                return combined.length() == 0 ? null : combined.toString();
            } finally {
                try {
                    machine.detach();
                } catch (Exception ignored) {
                    // Detach basarisiz olsa da okuma tamamlanmis olabilir.
                }
            }
        } catch (Exception ignored) {
            // Attach edilemeyen JVM'ler (farkli kullanici, kapali attach
            // mekanizmasi vb.) Minecraft adayi olarak degerlendirilmez.
            return null;
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

    private static MinecraftProcess parse(String pid, String commandLine) {
        if (commandLine == null || commandLine.isBlank()) {
            return null;
        }
        String username = value(commandLine, USERNAME_PATTERN);
        if (username == null || username.isBlank()) {
            return null;
        }
        String gameDir = value(commandLine, GAME_DIR_PATTERN);
        if (gameDir == null || gameDir.isBlank()) {
            gameDir = defaultGameDir();
        }
        try {
            return new MinecraftProcess(
                Long.parseLong(pid),
                username,
                value(commandLine, UUID_PATTERN),
                value(commandLine, VERSION_PATTERN),
                gameDir,
                commandLine
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
