package dev.toxido.anticheat.agent;

import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;

/** Faz 2: paket/attack hook, fare korelasyonu ve enjeksiyon taramasi. */
public final class Phase2 {
    private static volatile VersionResolver.Target target;
    private static volatile InputCollector input;
    private static volatile AttackCorrelator correlator;
    private static volatile InjectionScanner injection;
    private static volatile String hookStatus = "off";
    private static volatile String reason = "not_started";
    private static volatile String packetStatus = "waiting";
    private static volatile String packetReason = "netty_starting";
    private static volatile String installReason = "not_started";
    private static volatile String lastCapability;
    private static volatile String packetHook = "-";
    private static volatile AgentClient clientRef;
    private static volatile String detectedVersion = "unknown";
    private static volatile String detectedLoader = "unknown";
    private static volatile String versionSource = "unknown";
    private static volatile String mapping = "unknown";
    private static volatile String profileId = "-";
    private static volatile String inputReason = "not_started";
    private static volatile String jvmMain = "unknown";
    private static volatile long jvmPid = -1L;
    private static volatile int installAttempts;
    private static volatile long nettyTickAt;
    private static volatile String nettyDetail = "not_started";
    /** nettyNote kaynakli capability yayinini 5 sn ile sinirlar. */
    private static final java.util.concurrent.atomic.AtomicLong lastNoteEmit =
        new java.util.concurrent.atomic.AtomicLong();
    private static volatile String instanceNote = "not_started";
    private static volatile boolean inputWatchStarted;
    private static volatile int inputFailures;
    private static volatile Instrumentation agentInstrumentation;
    private static volatile String agentVersionHint = "unknown";
    private static volatile String agentGameDir = "";
    private static volatile String agentProfileName = "";
    private static final java.util.concurrent.atomic.AtomicBoolean installRunning =
        new java.util.concurrent.atomic.AtomicBoolean(false);

    private Phase2() {
    }

    public static void start(
        Instrumentation instrumentation,
        AgentClient client,
        String versionHint
    ) {
        start(instrumentation, client, versionHint, "", "");
    }

    public static void start(
        Instrumentation instrumentation,
        AgentClient client,
        String versionHint,
        String gameDir,
        String profileName
    ) {
        clientRef = client;
        jvmPid = ProcessHandle.current().pid();
        jvmMain = mainClass();
        injection = new InjectionScanner(instrumentation, client);
        injection.start();
        agentInstrumentation = instrumentation;
        agentVersionHint = versionHint;
        agentGameDir = gameDir;
        agentProfileName = profileName;

        try {
            NettyHook.start(instrumentation, client, versionHint, gameDir, profileName);
        } catch (Throwable throwable) {
            packetStatus = "waiting";
            packetReason = "netty_hook_unavailable";
            packetHook = "-";
            AgentMain.log("Netty hook baslatilamadi: " + throwable);
        }

        startInstaller();

        Thread refresher = new Thread(
            () -> refreshLoop(client),
            "40x1d0AC-capability-refresh"
        );
        refresher.setDaemon(true);
        refresher.start();
    }

    private static void startInstaller() {
        Instrumentation instrumentation = agentInstrumentation;
        AgentClient client = clientRef;
        if (instrumentation == null || client == null) {
            AgentMain.log("Kurulum baslatilamadi: instrumentation veya istemci yok.");
            return;
        }
        if (!installRunning.compareAndSet(false, true)) {
            AgentMain.log("Kurulum dongusu zaten calisiyor.");
            return;
        }
        Thread installer = new Thread(
            () -> installWhenReady(
                instrumentation, client, agentVersionHint, agentGameDir, agentProfileName
            ),
            "40x1d0AC-hook-install"
        );
        installer.setDaemon(true);
        installer.start();
        AgentMain.log("Kurulum dongusu basladi.");
    }

