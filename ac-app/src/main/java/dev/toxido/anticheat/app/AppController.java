package dev.toxido.anticheat.app;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Faz 1 akisi:
 * Minecraft bekle -> ilk oturuma attach ol -> ajan el sikismasi -> API oturumu ->
 * kalp atisi -> oyun kapaninca bitir. Tek seferlik; yeniden baglanmaz.
 */
public final class AppController implements Runnable, AgentBridge.Listener {
    private final Config config;
    private final Reporter reporter;
    private final ApiClient api;

    private final AtomicBoolean stopped = new AtomicBoolean(false);
    private final AtomicBoolean ending = new AtomicBoolean(false);
    private final AtomicLong lastAgentHeartbeat = new AtomicLong(0);
    private final AtomicReference<String> endReason = new AtomicReference<>("unknown");
    private final CountDownLatch agentReady = new CountDownLatch(1);
    private final CountDownLatch sessionEnded = new CountDownLatch(1);

    private volatile AgentBridge bridge;
    private volatile String sessionId;
    private volatile String sessionToken;
    private volatile long minecraftPid;
    private volatile ScheduledExecutorService scheduler;
    private volatile ScheduledExecutorService scannerScheduler;
    private volatile ExecutorService stageExecutor;
    private final Set<String> reportedFindings = ConcurrentHashMap.newKeySet();
    private final Set<String> reportedStages = ConcurrentHashMap.newKeySet();
    private final List<JsonObject> capabilityHistory =
        Collections.synchronizedList(new ArrayList<>());
    private final List<AgentEvent> pendingAgentEvents =
        Collections.synchronizedList(new ArrayList<>());
    private final AtomicBoolean sessionReady = new AtomicBoolean(false);
    private final AtomicLong agentAttachedAt = new AtomicLong(0);
    private volatile String stageRunId;

    public AppController(Config config, Reporter reporter) {
        this.config = config;
        this.reporter = reporter;
        this.api = new ApiClient(config);
    }

    @Override
    public void run() {
        reporter.status("Minecraft bekleniyor...", "Minecraft acilinca otomatik baglanacak.");
        reporter.log("Toxido AC " + config.agentVersion + " basladi.");

        MinecraftProcess minecraft = null;
        while (!stopped.get() && minecraft == null) {
            minecraft = MinecraftProcess.find().orElse(null);
            if (minecraft == null) {
                sleep(1000);
            }
        }

        if (minecraft == null) {
            return;
        }

        minecraftPid = minecraft.pid();
        reporter.status(
            "Minecraft bulundu",
            minecraft.username() + " • " + safe(minecraft.version())
        );
        reporter.log("Minecraft PID " + minecraft.pid() + " (" + minecraft.username() + ")");

        try {
            connect(minecraft);
        } catch (Exception exception) {
            reporter.log("HATA: " + exception.getMessage());
            if ("unknown".equals(endReason.get())) {
                endReason.set("attach_failed");
            }
        } finally {
            cleanup();
        }

        reporter.status(
            "Oturum kapandi",
            "Sebep: " + endReason.get() + ". Yeni oturum icin uygulamayi yeniden acin."
        );
        reporter.log("Oturum kapandi: " + endReason.get());
    }

