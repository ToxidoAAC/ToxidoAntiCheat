package dev.toxido.anticheat.agent;

import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Oyunun sinif taramasina hazir olup olmadigini belirler.
 *
 * <p>Fabric acilirken oyun classloader'i Mixin kilidiyle ugrasir. Bu sirada
 * yuklu sinif listesini taramak (veya sinif yuklemek) oyunla karsilikli
 * kilitlenmeye yol acabiliyor. Bu yuzden ajan, oyunun kendi log dosyasinda
 * menuye hazirlik isaretlerini gorunceye kadar hicbir sinif taramasi
 * yapmaz; 45 saniye sonra emin olamasa da devam eder.</p>
 */
final class GameReadiness {
    /** Oyunun menuye hazir oldugunu gosteren log izleri. */
    private static final String[] MARKERS = {
        "Sound engine started",
        "OpenAL initialized",
        "Backend library:",
        "Created: ",
        "Connecting to",
        "Joined world"
    };
    private static final long MARKER_SETTLE_MS = 8000L;
    private static final long FORCE_MS = 45000L;
    private static final long CHECK_INTERVAL_MS = 2000L;
    private static final int TAIL_BYTES = 262144;

    private static final long startedAt = System.currentTimeMillis();
    private static volatile boolean ready;
    private static volatile long markerAt;
    private static volatile long lastCheckAt;
    private static volatile boolean missingLogReported;

    private GameReadiness() {
    }

    static boolean isReady(String gameDir) {
        if (ready) {
            return true;
        }
        long now = System.currentTimeMillis();
        if (now - startedAt >= FORCE_MS) {
            mark("sure");
            return true;
        }
        if (markerAt > 0L) {
            if (now - markerAt >= MARKER_SETTLE_MS) {
                mark("log");
                return true;
            }
            return false;
        }
        if (now - lastCheckAt < CHECK_INTERVAL_MS) {
            return false;
        }
        lastCheckAt = now;
        String tail = tail(gameDir);
        if (tail == null || tail.isEmpty()) {
            if (!missingLogReported) {
                missingLogReported = true;
                AgentMain.log(
                    "Oyun logu bulunamadi (gameDir=" + gameDir
                        + "); alternatif konumlar denenecek."
                );
            }
            return false;
        }
        if (hasMarker(tail)) {
            markerAt = now;
            AgentMain.log(
                "Oyun menuye hazirlaniyor; sinif taramasi "
                    + (MARKER_SETTLE_MS / 1000L) + " sn sonra baslayacak."
            );
        }
        return false;
    }

    private static boolean hasMarker(String tail) {
        for (String marker : MARKERS) {
            if (tail.contains(marker)) {
                if ("Created: ".equals(marker)
                    && !tail.contains("atlas")) {
                    continue;
                }
                return true;
            }
        }
        return false;
    }

    private static void mark(String reason) {
        if (ready) {
            return;
        }
        ready = true;
        AgentMain.log("Oyun hazir (" + reason + "); sinif taramasi basliyor.");
    }

    private static String tail(String gameDir) {
        for (Path log : logCandidates(gameDir)) {
            String content = readTail(log);
            if (content != null && !content.isEmpty()) {
                return content;
            }
        }
        return null;
    }

    /**
     * Launcher'lar oyun dizinini farkli yollarla verebiliyor. TLauncher bazen
     * profil dizinini, bazen de calisma dizinini kullanir; bu yuzden yalnizca
     * app'in buldugu gameDir'e guvenmiyoruz.
     */
    private static Set<Path> logCandidates(String gameDir) {
        Set<Path> logs = new LinkedHashSet<>();
        addLogCandidate(logs, gameDir);
        if (gameDir != null && !gameDir.isBlank()) {
            try {
                Path dir = Path.of(gameDir).toAbsolutePath().normalize();
                Path parent = dir.getParent();
                if (parent != null) {
                    addLogCandidate(logs, parent.toString());
                }
            } catch (Throwable ignored) {
                // Gecersiz yol; diger adaylara devam et.
            }
        }
        addLogCandidate(logs, System.getProperty("user.dir"));
        String appData = System.getenv("APPDATA");
        if (appData != null && !appData.isBlank()) {
            try {
                addLogCandidate(
                    logs,
                    Path.of(appData).resolve(".minecraft").toString()
                );
            } catch (Throwable ignored) {
                // APPDATA yolu okunamadi; diger adaylara devam et.
            }
        }
        return logs;
    }

    private static void addLogCandidate(Set<Path> logs, String directory) {
        if (directory == null || directory.isBlank()) {
            return;
        }
        try {
            logs.add(Path.of(directory).resolve("logs").resolve("latest.log"));
        } catch (Throwable ignored) {
            // Gecersiz yol; diger adaylara devam et.
        }
    }

    private static String readTail(Path log) {
        if (!Files.isRegularFile(log)) {
            return null;
        }
        try (RandomAccessFile file = new RandomAccessFile(log.toFile(), "r")) {
            long length = file.length();
            long start = Math.max(0L, length - TAIL_BYTES);
            file.seek(start);
            byte[] buffer = new byte[(int) (length - start)];
            file.readFully(buffer);
            return new String(buffer, StandardCharsets.UTF_8);
        } catch (Throwable throwable) {
            return null;
        }
    }
}