    /**
     * Discord panelinden gelen /restart isteginde tespit zincirini bastan
     * kurar. Eski profil, paket durumu ve surum onbellegi temizlenir.
     */
    static void restartPipeline() {
        AgentMain.log("Yeniden baslatma istendi; tespit bastan kuruluyor.");
        target = null;
        hookStatus = "-";
        packetStatus = "waiting";
        packetReason = "restart_requested";
        packetHook = "-";
        installReason = "restart_requested";
        installAttempts = 0;
        reason = "restart_requested";
        nettyDetail = "restart";
        nettyTickAt = System.currentTimeMillis();
        GameVersionDetector.clearCache();
        NettyHook.restart();
        emitCapability();
        startInstaller();
    }

    /**
     * Durum degistiginde capability gonderir; degismese bile 30 saniyede bir
     * tekrar gonderir. Boylece ilk gonderim kaybolsa bile pano guncellenir.
     */
    private static void refreshLoop(AgentClient client) {
        long lastSentAt = 0L;
        while (true) {
            sleep(5000L);
            try {
                String current = capability();
                long now = System.currentTimeMillis();
                boolean changed = !current.equals(lastCapability);
                if (changed || now - lastSentAt >= 30_000L) {
                    lastCapability = current;
                    lastSentAt = now;
                    client.sendCapability(current);
                }
            } catch (Throwable throwable) {
                AgentMain.log("Capability yenileme hatasi: " + throwable);
            }
        }
    }

    private static final java.util.concurrent.atomic.AtomicInteger emitCount =
        new java.util.concurrent.atomic.AtomicInteger();

    private static void emitCapability() {
        AgentClient client = clientRef;
        if (client == null) {
            return;
        }
        String current = capability();
        lastCapability = current;
        emitCount.incrementAndGet();
        client.sendCapability(current);
    }

    private static void installWhenReady(
        Instrumentation instrumentation,
        AgentClient client,
        String versionHint,
        String gameDir,
        String profileName
    ) {
        // Minecraft siniflari oyun acilirken gec yuklenir (genelde dunyaya
        // girince). Tek seferlik cozumleme yerine hazir olana kadar bekle.
        reason = "waiting_for_attack_class";
        installReason = "waiting_for_attack_class";
        int attempts = 0;
        while (true) {
            try {
                // Oyun daha menuye gelmediyse sinif listesini taramak
                // Fabric'in classloader kilidiyle cakisabilir. Log'daki
                // hazirlik izlerini bekleyip sonra cozumlemeye basla.
                if (!GameReadiness.isReady(gameDir)) {
                    attempts++;
                    installAttempts = attempts;
                    installReason = "waiting_for_game";
                    reason = "waiting_for_attack_class";
                    if (attempts == 1 || attempts % 20 == 0) {
                        AgentMain.log(
                            "Oyun hazir olmasi bekleniyor (deneme " + attempts + ")."
                        );
                    }
                    sleep(attempts < 30 ? 1000L : 5000L);
                    continue;
                }
                VersionResolver.Target resolved = target;
                if (resolved == null) {
                    resolved = resolveWithDeadline(
                        instrumentation, versionHint, gameDir, profileName
                    );
                    if (resolved != null) {
                        target = resolved;
                    }
                }

                if (resolved != null) {
                    // Paket kaynagi Netty pipeline'dir; Minecraft bytecode'una
                    // hook enjekte edilmez. Attack hook yalnizca surum/loader
                    // bilgisini ve yedek sinyal yolunu hazirlar.
                    hookStatus = "off";
                    reason = "netty_waiting";
                    installReason = "attack_hook_ready";
                    startCollector(client, resolved);
                    startInputWatch();
                    installRunning.set(false);
                    return;
                }

                attempts++;
                installAttempts = attempts;
                if (attempts == 30) {
                    AgentMain.log("Attack sinifi henuz yuklenmedi; beklemeye devam ediliyor.");
                }
            } catch (Throwable throwable) {
                // Oyun acilirken siniflar yarim yuklu olabilir. Tek bir hata
                // kurulum thread'ini oldurmemeli; artan bekleme ile yeniden
                // denenir, aksi halde triggerbot sonsuza kadar kapali kalir.
                attempts++;
                installAttempts = attempts;
                installReason = "install_retry";
                AgentMain.log(
                    "Kurulum denemesi " + attempts + " basarisiz: " + throwable
                );
                emitCapability();
            }
            sleep(attempts < 30 ? 1000L : 5000L);
        }
    }

