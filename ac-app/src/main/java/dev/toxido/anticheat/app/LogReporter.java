package dev.toxido.anticheat.app;

import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Consumer;

/**
 * Servis durumunu Discord'a aktarir. AC'nin calistigi bilgisayarda log
 * dosyasi birakmaz; kuyruk, uzak hedef hazir olana kadar bellekte tutulur.
 */
public final class LogReporter implements Reporter {
    private static final DateTimeFormatter TIME_FORMAT =
        DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final int MAX_QUEUE = 500;

    private final BlockingQueue<String> queue = new LinkedBlockingQueue<>(MAX_QUEUE);
    private volatile Consumer<String> sink;
    private volatile Thread worker;

    public LogReporter(Path ignored) {
    }

    public static Path defaultLogFile() {
        return appDataDirectory().resolve("ac.log");
    }

    public static Path appDataDirectory() {
        String appData = System.getenv().getOrDefault(
            "APPDATA", System.getProperty("user.home"));
        return Path.of(appData, "40x1d0AC");
    }

    public void setSink(Consumer<String> sink) {
        this.sink = sink;
        if (sink != null) {
            startWorker();
        }
    }

    private synchronized void startWorker() {
        if (worker != null) {
            return;
        }
        Thread thread = new Thread(this::drain, "toxido-ac-remote-log");
        thread.setDaemon(true);
        worker = thread;
        thread.start();
    }

    private void drain() {
        while (true) {
            try {
                String line = queue.take();
                Consumer<String> current = sink;
                if (current != null) {
                    current.accept(line);
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException ignored) {
                // Uzak log hatasi servis calismasini engellemez.
            }
        }
    }

    @Override
    public synchronized void status(String status, String detail) {
        StringBuilder message = new StringBuilder("[STATUS] ").append(status);
        if (detail != null && !detail.isBlank()) {
            message.append(" - ").append(detail);
        }
        log(message.toString());
    }

    @Override
    public synchronized void log(String message) {
        String line = "[" + LocalTime.now().format(TIME_FORMAT) + "] "
            + message;
        if (!queue.offer(line)) {
            queue.poll();
            queue.offer(line);
        }
        if (sink != null) {
            startWorker();
        }
    }
}
