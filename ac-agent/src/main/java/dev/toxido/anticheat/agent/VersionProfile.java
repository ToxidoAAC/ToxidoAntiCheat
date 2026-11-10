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

    // Vanilla (obfuscated) istemciler icin ek alanlar. Fabric/Mojang
    // profillerinde null kalir; mevcut isim tabanli yedekler kullanilir.
    public final String localPlayerClass;
    public final String screenClass;
    public final String screenField;
    public final String optionsClass;
    public final String optionsField;
    public final String keyMappingClass;
    public final String attackKeyField;
    public final String keyNameField;
    public final String keyDownMethod;
    public final String livingClass;
    public final String playerEntityClass;
    public final String armorStandClass;
    public final String entityClass;
    public final String packetEntityField;
    public final String packetActionField;
    public final String packetAttackActionField;

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
        this(
            id,
            minecraftClass,
            worldField,
            networkHandlerField,
            networkHandlerClass,
            connectionMethod,
            connectionClass,
            channelField,
            attackClass,
            attackMethod,
            packetClass,
            null, null, null, null, null, null, null, null, null,
            null, null, null, null, null, null, null
        );
    }

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
        String packetClass,
        String localPlayerClass,
        String screenClass,
        String screenField,
        String optionsClass,
        String optionsField,
        String keyMappingClass,
        String attackKeyField,
        String keyNameField,
        String keyDownMethod,
        String livingClass,
        String playerEntityClass,
        String armorStandClass,
        String entityClass,
        String packetEntityField,
        String packetActionField,
        String packetAttackActionField
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
        this.localPlayerClass = localPlayerClass;
        this.screenClass = screenClass;
        this.screenField = screenField;
        this.optionsClass = optionsClass;
        this.optionsField = optionsField;
        this.keyMappingClass = keyMappingClass;
        this.attackKeyField = attackKeyField;
        this.keyNameField = keyNameField;
        this.keyDownMethod = keyDownMethod;
        this.livingClass = livingClass;
        this.playerEntityClass = playerEntityClass;
        this.armorStandClass = armorStandClass;
        this.entityClass = entityClass;
        this.packetEntityField = packetEntityField;
        this.packetActionField = packetActionField;
        this.packetAttackActionField = packetAttackActionField;
    }
}
