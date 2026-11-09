package dev.toxido.anticheat.agent;

/** Oyun thread'inde yakalanan ham attack olayi. */
public final class AttackEvent {
    public final long timeMs;
    public final Object player;
    public final Object target;
    public final String hook;

    public AttackEvent(long timeMs, Object player, Object target, String hook) {
        this.timeMs = timeMs;
        this.player = player;
        this.target = target;
        this.hook = hook;
    }
}