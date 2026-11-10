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
    private final AtomicBoolean stopped = new AtomicBoolean(false);
    private final AtomicBoolean hookRegistered = new AtomicBoolean(false);
    private volatile Process process;
    private volatile boolean controllerDown;
    private volatile long lastStartAt;

    public InputCollector(Path helper, Consumer<InputEvent> sink, Runnable onReady) {
        this.helper = helper;
        this.sink = sink;
        this.onReady = onReady;
    }

    /**
     * Yardimciyi gerektiginde yeniden baslatir. Surec hala ayaktaysa dokunmaz;
     * coktu veya hic baslamadiysa en fazla 5 saniyede bir yeni deneme yapar.
     */
    public synchronized boolean ensureStarted() {
        if (stopped.get()) {
            return false;
        }
        Process current = process;
        if (current != null && current.isAlive()) {
            return true;
        }
        long now = System.currentTimeMillis();
        if (now - lastStartAt < 5000L) {
            return false;
        }
        lastStartAt = now;
        return start();
    }

    /** Yardimciyi kalici olarak durdurur; sonraki ensureStarted cagrilari bos doner. */
    public void stop() {
        stopped.set(true);
        ready.set(false);
        Process current = process;
        if (current != null) {
            current.destroy();
        }
    }

    public synchronized boolean start() {
        if (stopped.get()) {
            return false;
        }
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
            Process started = builder.start();
            process = started;

            Thread reader = new Thread(
                () -> readLoop(started), "40x1d0AC-input-reader"
            );
            reader.setDaemon(true);
            reader.start();

            if (hookRegistered.compareAndSet(false, true)) {
                Runtime.getRuntime().addShutdownHook(new Thread(
                    this::stop, "40x1d0AC-input-stop"
                ));
            }
            return true;
        } catch (Exception exception) {
            AgentMain.log("Fare yardimcisi baslatilamadi: " + exception.getMessage());
            return false;
        }
    }

    public boolean isReady() {
        return ready.get();
    }

    public boolean isControllerDown() {
        return controllerDown;
    }

    private void readLoop(Process current) {
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
            if (process == current) {
                ready.set(false);
                AgentMain.log("Fare yardimcisi kapandi.");
            }
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
                case "C" -> controllerDown = parts.length > 1 && "1".equals(parts[1]);
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