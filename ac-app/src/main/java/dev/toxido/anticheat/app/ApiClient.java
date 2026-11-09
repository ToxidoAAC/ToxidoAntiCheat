package dev.toxido.anticheat.app;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import java.util.UUID;

/**
 * VPS API istemcisi. Her istegi HMAC-SHA256 ile imzalar.
 */
public final class ApiClient {
    public static final class ApiException extends Exception {
        private final int status;

        public ApiException(int status, String body) {
            super("API " + status + ": " + body);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }

    public record StartResult(
        String sessionId,
        String token,
        int heartbeatIntervalMs,
        Long channelId
    ) {
    }

    private final Config config;
    private final HttpClient http;
    private final Gson gson = new Gson();

    public ApiClient(Config config) {
        this.config = config;
        this.http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    }

    public StartResult startSession(MinecraftProcess minecraft, String startedAt) throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("mc_name", minecraft.username());
        body.addProperty("mc_uuid", minecraft.uuid());
        body.addProperty("mc_version", minecraft.version());
        body.addProperty("agent_version", config.agentVersion);
        body.addProperty("device_id", config.deviceId());
        body.addProperty("started_at", startedAt);

        JsonObject response = post("/api/v1/session/start", gson.toJson(body), null, false);
        Long channelId = null;
        if (response.has("channel_id") && !response.get("channel_id").isJsonNull()) {
            channelId = response.get("channel_id").getAsLong();
        }
        int interval = response.has("heartbeat_interval_ms")
            ? response.get("heartbeat_interval_ms").getAsInt()
            : 5000;

        return new StartResult(
            response.get("session_id").getAsString(),
            response.get("token").getAsString(),
            interval,
            channelId
        );
    }

    public void heartbeat(String sessionId, String token) throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("session_id", sessionId);
        post("/api/v1/session/heartbeat", gson.toJson(body), token, true);
    }

    public void endSession(String sessionId, String token, String reason) throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("session_id", sessionId);
        body.addProperty("reason", reason);
        post("/api/v1/session/end", gson.toJson(body), token, true);
    }

    public void sendEvent(
        String sessionId,
        String token,
        String event,
        String eventKey,
        JsonObject payload
    ) throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("session_id", sessionId);
        body.addProperty("type", event);
        if (eventKey != null && !eventKey.isBlank()) {
            body.addProperty("event_key", eventKey);
        }
        body.add("payload", payload == null ? new JsonObject() : payload);
        post("/api/v1/session/event", gson.toJson(body), token, true);
    }

    private JsonObject post(
        String path,
        String body,
        String bearer,
        boolean retryOnIoError
    ) throws Exception {
        int attempts = retryOnIoError ? 2 : 1;
        IOException lastIoError = null;

        for (int attempt = 1; attempt <= attempts; attempt++) {
            String timestamp = Long.toString(System.currentTimeMillis());
            String nonce = UUID.randomUUID().toString();
            String signature = sign(timestamp + "." + nonce + "." + body);

            HttpRequest.Builder builder = HttpRequest.newBuilder(
                    URI.create(config.apiBaseUrl + path))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .header("X-AC-Key", config.apiKey)
                .header("X-AC-Timestamp", timestamp)
                .header("X-AC-Nonce", nonce)
                .header("X-AC-Signature", signature)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));

            if (bearer != null) {
                builder.header("Authorization", "Bearer " + bearer);
            }

            try {
                HttpResponse<String> response = http.send(
                    builder.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
                );

                if (response.statusCode() / 100 != 2) {
                    throw new ApiException(response.statusCode(), response.body());
                }

                return JsonParser.parseString(response.body()).getAsJsonObject();
            } catch (IOException exception) {
                lastIoError = exception;
                if (attempt < attempts) {
                    try {
                        Thread.sleep(250);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw exception;
                    }
                }
            }
        }

        throw lastIoError;
    }

    private String sign(String payload) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(
            config.apiSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(
            mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    }
}
