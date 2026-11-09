package dev.toxido.anticheat.app;

import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
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
        String agentArgs = "port=" + bridge.port() + ";token=" + bridge.token();
        AttachService.loadAgent(minecraft.pid(), agentJar, agentArgs);

        if (!agentReady.await(config.attachTimeoutMs, TimeUnit.MILLISECONDS)) {
            throw new IllegalStateException(
                "Ajan " + config.attachTimeoutMs + " ms icinde baglanmadi");
        }
        reporter.log("Ajan baglandi.");

        ApiClient.StartResult start = api.startSession(minecraft, Instant.now().toString());
        this.sessionId = start.sessionId();
        this.sessionToken = start.token();

        reporter.status(
            "Oturum acik",
            minecraft.username() + " • " + safe(minecraft.version())
                + " • Discord kanali: " + (start.channelId() == null ? "-" : start.channelId())
        );
        reporter.log("Oturum: " + start.sessionId());
        reporter.log("Discord kanal id: " + start.channelId());

        lastAgentHeartbeat.set(System.currentTimeMillis());
        startScheduler();
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
        lastAgentHeartbeat.set(System.currentTimeMillis());
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
