package dev.toxido.anticheat.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * Servis durumunu %APPDATA%\ToxidoAC\ac.log dosyasina yazar.
 * Terminalde bilgi kalabaligi olusturmamak icin tek cikis noktasi burasidir.
 */
public final class LogReporter implements Reporter {
    private static final DateTimeFormatter TIME_FORMAT =
        DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final long MAX_BYTES = 1_000_000L;

    private final Path logFile;

    public LogReporter(Path logFile) {
        this.logFile = logFile;
        try {
            Files.createDirectories(logFile.getParent());
        } catch (IOException ignored) {
            // Log dizini olusturulamazsa yazma denemesi sessizce basarisiz olur.
        }
    }

    public static Path defaultLogFile() {
        return appDataDirectory().resolve("ac.log");
    }

    public static Path appDataDirectory() {
        String appData = System.getenv().getOrDefault(
            "APPDATA", System.getProperty("user.home"));
        return Path.of(appData, "ToxidoAC");
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
            + message + System.lineSeparator();
        try {
            rotateIfNeeded();
            Files.writeString(
                logFile,
                line,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
            );
        } catch (IOException ignored) {
            // Servis, log yazamasa bile calismaya devam etmeli.
        }
    }

    private void rotateIfNeeded() throws IOException {
        if (!Files.exists(logFile) || Files.size(logFile) <= MAX_BYTES) {
            return;
        }
        Path old = logFile.resolveSibling(logFile.getFileName() + ".1");
        Files.move(logFile, old, StandardCopyOption.REPLACE_EXISTING);
    }
}
