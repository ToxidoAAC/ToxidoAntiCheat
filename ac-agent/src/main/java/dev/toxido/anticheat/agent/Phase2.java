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
    private static volatile String packetReason = "starting";
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

        try {
            NettyHook.start(instrumentation, client, versionHint, gameDir, profileName);
        } catch (Throwable throwable) {
            packetStatus = "waiting";
            packetReason = "netty_hook_unavailable";
            packetHook = "-";
            AgentMain.log("Netty hook baslatilamadi: " + throwable);
        }

        Thread installer = new Thread(
            () -> installWhenReady(
                instrumentation, client, versionHint, gameDir, profileName
            ),
            "40x1d0AC-hook-install"
        );
        installer.setDaemon(true);
        installer.start();
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
        int attempts = 0;
        while (true) {
            VersionResolver.Target resolved = target;
            if (resolved == null) {
                resolved = VersionResolver.resolve(
                    instrumentation, versionHint, gameDir, profileName, false
                );
                target = resolved;
            }

            if (resolved != null) {
                // Paket kaynagi Netty pipeline'dir; Minecraft bytecode'una
                // hook enjekte edilmez. Attack hook yalnizca surum/loader
                // bilgisini ve yedek sinyal yolunu hazirlar.
                hookStatus = "off";
                reason = "netty_waiting";
                startCollector(client, resolved);
                return;
            }

            attempts++;
            if (attempts == 30) {
                AgentMain.log("Attack sinifi henuz yuklenmedi; beklemeye devam ediliyor.");
            }
            sleep(attempts < 30 ? 1000L : 5000L);
        }
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
            () -> {
                inputReason = input != null && input.isReady() ? "ready" : "starting";
                client.sendCapability(capability());
            }
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

        if (!input.start()) {
            reason = "input_helper_missing";
            inputReason = "input_helper_missing";
            AgentMain.log("Fare yardimcisi baslatilamadi; triggerbot degraded.");
        } else {
            inputReason = input.isReady() ? "ready" : "starting";
        }
        client.sendCapability(capability());
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
            AgentClient client = clientRef;
            if (client != null) {
                client.sendCapability(capability());
            }
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
            AgentClient client = clientRef;
            if (client != null) {
                client.sendCapability(capability());
            }
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
        AgentClient client = clientRef;
        if (client != null) {
            client.sendCapability(capability());
        }
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
