package dev.toxido.anticheat.agent;

/** Fiziksel veya enjekte edilmis fare olayi. */
public final class InputEvent {
    public final boolean down;
    public final long timeMs;
    public final boolean injected;
    public final boolean raw;
    public final long messageTime;
    public boolean consumed;

    public InputEvent(
        boolean down,
        long timeMs,
        boolean injected,
        boolean raw,
        long messageTime
    ) {
        this.down = down;
        this.timeMs = timeMs;
        this.injected = injected;
        this.raw = raw;
        this.messageTime = messageTime;
    }
}