    private void connect(MinecraftProcess minecraft) throws Exception {
        Path agentJar = config.resolveAgentJar();
        reporter.log("Ajan: " + agentJar);

        bridge = new AgentBridge();
        bridge.start(this);

        reporter.status("Baglaniyor...", "Ajan yukleniyor (PID " + minecraft.pid() + ")");
        String profile = minecraft.version() == null || minecraft.version().isBlank()
            ? "unknown"
            : minecraft.version();
        String agentArgs = "port=" + bridge.port() + ";token=" + bridge.token()
            + ";version=" + safeArg(profile)
            + ";game_dir=" + safeArg(minecraft.gameDir())
            + ";profile=" + safeArg(profile);
        AttachService.loadAgent(minecraft.pid(), agentJar, agentArgs);

        if (!agentReady.await(config.attachTimeoutMs, TimeUnit.MILLISECONDS)) {
            throw new IllegalStateException(
                "Ajan " + config.attachTimeoutMs + " ms icinde baglanmadi");
        }
        reporter.log("Ajan baglandi.");

        ApiClient.StartResult start = api.startSession(minecraft, Instant.now().toString());
        this.sessionId = start.sessionId();
        this.sessionToken = start.token();
        this.stageRunId = Long.toString(
            agentAttachedAt.get() > 0 ? agentAttachedAt.get() : System.currentTimeMillis()
        );
        if (reporter instanceof LogReporter logReporter) {
            logReporter.setSink(this::sendRemoteLog);
        }

        startStageExecutor();
        emitStage(
            "agent_attached", 1, 4, "Ajan baglandi", "active",
            agentAttachedAt.get() > 0 ? agentAttachedAt.get() : System.currentTimeMillis(),
            latestCapability()
        );
        flushCapabilityHistory();
        flushPendingAgentEvents();

        reporter.status(
            "Oturum acik",
            minecraft.username() + " • " + safe(minecraft.version())
                + " • Discord kanali: " + (start.channelId() == null ? "-" : start.channelId())
        );
        reporter.log("Oturum: " + start.sessionId());
        reporter.log("Discord kanal id: " + start.channelId());

        lastAgentHeartbeat.set(System.currentTimeMillis());
        startScheduler();
        startModuleScanner(minecraft.pid());
        watchMinecraftProcess(minecraft);

        sessionEnded.await();
    }

