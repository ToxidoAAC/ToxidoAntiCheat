package dev.toxido.anticheat.app;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.function.Consumer;

/** Minecraft surecindeki native modulleri ve manuel mapped bellegi tarar. */
public final class ModuleScanner {
    public static final class Finding {
        public final String key;
        public final String kind;
        public final String name;
        public final String path;
        public final String sha256;
        public final String signature;
        public final String description;
        public final String confidence;
        public final String evidence;
        public final boolean exists;
        public final boolean deleted;

        Finding(
            String key,
            String kind,
            String name,
            String path,
            String sha256,
            String signature,
            String description,
            String confidence,
            String evidence,
            boolean exists,
            boolean deleted
        ) {
            this.key = key;
            this.kind = kind;
            this.name = name;
            this.path = path;
            this.sha256 = sha256;
            this.signature = signature;
            this.description = description;
            this.confidence = confidence;
            this.evidence = evidence;
            this.exists = exists;
            this.deleted = deleted;
        }
    }

    private ModuleScanner() {
    }

    public static Path helperPath() {
        try {
            Path location = Path.of(
                ModuleScanner.class.getProtectionDomain().getCodeSource()
                    .getLocation().toURI()
            );
            Path directory = Files.isDirectory(location) ? location : location.getParent();
            return directory == null ? null : directory.resolve("40x1d0AC-scan.exe");
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static void scan(Path helper, long pid, Consumer<Finding> sink) {
        if (helper == null || !Files.isRegularFile(helper)) {
            return;
        }
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(
                helper.toString(), Long.toString(pid)
            );
            builder.redirectErrorStream(true);
            process = builder.start();
            if (!process.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return;
            }
            try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    Finding finding = parse(line);
                    if (finding != null && sink != null) {
                        sink.accept(finding);
                    }
                }
            }
        } catch (Throwable ignored) {
            if (process != null) {
                process.destroyForcibly();
            }
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    private static Finding parse(String line) {
        String[] parts = line.split("\t", -1);
        if (parts.length >= 3 && "MEMORY".equals(parts[0])) {
            String base = parts[1];
            String size = parts[2];
            long bytes = parseLong(size);
            if (bytes < 0x1000L) {
                return null;
            }
            return new Finding(
                "inj:memory:" + base,
                "memory_region",
                "Manuel mapped bellek",
                "pid-memory:" + base,
                null,
                "yok",
                "Surec belleğinde dosyaya bagli olmayan PE alani bulundu.",
                "high",
                "base=" + base + " size=" + size,
                true,
                false
            );
        }
        if (parts.length < 6 || !"MODULE".equals(parts[0])) {
            return null;
        }

        String path = parts[1];
        String base = parts[2];
        String size = parts[3];
        boolean signed = "1".equals(parts[4]);
        String sha256 = parts.length > 5 ? parts[5] : "";
        String lower = path.toLowerCase(Locale.ROOT);
        if (isTrusted(lower) || signed) {
            return null;
        }
        boolean suspicious = lower.contains("\\temp\\")
            || lower.contains("\\downloads\\")
            || lower.contains("\\desktop\\")
            || lower.contains("\\appdata\\local\\temp\\")
            || !isTrusted(lower);
        if (!suspicious) {
            return null;
        }
        String name = path;
        int slash = path.lastIndexOf('\\');
        if (slash >= 0) {
            name = path.substring(slash + 1);
        }
        return new Finding(
            "inj:module:" + (sha256.isBlank() ? lower : sha256),
            "native_module",
            name,
            path,
            sha256.isBlank() ? null : sha256,
            "imzasiz",
            "Minecraft surecine yuklenmis imzasiz yerel modul.",
            "high",
            "base=" + base + " size=" + size,
            true,
            false
        );
    }

    private static boolean isTrusted(String path) {
        return path.contains("\\windows\\")
            || path.contains("\\program files")
            || path.contains("\\runtime\\")
            || path.contains("\\40x1d0ac\\")
            || path.contains("\\.minecraft\\")
            || path.contains("\\jdk")
            || path.contains("\\java");
    }

    private static long parseLong(String value) {
        try {
            return Long.decode(value);
        } catch (Throwable ignored) {
            return -1L;
        }
    }
}