    private static final java.util.concurrent.atomic.AtomicInteger RESOLVE_IN_FLIGHT =
        new java.util.concurrent.atomic.AtomicInteger();

    /**
     * resolve() oyun acilirken classloader kilidinde bekleyebiliyor. Bu
     * yuzden ayri bir thread'de calistirilir ve 8 sn'de yanit gelmezse
     * null donulur; kurulum dongusu beklemeye devam eder, ajan kilitlenmez.
     */
    private static VersionResolver.Target resolveWithDeadline(
        Instrumentation instrumentation,
        String versionHint,
        String gameDir,
        String profileName
    ) throws InterruptedException {
        if (RESOLVE_IN_FLIGHT.get() >= 2) {
            return null;
        }
        Object[] result = new Object[1];
        RESOLVE_IN_FLIGHT.incrementAndGet();
        Thread worker = new Thread(() -> {
            try {
                result[0] = VersionResolver.resolve(
                    instrumentation, versionHint, gameDir, profileName, false
                );
            } catch (Throwable throwable) {
                AgentMain.log("Cozumleme hatasi: " + throwable);
            } finally {
                RESOLVE_IN_FLIGHT.decrementAndGet();
            }
        }, "40x1d0AC-resolve");
        worker.setDaemon(true);
        worker.start();
        worker.join(8000L);
        if (worker.isAlive()) {
            installReason = "install_timeout";
            return null;
        }
        return (VersionResolver.Target) result[0];
    }

    private static void startCollector(
        AgentClient client,
        VersionResolver.Target resolved
    ) {
        Path helper = helperPath();
        AttackCorrelator[] holder = new AttackCorrelator[1];
        InputCollector collector = new InputCollector(
            helper,
            event -> {
                AttackCorrelator current = holder[0];
                if (current != null) {
                    current.onInput(event);
                }
            },
            () -> markInputReady()
        );
        correlator = new AttackCorrelator(resolved, collector, client::sendTrigger);
        holder[0] = correlator;
        input = collector;
        try {
            Class<?> bootBridge = Class.forName(
                "dev.toxido.anticheat.agent.HookBridge", true, null
            );
            AgentMain.log(
                "HookBridge kimlik: agent=" + HookBridge.class.getClassLoader()
                    + " bootstrap=" + bootBridge.getClassLoader()
                    + " ayni=" + (bootBridge == HookBridge.class)
            );
        } catch (Throwable throwable) {
            AgentMain.log("HookBridge kimlik kontrolu basarisiz: " + throwable);
        }
        HookBridge.setSink(correlator::onAttack);
        HookBridge.setPacketSink(correlator::onPacket);
        correlator.setPacketSourceActive("active".equals(packetStatus));

        if (!input.ensureStarted()) {
            inputReason = "retry";
            AgentMain.log("Fare yardimcisi henuz baslatilamadi; yeniden denenecek.");
        } else {
            inputReason = input.isReady() ? "ready" : "starting";
        }
        startInputWatch();
        emitCapability();
    }

    private static void markInputReady() {
        inputFailures = 0;
        inputReason = "ready";
        if ("input_helper_missing".equals(reason)) {
            reason = "active".equals(packetStatus) ? "netty_active" : "netty_waiting";
        }
        emitCapability();
    }

    private static synchronized void startInputWatch() {
        if (inputWatchStarted) {
            return;
        }
        inputWatchStarted = true;
        Thread watcher = new Thread(
            Phase2::inputWatchLoop, "40x1d0AC-input-watch"
        );
        watcher.setDaemon(true);
        watcher.start();
    }

