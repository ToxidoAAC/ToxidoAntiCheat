package dev.toxido.anticheat.agent;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Fiziksel fare tiklamasi ile attack cagrisini eslestirir.
 * Bir fiziksel basma yalnizca bir attack'i mesrulastirir.
 *
 * Attack kaynagi iki tanedir:
 *  - attack: ClientPlayerInteractionManager.attackEntity (yedek)
 *  - packet: ClientConnection.send icindeki ServerboundInteractPacket (asil)
 */
public final class AttackCorrelator {
    private static final long WINDOW_BEFORE_MS = 300;
    private static final long WINDOW_AFTER_MS = 50;
    private static final long REPORT_BUCKET_MS = 500;

    private final Deque<InputEvent> lowDowns = new ArrayDeque<>();
    private final Deque<InputEvent> rawDowns = new ArrayDeque<>();
    private final Map<String, Long> reported = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "40x1d0AC-trigger");
        thread.setDaemon(true);
        return thread;
    });
    private final VersionResolver.Target target;
    private final InputCollector input;
    private final Consumer<TriggerEvent> sink;
    private volatile boolean capabilityDegradedLogged;
    private volatile boolean packetSourceActive;
    private volatile boolean physicalHeld;
    private volatile boolean injectedHeld;
    private volatile boolean rawHeld;

    public AttackCorrelator(
        VersionResolver.Target target,
        InputCollector input,
        Consumer<TriggerEvent> sink
    ) {
        this.target = target;
        this.input = input;
        this.sink = sink;
    }

    public void onInput(InputEvent event) {
        if (event == null) {
            return;
        }

        if (event.raw) {
            rawHeld = event.down;
            if (!event.down) {
                return;
            }
            synchronized (lowDowns) {
                rawDowns.addLast(event);
                prune(rawDowns, event.timeMs);
            }
            return;
        }

        if (event.injected) {
            injectedHeld = event.down;
        } else {
            physicalHeld = event.down;
        }

        if (!event.down) {
            return;
        }
        synchronized (lowDowns) {
            lowDowns.addLast(event);
            prune(lowDowns, event.timeMs);
        }
    }

    /** Packet hook aktifken yuksek seviyeli hook yedek olarak kullanilmaz. */
    public void setPacketSourceActive(boolean active) {
        packetSourceActive = active;
    }

    /** Yuksek seviyeli attackEntity cagrisi (yedek kaynak). */
    public void onAttack(AttackEvent attack) {
        if (attack == null || attack.target == null) {
            return;
        }
        if (packetSourceActive) {
            AgentMain.debug("[ATTACK] source=attack ignored=packet_primary");
            return;
        }
        AgentMain.debug(
            "[ATTACK] source=attack target=" + describe(attack.target)
                + " hook=" + attack.hook
        );
        executor.execute(() -> evaluate(attack, "attack"));
    }

    /** ClientConnection.send icinden gelen ham packet (asil kaynak). */
    public void onPacket(Object packet, String hook) {
        PacketAttack.Result result = PacketAttack.resolve(packet);
        if (!result.attack) {
            return;
        }

        AgentMain.debug(
            "[PACKET] class=" + result.packetClass
                + " entity=" + result.entityId
                + " target=" + describe(result.target)
                + " hook=" + hook
        );

        if (result.target == null) {
            AgentMain.debug(
                "[REJECT] reason=packet_target_missing source=packet entity="
                    + result.entityId
            );
            return;
        }

        long now = System.currentTimeMillis();
        AttackEvent attack = new AttackEvent(now, null, result.target, hook);
        executor.execute(() -> evaluate(attack, "packet"));
    }

    private void evaluate(AttackEvent attack, String source) {
        try {
            if (!input.isReady()) {
                AgentMain.debug("[REJECT] reason=input_not_ready source=" + source);
                return;
            }

            GameState state = GameState.inspect(target, attack.target);
            String stateText = "screen=" + state.screenOpen
                + " key=" + state.attackKeyPressed
                + " living=" + state.targetLiving
                + " player=" + state.targetPlayer
                + " armorstand=" + state.targetArmorStand
                + " tablist=" + state.targetInTabList;

            if (!state.reliable) {
                if (!capabilityDegradedLogged) {
                    capabilityDegradedLogged = true;
                    AgentMain.log("Oyun durumu guvenilir degil; triggerbot raporu kapali.");
                }
                AgentMain.debug("[REJECT] reason=state_unreliable source=" + source);
                return;
            }

            if (state.screenOpen) {
                AgentMain.debug("[REJECT] reason=screen_open source=" + source
                    + " target=" + state.targetName);
                return;
            }
            if (!state.targetLiving) {
                AgentMain.debug("[REJECT] reason=non_living source=" + source
                    + " target=" + state.targetName);
                return;
            }
            if (state.targetArmorStand) {
                AgentMain.debug("[REJECT] reason=armor_stand source=" + source
                    + " target=" + state.targetName);
                return;
            }
            if (state.targetPlayer && !state.targetInTabList) {
                AgentMain.debug("[REJECT] reason=plugin_npc source=" + source
                    + " target=" + state.targetName);
                return;
            }

            // Basili tutulan fiziksel tik da normaldir; tek bir down olayi
            // yalnizca ilk attack'i degil, tus birakilana kadar tum attack'lari
            // mesrulastirir.
            if (physicalHeld) {
                AgentMain.debug(
                    "[TRIGGER] normal physical hold source=" + source
                        + " target=" + state.targetName
                );
                return;
            }

            if (injectedHeld) {
                report(
                    "External TriggerBot",
                    "injected_hold",
                    attack,
                    source,
                    state,
                    null,
                    true,
                    rawHeld,
                    "LL injected tik basili tutuluyor"
                );
                return;
            }

            InputEvent physical = null;
            InputEvent injected = null;
            boolean rawAny = false;

            synchronized (lowDowns) {
                prune(lowDowns, attack.timeMs);
                for (InputEvent event : lowDowns) {
                    if (event.consumed) {
                        continue;
                    }
                    if (event.timeMs < attack.timeMs - WINDOW_BEFORE_MS
                        || event.timeMs > attack.timeMs + WINDOW_AFTER_MS) {
                        continue;
                    }
                    if (event.injected) {
                        if (injected == null || event.timeMs > injected.timeMs) {
                            injected = event;
                        }
                    } else if (event.timeMs <= attack.timeMs + WINDOW_AFTER_MS) {
                        if (physical == null || event.timeMs > physical.timeMs) {
                            physical = event;
                        }
                    }
                }

                prune(rawDowns, attack.timeMs);
                for (InputEvent event : rawDowns) {
                    if (event.timeMs >= attack.timeMs - WINDOW_BEFORE_MS
                        && event.timeMs <= attack.timeMs + WINDOW_AFTER_MS) {
                        rawAny = true;
                        break;
                    }
                }
            }

            // Fiziksel LL click her zaman normaldir; sahte/injected click'i
            // fiziksel sayma. Raw Input tek basina fiziksel kanit degildir.
            if (physical != null) {
                AgentMain.debug(
                    "[TRIGGER] normal physical click source=" + source
                        + " target=" + state.targetName
                );
                return;
            }

            if (injected != null) {
                report(
                    "External TriggerBot",
                    "injected_only",
                    attack,
                    source,
                    state,
                    injected,
                    true,
                    rawAny,
                    "LL injected down at -" + (attack.timeMs - injected.timeMs) + "ms"
                );
                return;
            }

            // Raw input var ama LL click yok: enjekte mi fiziksel mi belirsiz.
            // Yanlis pozitif uretmemek icin fail-closed davran.
            if (rawAny || rawHeld) {
                AgentMain.debug(
                    "[REJECT] reason=raw_only_ambiguous source=" + source
                        + " target=" + state.targetName
                );
                return;
            }

            if (input.isControllerDown()) {
                AgentMain.debug(
                    "[REJECT] reason=controller_exempt source=" + source
                        + " target=" + state.targetName
                );
                return;
            }

            if (state.attackKeyPressed) {
                AgentMain.debug(
                    "[REJECT] reason=keyboard_attack_exempt source=" + source
                        + " target=" + state.targetName
                );
                return;
            }

            report(
                "Un-External TriggerBot",
                "no_physical_click",
                attack,
                source,
                state,
                null,
                false,
                false,
                "Attack penceresinde LL asagi olayi yok; attack packeti dogrudan gonderildi"
            );
        } catch (Throwable throwable) {
            AgentMain.debug(
                "[REJECT] reason=evaluate_error source=" + source
                    + " error=" + throwable
            );
        }
    }

    private void report(
        String verdict,
        String reason,
        AttackEvent attack,
        String source,
        GameState state,
        InputEvent injected,
        boolean injectedClick,
        boolean rawClick,
        String evidence
    ) {
        long age = injected == null ? -1 : attack.timeMs - injected.timeMs;
        String bucket = Long.toString(attack.timeMs / REPORT_BUCKET_MS);
        String targetKey = state.targetUuid;
        if (targetKey == null || targetKey.isBlank()) {
            targetKey = state.targetName + "@" + System.identityHashCode(attack.target);
        }
        String key = "tb:" + targetKey + ":" + reason + ":" + bucket;
        long now = System.currentTimeMillis();
        Long previous = reported.putIfAbsent(key, now);
        if (previous != null) {
            AgentMain.debug(
                "[REJECT] reason=duplicate source=" + source
                    + " key=" + key
            );
            return;
        }
        if (reported.size() > 300) {
            reported.entrySet().removeIf(entry -> now - entry.getValue() > 10_000);
        }

        TriggerEvent event = new TriggerEvent(
            verdict,
            reason,
            key,
            attack.hook,
            attack.timeMs,
            age,
            false,
            injectedClick,
            rawClick,
            state.targetType,
            state.targetName,
            state.targetUuid,
            evidence
        );

        AgentMain.log(
            "TriggerBot: " + verdict + " hedef=" + state.targetName
                + " sebep=" + reason + " kaynak=" + source
        );
        AgentMain.debug(
            "[TRIGGER] verdict=" + verdict + " reason=" + reason
                + " source=" + source + " target=" + state.targetName
                + " state=" + stateText(state)
        );
        if (sink != null) {
            sink.accept(event);
        }
    }

    private static String stateText(GameState state) {
        return "screen=" + state.screenOpen
            + ",key=" + state.attackKeyPressed
            + ",living=" + state.targetLiving
            + ",player=" + state.targetPlayer
            + ",armorstand=" + state.targetArmorStand
            + ",tablist=" + state.targetInTabList;
    }

    private static String describe(Object target) {
        if (target == null) {
            return "null";
        }
        String name = target.getClass().getName();
        try {
            Object value = target.getClass().getMethod("getName").invoke(target);
            if (value != null) {
                name = name + "(" + value + ")";
            }
        } catch (Throwable ignored) {
        }
        return name;
    }

    private static void prune(Deque<InputEvent> events, long now) {
        while (!events.isEmpty() && events.peekFirst().timeMs < now - 2000) {
            events.removeFirst();
        }
    }
}
