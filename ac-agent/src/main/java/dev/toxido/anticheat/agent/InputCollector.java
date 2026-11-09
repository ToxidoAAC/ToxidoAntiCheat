package dev.toxido.anticheat.agent;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Windows tarafindaki dusuk seviyeli fare yardimcisini baslatir.
 * Yardimci, WH_MOUSE_LL ve Raw Input olaylarini satir satir bildirir.
 */
public final class InputCollector {
    private final Path helper;
    private final Consumer<InputEvent> sink;
    private final Runnable onReady;
    private final AtomicBoolean ready = new AtomicBoolean(false);
    private volatile Process process;

    public InputCollector(Path helper, Consumer<InputEvent> sink, Runnable onReady) {
        this.helper = helper;
        this.sink = sink;
        this.onReady = onReady;
    }

    public boolean start() {
        if (helper == null || !Files.isRegularFile(helper)) {
            AgentMain.log("Fare yardimcisi bulunamadi: " + helper);
            return false;
        }

        try {
            ProcessBuilder builder = new ProcessBuilder(
                helper.toString(),
                "--parent",
                Long.toString(ProcessHandle.current().pid())
            );
            builder.redirectErrorStream(true);
            process = builder.start();

            Thread reader = new Thread(this::readLoop, "40x1d0AC-input-reader");
            reader.setDaemon(true);
            reader.start();

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                Process current = process;
                if (current != null) {
                    current.destroy();
                }
            }, "40x1d0AC-input-stop"));
            return true;
        } catch (Exception exception) {
            AgentMain.log("Fare yardimcisi baslatilamadi: " + exception.getMessage());
            return false;
        }
    }

    public boolean isReady() {
        return ready.get();
    }

    private void readLoop() {
        Process current = process;
        if (current == null) {
            return;
        }

        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(current.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                handle(line.trim());
            }
        } catch (Exception exception) {
            AgentMain.log("Fare yardimcisi okunamadi: " + exception.getMessage());
        } finally {
            ready.set(false);
            AgentMain.log("Fare yardimcisi kapandi.");
        }
    }

    private void handle(String line) {
        if (line.isEmpty()) {
            return;
        }
        try {
            String[] parts = line.split(" ");
            switch (parts[0]) {
                case "READY" -> {
                    ready.set(true);
                    AgentMain.log("Fare yardimcisi hazir.");
                    if (onReady != null) {
                        onReady.run();
                    }
                }
                case "D", "U" -> publish(
                    "D".equals(parts[0]),
                    Long.parseLong(parts[1]),
                    "1".equals(parts[2]),
                    false,
                    parts.length > 3 ? Long.parseLong(parts[3]) : 0L
                );
                case "RD", "RU" -> publish(
                    "RD".equals(parts[0]),
                    Long.parseLong(parts[1]),
                    false,
                    true,
                    parts.length > 2 ? Long.parseLong(parts[2]) : 0L
                );
                default -> {
                    // Bilinmeyen satir; yoksay.
                }
            }
        } catch (Exception exception) {
            AgentMain.log("Fare olayi cozulemedi: " + line);
        }
    }

    private void publish(
        boolean down,
        long timeMs,
        boolean injected,
        boolean raw,
        long messageTime
    ) {
        if (sink != null) {
            sink.accept(new InputEvent(down, timeMs, injected, raw, messageTime));
        }
    }
}