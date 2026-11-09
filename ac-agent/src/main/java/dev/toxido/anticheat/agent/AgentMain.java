package dev.toxido.anticheat.agent;

import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Minecraft JVM'ine attach ile yuklenen ajan.
 * Mod degildir; Fabric/Forge gerektirmez.
 */
public final class AgentMain {
    public static final String VERSION = "0.1.0";

    private AgentMain() {
    }

    public static void agentmain(String args, Instrumentation instrumentation) {
        boot("agentmain", args, instrumentation);
    }

    public static void premain(String args, Instrumentation instrumentation) {
        boot("premain", args, instrumentation);
    }

    private static void boot(String mode, String args, Instrumentation instrumentation) {
        Map<String, String> options = parse(args);
        int port = Integer.parseInt(options.getOrDefault("port", "0"));
        String token = options.getOrDefault("token", "");

        log("Ajan yuklendi (" + mode + "), port=" + port + ", surum=" + VERSION);

        Thread thread = new Thread(new AgentClient(port, token), "40x1d0AC-agent");
        thread.setDaemon(true);
        thread.start();

        // Faz 2: instrumentation uzerinden sinif enstrumantasyonu ve
        // yuklu ajan/modul tespiti burada baslayacak.
        if (instrumentation != null) {
            log("Instrumentation hazir; yeniden tanimlama=" + instrumentation.isRedefineClassesSupported());
        }
    }

    static Map<String, String> parse(String args) {
        Map<String, String> options = new HashMap<>();
        if (args == null || args.isBlank()) {
            return options;
        }
        for (String part : args.split(";")) {
            int separator = part.indexOf('=');
            if (separator > 0) {
                options.put(part.substring(0, separator), part.substring(separator + 1));
            }
        }
        return options;
    }

    static void log(String message) {
        String line = "[" + Instant.now() + "] " + message;
        System.err.println("[ToxidoAC] " + line);
        try {
            Path file = Path.of(System.getProperty("java.io.tmpdir"), "40x1d0AC-agent.log");
            Files.writeString(
                file,
                line + System.lineSeparator(),
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
            );
        } catch (Exception ignored) {
            // Loglama basarisiz olsa da ajan calismaya devam eder.
        }
    }
}
