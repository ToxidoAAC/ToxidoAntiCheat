package dev.toxido.anticheat.app;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Terminal tabanli baslatici. Splash Enter ile kapanir, arka plandaki
 * servis Minecraft kapanana kadar calismaya devam eder.
 */
public final class TerminalSplash {
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final LogReporter launcherLog =
        new LogReporter(LogReporter.appDataDirectory().resolve("launcher.log"));
    private final boolean preview;

    private volatile String serviceError;
    private volatile ConsoleOutput console;
    private volatile SplashScreen screen;

    public TerminalSplash() {
        this(false);
    }

    TerminalSplash(boolean preview) {
        this.preview = preview;
    }

    public void run() {
        ConsoleOutput output = ConsoleOutput.open();
        console = output;

        SplashScreen splash = new SplashScreen(output);
        screen = splash;

        Runtime.getRuntime().addShutdownHook(
            new Thread(this::restore, "toxido-ac-restore"));

        try {
            splash.start();
            startInput();
            if (!preview) {
                startService();
            }

            while (running.get()) {
                String error = serviceError;
                if (error != null) {
                    splash.setError(error);
                }
                splash.render();
                Thread.sleep(45L);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } finally {
            restore();
        }
    }

    private void startInput() {
        Thread input = new Thread(() -> {
            try {
                while (running.get()) {
                    int value = System.in.read();
                    if (value == -1) {
                        running.set(false);
                        return;
                    }
                    if (value == '\n' || value == '\r') {
                        running.set(false);
                        return;
                    }
                }
            } catch (IOException ignored) {
                // Konsol yoksa pencere kapanana kadar devam edilir.
            }
        }, "toxido-ac-input");
        input.setDaemon(true);
        input.start();
    }

    private void startService() {
        try {
            Path appPath = locateAppPath();
            Path javaHome = Path.of(System.getProperty("java.home"));
            Path java = javaHome.resolve("bin").resolve(isWindows() ? "javaw.exe" : "java");
            if (!Files.exists(java)) {
                java = javaHome.resolve("bin").resolve("java");
            }
            if (!Files.exists(java)) {
                throw new IOException("Java calistirilabilir dosyasi bulunamadi: " + java);
            }

            List<String> command = new ArrayList<>();
            command.add(java.toString());
            command.add("--add-modules");
            command.add("jdk.attach");

            String config = System.getProperty("ac.config");
            if (config != null && !config.isBlank()) {
                command.add("-Dac.config=" + config);
            }
            String agentJar = System.getProperty("ac.agent.jar");
            if (agentJar != null && !agentJar.isBlank()) {
                command.add("-Dac.agent.jar=" + agentJar);
            }

            if (Files.isDirectory(appPath)) {
                command.add("-cp");
                command.add(appPath.toString());
                command.add(Main.class.getName());
            } else {
                command.add("-jar");
                command.add(appPath.toString());
            }
            command.add("--service");

            Path appData = LogReporter.appDataDirectory();
            Files.createDirectories(appData);
            Path serviceOut = appData.resolve("service.out");

            ProcessBuilder builder = new ProcessBuilder(command);
            builder.redirectErrorStream(true);
            builder.redirectOutput(ProcessBuilder.Redirect.to(serviceOut.toFile()));

            Process service = builder.start();
            try {
                service.getOutputStream().close();
            } catch (IOException ignored) {
                // Servis stdin kullanmiyor.
            }
            launcherLog.log("Servis baslatildi (PID " + service.pid() + ").");

            Thread watcher = new Thread(() -> {
                try {
                    if (service.waitFor(900, TimeUnit.MILLISECONDS)
                        && service.exitValue() != 0) {
                        serviceError = "servis baslatilamadi";
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            }, "toxido-ac-service-watch");
            watcher.setDaemon(true);
            watcher.start();
        } catch (Exception exception) {
            serviceError = "servis baslatilamadi";
            launcherLog.log("Servis baslatilamadi: " + exception);
        }
    }

    private void restore() {
        SplashScreen currentScreen = screen;
        if (currentScreen != null) {
            currentScreen.close();
        }
        ConsoleOutput currentConsole = console;
        if (currentConsole != null) {
            currentConsole.close();
        }
    }

    private static Path locateAppPath() throws Exception {
        URI location = Main.class.getProtectionDomain().getCodeSource().getLocation().toURI();
        return Path.of(location).toAbsolutePath().normalize();
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "")
            .toLowerCase(Locale.ROOT)
            .contains("win");
    }
}