    private static void inputWatchLoop() {
        while (true) {
            sleep(5000L);
            try {
                InputCollector current = input;
                if (current == null) {
                    continue;
                }
                if (!current.isReady()) {
                    current.ensureStarted();
                }
                boolean ready = current.isReady();
                inputFailures = ready ? 0 : inputFailures + 1;
                String next = ready
                    ? "ready"
                    : (inputFailures >= 6 ? "input_helper_missing" : "retry");
                boolean changed = !next.equals(inputReason);
                inputReason = next;
                if ("input_helper_missing".equals(reason) && ready) {
                    reason = "active".equals(packetStatus) ? "netty_active" : "netty_waiting";
                    changed = true;
                }
                if (changed) {
                    emitCapability();
                }
            } catch (Throwable throwable) {
                AgentMain.log("Fare yardimcisi izleme hatasi: " + throwable);
            }
        }
    }

    /** Netty halkasinin canli oldugunu ve son durumunu kaydeder. */
    static void nettyHeartbeat(String detail) {
        nettyTickAt = System.currentTimeMillis();
        if (detail != null && !detail.isEmpty()) {
            nettyDetail = detail;
        }
    }

    /**
     * Adim bilgisini yayinlar. nettyTickAt'e dokunmaz; boylece bir adimda
     * kilitlenildiginde netty_age buyumeye devam eder ve kilit gorunur olur.
     */
    static void nettyNote(String detail) {
        if (detail == null || detail.isEmpty()) {
            return;
        }
        if (detail.equals(nettyDetail)) {
            return;
        }
        nettyDetail = detail;
        // Adim degisimini panoya tasi; refreshLoop bloke olsa bile kilit
        // noktasi gorunur kalir. En fazla 5 sn'de bir yayinlanir.
        long now = System.currentTimeMillis();
        long previous = lastNoteEmit.get();
        if (now - previous >= 5000L && lastNoteEmit.compareAndSet(previous, now)) {
            emitCapability();
        }
    }

    /**
     * Minecraft ornegi bulunamadiginda nedeni panoya tasir. Boylece "ornek yok"
     * durumu sessiz kalmaz; hangi erisimcinin hangi hatayi verdigi gorulur.
     */
    static void instanceNote(String note) {
        String value = note == null || note.isEmpty() ? "unknown" : note;
        if (value.equals(instanceNote)) {
            return;
        }
        instanceNote = value;
        emitCapability();
    }

    static void nettyWaiting(String nettyReason) {
        if ("active".equals(packetStatus) || nettyReason == null) {
            return;
        }
        boolean unsupported = nettyReason.startsWith("unsupported_version");
        boolean changed = !nettyReason.equals(packetReason)
            || !(unsupported ? "off" : "waiting").equals(packetStatus);
        packetStatus = unsupported ? "off" : "waiting";
        packetReason = nettyReason;
        packetHook = "-";
        if (changed) {
            emitCapability();
        }
    }

    static void versionDetected(
        GameVersionDetector.Result detected,
        String selectedProfile
    ) {
        if (detected == null) {
            return;
        }
        boolean changed = !detected.version.equals(detectedVersion)
            || !detected.loader.equals(detectedLoader)
            || !detected.source.equals(versionSource)
            || !(selectedProfile == null ? "-" : selectedProfile).equals(profileId);
        detectedVersion = detected.version;
        detectedLoader = detected.loader;
        versionSource = detected.source;
        mapping = detected.mapping;
        profileId = selectedProfile == null ? "-" : selectedProfile;
        if (changed) {
            emitCapability();
        }
    }

