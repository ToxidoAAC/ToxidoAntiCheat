package dev.toxido.anticheat.agent;

/** Disari mudahale/supheli enjeksiyon bulgusu. */
public final class InjectionFinding {
    public final String key;
    public final String kind;
    public final String name;
    public final String path;
    public final String sha256;
    public final String signature;
    public final String description;
    public final String confidence;
    public final String evidence;
    public final boolean exists;
    public final boolean deleted;

    public InjectionFinding(
        String key,
        String kind,
        String name,
        String path,
        String sha256,
        String signature,
        String description,
        String confidence,
        String evidence,
        boolean exists,
        boolean deleted
    ) {
        this.key = key;
        this.kind = kind;
        this.name = name;
        this.path = path;
        this.sha256 = sha256;
        this.signature = signature;
        this.description = description;
        this.confidence = confidence;
        this.evidence = evidence;
        this.exists = exists;
        this.deleted = deleted;
    }
}