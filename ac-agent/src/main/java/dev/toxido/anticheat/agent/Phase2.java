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
    private static volatile String packetReason = "waiting_for_packet_class";
    private static volatile String packetHook = "-";

    private Phase2() {
    }

    public static void start(
        Instrumentation instrumentation,
        AgentClient client,
        String versionHint
    ) {
        injection = new InjectionScanner(instrumentation, client);
        injection.start();

        Thread packetInstaller = new Thread(
            () -> installPacketWhenReady(instrumentation, client),
            "40x1d0AC-packet-install"
        );
        packetInstaller.setDaemon(true);
        packetInstaller.start();

        Thread installer = new Thread(
            () -> installWhenReady(instrumentation, client, versionHint),
            "40x1d0AC-hook-install"
        );
        installer.setDaemon(true);
        installer.start();
    }

    private static void installPacketWhenReady(
        Instrumentation instrumentation,
        AgentClient client
    ) {
        int attempts = 0;
        while (true) {
            VersionResolver.PacketTarget resolved = VersionResolver.resolvePacket(instrumentation);
            if (resolved != null) {
                try {
                    PacketHook hook = new PacketHook(resolved);
                    instrumentation.addTransformer(hook, true);
                    instrumentation.retransformClasses(resolved.owner);
                    packetStatus = "active";
                    packetReason = "packet_hooked";
                    packetHook = resolved.hook();
                    AgentMain.log("Packet hook aktif: " + resolved.hook());
                    AgentMain.debug("[PACKET] hook=active label=" + resolved.hook());
                    AttackCorrelator current = correlator;
                    if (current != null) {
                        current.setPacketSourceActive(true);
                    }
                    if (client != null) {
                        client.sendCapability(capability());
                    }
                    return;
                } catch (Throwable throwable) {
                    packetStatus = "off";
                    packetReason = "packet_hook_failed";
                    AgentMain.log("Packet hook kurulamadi: " + throwable);
                    AgentMain.debug("[REJECT] reason=packet_hook_failed error=" + throwable);
                    if (client != null) {
                        client.sendCapability(capability());
                    }
                    return;
                }
            }

            attempts++;
            if (attempts == 30) {
                AgentMain.log("Baglanti sinifi henuz yuklenmedi; packet hook bekliyor.");
            }
            sleep(attempts < 30 ? 500L : 2000L);
        }
    }

    private static void installWhenReady(
        Instrumentation instrumentation,
        AgentClient client,
        String versionHint
    ) {
        // Minecraft siniflari oyun acilirken gec yuklenir (genelde dunyaya
        // girince). Tek seferlik cozumleme yerine hazir olana kadar bekle.
        reason = "waiting_for_attack_class";
        int attempts = 0;
        while (true) {
            VersionResolver.Target resolved = target;
            if (resolved == null) {
                resolved = VersionResolver.resolve(instrumentation, versionHint, false);
                target = resolved;
            }

            if (resolved != null) {
                // Packet hook asil kaynaktir; attack hook yalnizca yedektir.
                // Attack hook kurulamasa bile packet korelasyonu baslatilir.
                installHook(instrumentation, resolved);
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

    private static boolean installHook(
        Instrumentation instrumentation,
        VersionResolver.Target resolved
    ) {
        try {
            AttackHook hook = new AttackHook(resolved);
            instrumentation.addTransformer(hook, true);
            instrumentation.retransformClasses(resolved.owner);
            hookStatus = "active";
            reason = "hooked";
            AgentMain.log("Attack hook aktif: " + resolved.hook());
            return true;
        } catch (Throwable throwable) {
            hookStatus = "off";
            reason = "hook_failed";
            AgentMain.log("Attack hook kurulamadi: " + throwable);
            return false;
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
            () -> client.sendCapability(capability())
        );
        correlator = new AttackCorrelator(resolved, collector, client::sendTrigger);
        holder[0] = correlator;
        input = collector;
        HookBridge.setSink(correlator::onAttack);
        HookBridge.setPacketSink(correlator::onPacket);
        correlator.setPacketSourceActive("active".equals(packetStatus));

        if (!input.start()) {
            reason = "input_helper_missing";
            AgentMain.log("Fare yardimcisi baslatilamadi; triggerbot degraded.");
        }
        client.sendCapability(capability());
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
        String version = target == null ? "unknown" : target.version;
        String loader = target == null ? "unknown" : target.loader;
        String hook = target == null ? "-" : target.hook();
        String currentReason = reason;
        if ("hook_failed".equals(currentReason) || "input_helper_missing".equals(currentReason)) {
            // Bu iki hata packet durumundan daha onceliklidir.
        } else if ("off".equals(packetStatus)) {
            currentReason = "packet_hook_failed";
        } else if ("waiting".equals(packetStatus)) {
            currentReason = "waiting_for_packet_hook";
        }
        return "{\"triggerbot\":" + Json.quote(trigger)
            + ",\"injection\":" + Json.quote("active")
            + ",\"version\":" + Json.quote(version)
            + ",\"loader\":" + Json.quote(loader)
            + ",\"hook\":" + Json.quote(hook)
            + ",\"packet\":" + Json.quote(packetStatus)
            + ",\"packet_hook\":" + Json.quote(packetHook)
            + ",\"packet_reason\":" + Json.quote(packetReason)
            + ",\"input\":" + Json.quote(inputStatus)
            + ",\"reason\":" + Json.quote(currentReason) + "}";
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
