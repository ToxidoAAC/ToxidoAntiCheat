package dev.toxido.anticheat.agent;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Fiziksel fare tiklamasi ile attack cagrisini eslestirir.
 * Bir fiziksel basma yalnizca bir attack'i mesrulastirir.
 */
public final class AttackCorrelator {
    private static final long WINDOW_BEFORE_MS = 300;
    private static final long WINDOW_AFTER_MS = 15;

    private final Deque<InputEvent> lowDowns = new ArrayDeque<>();
    private final Deque<InputEvent> rawDowns = new ArrayDeque<>();
    private final Set<String> reported = ConcurrentHashMap.newKeySet();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "40x1d0AC-trigger");
        thread.setDaemon(true);
        return thread;
    });
    private final VersionResolver.Target target;
    private final InputCollector input;
    private final Consumer<TriggerEvent> sink;
    private volatile boolean capabilityDegradedLogged;

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
        if (!event.down) {
            return;
        }
        synchronized (lowDowns) {
            if (event.raw) {
                rawDowns.addLast(event);
                prune(rawDowns, event.timeMs);
            } else {
                lowDowns.addLast(event);
                prune(lowDowns, event.timeMs);
            }
        }
    }

    public void onAttack(AttackEvent attack) {
        executor.execute(() -> evaluate(attack));
    }

    private void evaluate(AttackEvent attack) {
        if (!input.isReady()) {
            return;
        }

        GameState state = GameState.inspect(target, attack.target);
        if (!state.reliable) {
            if (!capabilityDegradedLogged) {
                capabilityDegradedLogged = true;
                AgentMain.log("Oyun durumu guvenilir degil; triggerbot raporu kapali.");
            }
            return;
        }

        if (state.screenOpen
            || state.attackKeyPressed
            || !state.targetLiving
            || state.targetArmorStand
            || (state.targetPlayer && !state.targetInTabList)) {
            return;
        }

        InputEvent physical = null;
        InputEvent injected = null;
        boolean rawPhysical = false;

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
                    rawPhysical = true;
                    break;
                }
            }
        }

        if (physical != null || rawPhysical) {
            if (physical != null) {
                physical.consumed = true;
            }
            return;
        }

        String verdict;
        String reason;
        boolean injectedClick;
        long age;
        if (injected != null) {
            injected.consumed = true;
            verdict = "External TriggerBot";
            reason = "injected_only";
            injectedClick = true;
            age = attack.timeMs - injected.timeMs;
        } else {
            verdict = "Un-External TriggerBot";
            reason = "no_physical_click";
            injectedClick = false;
            age = -1;
        }

        String key = "tb:" + attack.timeMs + ":" + state.targetUuid + ":" + reason;
        if (!reported.add(key)) {
            return;
        }

        String evidence = injectedClick
            ? "LL injected down at -" + age + "ms; raw hardware down yok"
            : "Attack penceresinde LL asagi olayi yok; yalnizca attack cagrisi";

        TriggerEvent event = new TriggerEvent(
            verdict,
            reason,
            key,
            attack.hook,
            attack.timeMs,
            age,
            false,
            injectedClick,
            rawPhysical,
            state.targetType,
            state.targetName,
            state.targetUuid,
            evidence
        );

        AgentMain.log("TriggerBot: " + verdict + " hedef=" + state.targetName + " sebep=" + reason);
        if (sink != null) {
            sink.accept(event);
        }
    }

    private static void prune(Deque<InputEvent> events, long now) {
        while (!events.isEmpty() && events.peekFirst().timeMs < now - 2000) {
            events.removeFirst();
        }
    }
}