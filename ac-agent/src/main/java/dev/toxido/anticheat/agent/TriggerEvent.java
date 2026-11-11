package dev.toxido.anticheat.agent;

/** TriggerBot korelasyon sonucu. */
public final class TriggerEvent {
    public final String verdict;
    public final String reason;
    public final String key;
    public final String hook;
    public final long attackTimeMs;
    public final long inputAgeMs;
    public final boolean physicalClick;
    public final boolean injectedClick;
    public final boolean rawClick;
    public final String targetType;
    public final String targetName;
    public final String targetKind;
    public final String targetUuid;
    public final String evidence;

    public TriggerEvent(
        String verdict,
        String reason,
        String key,
        String hook,
        long attackTimeMs,
        long inputAgeMs,
        boolean physicalClick,
        boolean injectedClick,
        boolean rawClick,
        String targetType,
        String targetName,
        String targetKind,
        String targetUuid,
        String evidence
    ) {
        this.verdict = verdict;
        this.reason = reason;
        this.key = key;
        this.hook = hook;
        this.attackTimeMs = attackTimeMs;
        this.inputAgeMs = inputAgeMs;
        this.physicalClick = physicalClick;
        this.injectedClick = injectedClick;
        this.rawClick = rawClick;
        this.targetType = targetType;
        this.targetName = targetName;
        this.targetKind = targetKind;
        this.targetUuid = targetUuid;
        this.evidence = evidence;
    }
}