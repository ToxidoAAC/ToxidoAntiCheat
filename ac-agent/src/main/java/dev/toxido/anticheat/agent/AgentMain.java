package dev.toxido.anticheat.agent;

import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.jar.JarFile;

/**
 * Minecraft JVM'ine attach ile yuklenen ajan.
 * Mod degildir; Fabric/Forge/NeoForge gerektirmez.
 */
public final class AgentMain {
    public static final String VERSION = "0.2.2";

    private static volatile JarFile bootstrapJar;

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
        String version = options.getOrDefault("version", "unknown");

        log("Ajan yuklendi (" + mode + "), port=" + port + ", surum=" + VERSION);

        if (instrumentation != null) {
            appendToBootstrap(instrumentation);
            log("Instrumentation hazir; yeniden tanimlama=" + instrumentation.isRedefineClassesSupported());
        }

        AgentClient client = new AgentClient(port, token);
        Thread thread = new Thread(client, "40x1d0AC-agent");
        thread.setDaemon(true);
        thread.start();

        if (instrumentation != null) {
            Phase2.start(instrumentation, client, version);
        }
    }

    static void onConnected(AgentClient client) {
        Phase2.announce(client);
    }

    private static void appendToBootstrap(Instrumentation instrumentation) {
        try {
            java.net.URL location = AgentMain.class.getProtectionDomain()
                .getCodeSource().getLocation();
            Path agentPath = Path.of(location.toURI());
            Path hookPath = agentPath.getParent() == null
                ? null
                : agentPath.getParent().resolve("ac-agent-hook.jar");
            if (hookPath == null || !Files.isRegularFile(hookPath)) {
                log("Hook jar bulunamadi; bootstrap eklemesi atlandi.");
                return;
            }
            bootstrapJar = new JarFile(hookPath.toFile());
            instrumentation.appendToBootstrapClassLoaderSearch(bootstrapJar);
            // Yalnizca HookBridge/AttackEvent bootstrap'a girer; AgentMain ve
            // AgentClient sistem classloader'inda kalir.
            Class.forName("dev.toxido.anticheat.agent.HookBridge", true, null);
            log("Hook jar bootstrap classloader'a eklendi: " + hookPath);
        } catch (Throwable throwable) {
            log("Bootstrap eklemesi basarisiz: " + throwable);
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