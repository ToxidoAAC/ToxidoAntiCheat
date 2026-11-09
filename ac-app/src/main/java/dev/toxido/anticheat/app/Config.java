package dev.toxido.anticheat.app;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.UUID;

public final class Config {
    public final String apiBaseUrl;
    public final String apiKey;
    public final String apiSecret;
    public final int heartbeatIntervalMs;
    public final int agentTimeoutMs;
    public final int attachTimeoutMs;
    public final String agentVersion;

    private final String agentJar;
    private final String deviceId;

    private Config(Properties properties, String deviceId) {
        this.apiBaseUrl = trimTrailingSlash(
            properties.getProperty("api.baseUrl", "http://127.0.0.1:8000"));
        this.apiKey = value(properties, "api.key", Secrets.apiKey());
        this.apiSecret = value(properties, "api.secret", Secrets.apiSecret());
        this.heartbeatIntervalMs = intValue(properties, "heartbeat.intervalMs", 5000);
        this.agentTimeoutMs = intValue(properties, "agent.timeoutMs", 20000);
        this.attachTimeoutMs = intValue(properties, "attach.timeoutMs", 15000);
        this.agentVersion = properties.getProperty("agent.version", "0.1.0");
        this.agentJar = properties.getProperty("agent.jar", "").trim();
        this.deviceId = deviceId;
    }

    public static Config load() throws IOException {
        Properties properties = new Properties();

        Path external = null;
        String configured = System.getProperty("ac.config");
        if (configured != null && !configured.isBlank()) {
            external = Path.of(configured);
        } else {
            Path local = Path.of("config.properties");
            if (Files.exists(local)) {
                external = local;
            }
        }

        if (external != null && Files.exists(external)) {
            try (InputStream input = Files.newInputStream(external)) {
                properties.load(input);
            }
        } else {
            try (InputStream input = Config.class.getResourceAsStream("/config.properties")) {
                if (input != null) {
                    properties.load(input);
                }
            }
        }

        override(properties, "api.baseUrl", "AC_API_URL");
        override(properties, "api.key", "AC_API_KEY");
        override(properties, "api.secret", "AC_API_SECRET");
        override(properties, "agent.jar", "AC_AGENT_JAR");

        return new Config(properties, loadDeviceId());
    }

    public Path resolveAgentJar() {
        if (!agentJar.isBlank()) {
            Path path = Path.of(agentJar).toAbsolutePath().normalize();
            if (Files.exists(path)) {
                return path;
            }
        }

        String systemProperty = System.getProperty("ac.agent.jar");
        if (systemProperty != null && !systemProperty.isBlank()) {
            Path path = Path.of(systemProperty).toAbsolutePath().normalize();
            if (Files.exists(path)) {
                return path;
            }
        }

        String[] candidates = {
            "ac-agent/target/ac-agent.jar",
            "../ac-agent/target/ac-agent.jar",
            "ac-agent.jar"
        };
        for (String candidate : candidates) {
            Path path = Path.of(candidate).toAbsolutePath().normalize();
            if (Files.exists(path)) {
                return path;
            }
        }

        try {
            Path appDirectory = Path.of(
                Config.class.getProtectionDomain().getCodeSource().getLocation().toURI()
            ).getParent();
            Path nextToJar = appDirectory.resolve("ac-agent.jar");
            if (Files.exists(nextToJar)) {
                return nextToJar;
            }
            Path inRepository = appDirectory.resolve("../ac-agent/target/ac-agent.jar").normalize();
            if (Files.exists(inRepository)) {
                return inRepository;
            }
        } catch (Exception ignored) {
            // Asagidaki hata daha aciklayici.
        }

        throw new IllegalStateException(
            "ac-agent.jar bulunamadi. -Dac.agent.jar=... ile yol belirtin.");
    }

    public String deviceId() {
        return deviceId;
    }

    private static void override(Properties properties, String key, String environmentName) {
        String value = System.getenv(environmentName);
        if (value != null && !value.isBlank()) {
            properties.setProperty(key, value);
        }
    }

    private static String value(Properties properties, String key, String fallback) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }

    private static String trimTrailingSlash(String value) {
        String trimmed = value.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static int intValue(Properties properties, String key, int fallback) {
        try {
            return Integer.parseInt(properties.getProperty(key, Integer.toString(fallback)).trim());
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private static String loadDeviceId() {
        try {
            String appData = System.getenv().getOrDefault(
                "APPDATA", System.getProperty("user.home"));
            Path directory = Path.of(appData, "40x1d0AC");
            Files.createDirectories(directory);
            Path file = directory.resolve("device.id");
            if (Files.exists(file)) {
                String existing = Files.readString(file).trim();
                if (!existing.isBlank()) {
                    return existing;
                }
            }
            String id = UUID.randomUUID().toString();
            Files.writeString(file, id);
            return id;
        } catch (Exception exception) {
            return "unknown-" + UUID.randomUUID();
        }
    }
}