    static void nettyActive(String hook, String nettyReason) {
        if ("active".equals(packetStatus)) {
            return;
        }
        packetStatus = "active";
        packetReason = nettyReason == null ? "netty_packet_handler" : nettyReason;
        packetHook = hook == null ? "netty:packet_handler" : hook;
        AgentMain.log("Netty packet hook aktif: " + packetHook);
        AgentMain.debug("[NETTY] hook=active label=" + packetHook);
        AttackCorrelator current = correlator;
        if (current != null) {
            current.setPacketSourceActive(true);
        }
        emitCapability();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    public static void announce(AgentClient client) {
        client.sendCapability(capability());
    }

    public static String capability() {
        String trigger = "off";
        if (target != null && "active".equals(packetStatus)) {
            trigger = input != null && input.isReady() ? "active" : "degraded";
        } else if (target != null && "active".equals(hookStatus)) {
            trigger = "degraded";
        }
        String inputStatus = input == null ? "missing" : (input.isReady() ? "ready" : "starting");
        String version = detectedVersion;
        if ("unknown".equals(version) && target != null) {
            version = target.version;
        }
        String loader = detectedLoader;
        if ("unknown".equals(loader) && target != null) {
            loader = target.loader;
        }
        String currentInputReason = inputReason;
        if ("not_started".equals(currentInputReason) && input != null) {
            currentInputReason = input.isReady() ? "ready" : "starting";
        }
        String hook = "active".equals(packetStatus)
            ? packetHook
            : (target == null ? "-" : target.hook());
        String currentReason = reason;
        if ("input_helper_missing".equals(currentReason)) {
            // Bu hata packet durumundan daha onceliklidir.
        } else if ("off".equals(packetStatus) || "waiting".equals(packetStatus)) {
            currentReason = packetReason;
        }
        long nettyAge = nettyTickAt == 0L
            ? -1L
            : (System.currentTimeMillis() - nettyTickAt) / 1000L;
        return "{\"triggerbot\":" + Json.quote(trigger)
            + ",\"injection\":" + Json.quote("active")
            + ",\"version\":" + Json.quote(version)
            + ",\"version_source\":" + Json.quote(versionSource)
            + ",\"loader\":" + Json.quote(loader)
            + ",\"mapping\":" + Json.quote(mapping)
            + ",\"profile\":" + Json.quote(profileId)
            + ",\"jvm_main\":" + Json.quote(jvmMain)
            + ",\"pid\":" + jvmPid
            + ",\"hook\":" + Json.quote(hook)
            + ",\"packet\":" + Json.quote(packetStatus)
            + ",\"packet_hook\":" + Json.quote(packetHook)
            + ",\"packet_reason\":" + Json.quote(packetReason)
            + ",\"install\":" + Json.quote(installReason)
            + ",\"install_attempts\":" + installAttempts
            + ",\"install_running\":" + installRunning.get()
            + ",\"boots\":" + AgentMain.boots()
            + ",\"instance\":" + Json.quote(instanceNote)
            + ",\"netty_detail\":" + Json.quote(nettyDetail)
            + ",\"netty_age\":" + nettyAge
            + ",\"emit\":" + emitCount.get()
            + ",\"classes_total\":" + NettyHook.scanTotal()
            + ",\"mc_candidates\":" + NettyHook.scanCandidates()
            + ",\"input\":" + Json.quote(inputStatus)
            + ",\"input_reason\":" + Json.quote(currentInputReason)
            + ",\"reason\":" + Json.quote(currentReason) + "}";
    }

    private static String mainClass() {
        String command = System.getProperty("sun.java.command", "").trim();
        if (command.isEmpty()) {
            return "unknown";
        }
        int space = command.indexOf(' ');
        return space < 0 ? command : command.substring(0, space);
    }

    private static Path helperPath() {
        try {
            java.net.URL location = Phase2.class.getProtectionDomain()
                .getCodeSource().getLocation();
            Path path = Path.of(location.toURI());
            Path directory = Files.isDirectory(path) ? path : path.getParent();
            return directory == null ? null : directory.resolve("40x1d0AC-input.exe");
        } catch (Throwable ignored) {
            return null;
        }
    }
}