    private void startScheduler() {
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "toxido-ac-heartbeat");
            thread.setDaemon(true);
            return thread;
        });

        int interval = config.heartbeatIntervalMs;
        scheduler.scheduleAtFixedRate(() -> {
            try {
                if (sessionId != null && sessionToken != null) {
                    api.heartbeat(sessionId, sessionToken);
                }
            } catch (ApiClient.ApiException exception) {
                if (exception.status() == 409 && ending.get()) {
                    return;
                }
                reporter.log("Heartbeat hatasi: " + exception.getMessage());
            } catch (Exception exception) {
                reporter.log("Heartbeat hatasi: " + exception.getMessage());
            }
        }, interval, interval, TimeUnit.MILLISECONDS);

        scheduler.scheduleAtFixedRate(() -> {
            long last = lastAgentHeartbeat.get();
            if (last > 0 && System.currentTimeMillis() - last > config.agentTimeoutMs) {
                triggerEnd("agent_silent");
            }
        }, 5, 5, TimeUnit.SECONDS);
    }

    private void startStageExecutor() {
        stageExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "toxido-ac-stage");
            thread.setDaemon(true);
            return thread;
        });
    }

    private void startModuleScanner(long pid) {
        Path helper = ModuleScanner.helperPath();
        if (helper == null) {
            reporter.log("Native tarayici bulunamadi; modul taramasi kapali.");
            return;
        }

        scannerScheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "toxido-ac-module-scan");
            thread.setDaemon(true);
            return thread;
        });
        scannerScheduler.scheduleAtFixedRate(() -> {
            ModuleScanner.scan(helper, pid, finding -> {
                if (!reportedFindings.add(finding.key)) {
                    return;
                }
                sendFinding(finding);
            });
        }, 2, 30, TimeUnit.SECONDS);
    }

    private void sendFinding(ModuleScanner.Finding finding) {
        String currentSession = sessionId;
        String currentToken = sessionToken;
        if (currentSession == null || currentToken == null) {
            return;
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("kind", finding.kind);
        payload.addProperty("name", finding.name);
        payload.addProperty("path", finding.path);
        payload.addProperty("sha256", finding.sha256);
        payload.addProperty("signature", finding.signature);
        payload.addProperty("description", finding.description);
        payload.addProperty("confidence", finding.confidence);
        payload.addProperty("evidence", finding.evidence);
        payload.addProperty("exists", finding.exists);
        payload.addProperty("deleted", finding.deleted);
        try {
            api.sendEvent(currentSession, currentToken, "injection", finding.key, payload);
            reporter.log("Enjeksiyon bulgusu gonderildi: " + finding.name);
        } catch (Exception exception) {
            reporter.log("Enjeksiyon bulgusu gonderilemedi: " + exception.getMessage());
        }
    }

    @Override
    public void onCapability(JsonObject payload) {
        if (payload == null) {
            return;
        }

        JsonObject capability = payload.deepCopy();
        if (!capability.has("client_ts")) {
            capability.addProperty("client_ts", System.currentTimeMillis());
        }
        reporter.log("Ajan yetenekleri: " + capability);

        synchronized (capabilityHistory) {
            if (!sessionReady.get()) {
                capabilityHistory.add(capability);
                while (capabilityHistory.size() > 20) {
                    capabilityHistory.remove(0);
                }
                return;
            }
            emitCapabilityStages(capability);
        }
    }

    private void flushCapabilityHistory() {
        synchronized (capabilityHistory) {
            List<JsonObject> snapshot = new ArrayList<>(capabilityHistory);
            capabilityHistory.clear();
            for (JsonObject capability : snapshot) {
                emitCapabilityStages(capability);
            }
            // Birikmis kayitlar gonderildikten sonra dogrudan akisa gec.
            sessionReady.set(true);
        }
    }

    private JsonObject latestCapability() {
        synchronized (capabilityHistory) {
            if (capabilityHistory.isEmpty()) {
                return null;
            }
            return capabilityHistory.get(capabilityHistory.size() - 1).deepCopy();
        }
    }

    private void emitCapabilityStages(JsonObject capability) {
        long timestamp = clientTimestamp(capability);
        String reason = text(capability, "reason");
        String triggerbot = text(capability, "triggerbot");
        String hook = text(capability, "hook");
        String packet = text(capability, "packet");
        String packetReason = text(capability, "packet_reason");
        String input = text(capability, "input");
        boolean hookInstalled = !hook.isBlank() && !"-".equals(hook);
        boolean packetActive = "active".equals(packet);
        boolean packetFailed = "off".equals(packet) || "packet_hook_failed".equals(packetReason);
        boolean unsupportedVersion = packetReason.startsWith("unsupported_version");

        if ("hook_failed".equals(reason) && !packetActive) {
            emitStage("hook_failed", 3, 4, "Attack hook kurulamadi", "failed",
                timestamp, capability);
            return;
        }

        if (packetFailed) {
            if (unsupportedVersion) {
                emitStage("unsupported_version", 3, 4,
                    "Desteklenmeyen surum", "failed", timestamp, capability);
                emitStage("unsupported_trigger", 4, 4,
                    "TriggerBot devre disi", "failed", timestamp, capability);
            } else {
                emitStage("packet_failed", 3, 4,
                    "Packet hook kurulamadi", "failed", timestamp, capability);
                emitStage("packet_failed_trigger", 4, 4,
                    "TriggerBot devre disi", "failed", timestamp, capability);
            }
            return;
        }

        if ("waiting".equals(packet) || (!packetActive && !hookInstalled
            && ("waiting_for_attack_class".equals(reason)
                || "off".equals(triggerbot)))) {
            emitStage("packet_waiting", 2, 4, "Packet hook kurulumu", "waiting",
                timestamp, capability);
            return;
        }

        if (packetActive) {
            emitStage("packet_installed", 3, 4, "Packet hook kuruldu", "active",
                timestamp, capability);
        } else if (hookInstalled) {
            emitStage("hook_installed", 3, 4, "Attack hook kuruldu", "active",
                timestamp, capability);
        }

        if ("input_helper_missing".equals(reason)) {
            emitStage("input_missing", 4, 4, "Fare yardimcisi baslatilamadi", "failed",
                timestamp, capability);
            return;
        }

        if ("active".equals(triggerbot) && "ready".equals(input) && packetActive) {
            emitStage("triggerbot_ready", 4, 4, "TriggerBot hazir", "active",
                timestamp, capability);
        } else if ("degraded".equals(triggerbot) && "missing".equals(input)) {
            emitStage("degraded", 4, 4, "TriggerBot degraded", "failed",
                timestamp, capability);
        }
    }

    private void emitStage(
        String stage,
        int index,
        int total,
        String title,
        String status,
        long timestamp,
        JsonObject capability
    ) {
        if (!reportedStages.add(stage)) {
            return;
        }

        String currentSession = sessionId;
        String currentToken = sessionToken;
        ExecutorService executor = stageExecutor;
        if (currentSession == null || currentToken == null || executor == null) {
            reportedStages.remove(stage);
            return;
        }

        JsonObject payload = new JsonObject();
        payload.addProperty("stage", stage);
        payload.addProperty("title", title);
        payload.addProperty("status", status);
        payload.addProperty("stage_index", index);
        payload.addProperty("stage_total", total);
        payload.addProperty("client_ts", timestamp);
        payload.addProperty("run_id", stageRunId == null ? "na" : stageRunId);
        if (capability != null) {
            copyCapability(capability, payload, "triggerbot");
            copyCapability(capability, payload, "injection");
            copyCapability(capability, payload, "version");
            copyCapability(capability, payload, "version_source");
            copyCapability(capability, payload, "loader");
            copyCapability(capability, payload, "mapping");
            copyCapability(capability, payload, "profile");
            copyCapability(capability, payload, "jvm_main");
            copyCapability(capability, payload, "pid");
            copyCapability(capability, payload, "hook");
            copyCapability(capability, payload, "packet");
            copyCapability(capability, payload, "packet_hook");
            copyCapability(capability, payload, "packet_reason");
            copyCapability(capability, payload, "input");
            copyCapability(capability, payload, "input_reason");
            copyCapability(capability, payload, "reason");
        }

        try {
            executor.execute(() -> {
                try {
                    String runId = stageRunId == null ? "na" : stageRunId;
                    api.sendEvent(
                        currentSession, currentToken, "stage",
                        "stage:" + runId + ":" + stage, payload
                    );
                    reporter.log(
                        "Asama gonderildi: " + index + "/" + total + " " + stage
                    );
                } catch (Exception exception) {
                    reportedStages.remove(stage);
                    reporter.log(
                        "Asama gonderilemedi (" + stage + "): "
                            + exception.getMessage()
                    );
                }
            });
        } catch (RejectedExecutionException exception) {
            reportedStages.remove(stage);
            reporter.log("Asama kuyrugu kapali: " + stage);
        }
    }

    private static String safeArg(String value) {
        if (value == null) {
            return "";
        }
        return value.replace(";", "")
            .replace("\r", "")
            .replace("\n", "");
    }

    private static void copyCapability(
        JsonObject source, JsonObject target, String key
    ) {
        JsonElement value = source.get(key);
        if (value != null && !value.isJsonNull()) {
            target.add(key, value.deepCopy());
        }
    }

    private static long clientTimestamp(JsonObject payload) {
        JsonElement value = payload.get("client_ts");
        if (value != null && !value.isJsonNull()) {
            try {
                return value.getAsLong();
            } catch (RuntimeException ignored) {
                // Gecersiz zaman damgasi yerine simdiki zamani kullan.
            }
        }
        return System.currentTimeMillis();
    }

    private static String text(JsonObject payload, String key) {
        JsonElement value = payload.get(key);
        return value == null || value.isJsonNull() ? "" : value.getAsString();
    }

    @Override
    public void onEvent(String event, String key, JsonObject payload) {
        String currentSession = sessionId;
        String currentToken = sessionToken;
        if (currentSession == null || currentToken == null) {
            synchronized (pendingAgentEvents) {
                if (pendingAgentEvents.size() < 200) {
                    pendingAgentEvents.add(new AgentEvent(event, key, payload));
                }
            }
            return;
        }
        sendAgentEvent(event, key, payload);
    }

    private void flushPendingAgentEvents() {
        List<AgentEvent> pending;
        synchronized (pendingAgentEvents) {
            pending = new ArrayList<>(pendingAgentEvents);
            pendingAgentEvents.clear();
        }
        for (AgentEvent event : pending) {
            sendAgentEvent(event.event(), event.key(), event.payload());
        }
    }

    private void sendAgentEvent(String event, String key, JsonObject payload) {
        String currentSession = sessionId;
        String currentToken = sessionToken;
        if (currentSession == null || currentToken == null) {
            return;
        }
        try {
            api.sendEvent(currentSession, currentToken, event, key, payload);
            if (!"log".equals(event)) {
                reporter.log("Olay gonderildi: " + event);
            }
        } catch (Exception exception) {
            reporter.log("Olay gonderilemedi (" + event + "): " + exception.getMessage());
        }
    }

    private void sendRemoteLog(String line) {
        String currentSession = sessionId;
        String currentToken = sessionToken;
        if (currentSession == null || currentToken == null) {
            return;
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("message", line);
        payload.addProperty("source", "app");
        try {
            api.sendEvent(currentSession, currentToken, "log", "app", payload);
        } catch (Exception ignored) {
            // Uzak log gonderimi servis akisini durdurmaz.
        }
    }

    private record AgentEvent(String event, String key, JsonObject payload) {
    }

    private void watchMinecraftProcess(MinecraftProcess minecraft) {
        ProcessHandle.of(minecraft.pid()).ifPresentOrElse(
            handle -> handle.onExit().thenRun(() -> triggerEnd("game_closed")),
            () -> triggerEnd("game_closed")
        );
    }

    public void triggerEnd(String reason) {
        if (!ending.compareAndSet(false, true)) {
            return;
        }

        stopped.set(true);
        endReason.set(reason);
        reporter.log("Oturum bitiriliyor: " + reason);

        ScheduledExecutorService currentScheduler = scheduler;
        if (currentScheduler != null) {
            currentScheduler.shutdownNow();
        }

        if (sessionId != null && sessionToken != null) {
            try {
                api.endSession(sessionId, sessionToken, reason);
            } catch (Exception exception) {
                reporter.log("End istegi hatasi: " + exception.getMessage());
            }
        }

        cleanup();
        sessionEnded.countDown();
    }

    @Override
    public void onAgentReady() {
        long now = System.currentTimeMillis();
        agentAttachedAt.set(now);
        lastAgentHeartbeat.set(now);
        reporter.log("Ajan el sikismasi tamam.");
        agentReady.countDown();
    }

    @Override
    public void onHeartbeat() {
        lastAgentHeartbeat.set(System.currentTimeMillis());
    }

    @Override
    public void onDisconnected(String reason) {
        if (sessionId == null) {
            return;
        }

        String effectiveReason = reason;
        if (("agent_disconnected".equals(reason) || "agent_error".equals(reason))
            && minecraftPid > 0) {
            // Oyun kapanirken ajan soketi once kopabilir; kisa bir kontrolle
            // bunu normal oyun kapanisi olarak siniflandir.
            sleep(500);
            if (!ProcessHandle.of(minecraftPid)
                .map(ProcessHandle::isAlive)
                .orElse(false)) {
                effectiveReason = "game_closed";
            }
        }

        triggerEnd(effectiveReason);
    }

    private void cleanup() {
        AgentBridge currentBridge = bridge;
        if (currentBridge != null) {
            currentBridge.close();
        }

        ScheduledExecutorService currentScheduler = scheduler;
        if (currentScheduler != null) {
            currentScheduler.shutdownNow();
        }
        ScheduledExecutorService currentScanner = scannerScheduler;
        if (currentScanner != null) {
            currentScanner.shutdownNow();
        }
        ExecutorService currentStage = stageExecutor;
        if (currentStage != null) {
            currentStage.shutdown();
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "?" : value;
    }
}
