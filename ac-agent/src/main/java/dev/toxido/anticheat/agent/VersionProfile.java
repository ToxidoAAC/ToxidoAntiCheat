package dev.toxido.anticheat.agent;

/**
 * Bir Minecraft surumu/loader/mapping ailesi icin sabit hook zinciri.
 * Runtime'da Minecraft metodu veya string'i aranmaz.
 */
public final class VersionProfile {
    public final String id;
    public final String minecraftClass;
    public final String worldField;
    public final String networkHandlerField;
    public final String networkHandlerClass;
    public final String connectionMethod;
    public final String connectionClass;
    public final String channelField;
    public final String attackClass;
    public final String attackMethod;
    public final String packetClass;

    public VersionProfile(
        String id,
        String minecraftClass,
        String worldField,
        String networkHandlerField,
        String networkHandlerClass,
        String connectionMethod,
        String connectionClass,
        String channelField,
        String attackClass,
        String attackMethod,
        String packetClass
    ) {
        this.id = id;
        this.minecraftClass = minecraftClass;
        this.worldField = worldField;
        this.networkHandlerField = networkHandlerField;
        this.networkHandlerClass = networkHandlerClass;
        this.connectionMethod = connectionMethod;
        this.connectionClass = connectionClass;
        this.channelField = channelField;
        this.attackClass = attackClass;
        this.attackMethod = attackMethod;
        this.packetClass = packetClass;
    }
}
