package dev.toxido.anticheat.agent;

/**
 * Bir Minecraft surumu/loader/mapping ailesi icin katalogdan gelen sabit
 * hook zinciri. Runtime'da Minecraft metodu veya string'i aranmaz.
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

    // Katalogun ek alanlari.
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

    public final String version;
    public final String loader;
    public final String mapping;
    public final String source;
    public final String connectionField;
    public final String minecraftConnectionMethod;
    public final String channelMethod;
    public final String pipelineHandler;
    public final String packetKind;
    public final String entityIdAccessor;
    public final String screenAccess;
    public final String guiField;
    public final String guiScreenField;
    public final boolean verified;
    public final boolean loaderVerified;
    public final String notes;
    public final String missing;

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
            id, minecraftClass, worldField, networkHandlerField, networkHandlerClass,
            connectionMethod, connectionClass, channelField, attackClass, attackMethod,
            packetClass, null, null, null, null, null, null, null, null, null,
            null, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null, null, null, null,
            null, null, false, false, "", ""
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
        this(
            id, minecraftClass, worldField, networkHandlerField, networkHandlerClass,
            connectionMethod, connectionClass, channelField, attackClass, attackMethod,
            packetClass, localPlayerClass, screenClass, screenField, optionsClass,
            optionsField, keyMappingClass, attackKeyField, keyNameField, keyDownMethod,
            livingClass, playerEntityClass, armorStandClass, entityClass,
            packetEntityField, packetActionField, packetAttackActionField,
            null, null, null, null, null, null, null, null, null, null, null,
            null, null, false, false, "", ""
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
        String packetAttackActionField,
        String version,
        String loader,
        String mapping,
        String source,
        String connectionField,
        String minecraftConnectionMethod,
        String channelMethod,
        String pipelineHandler,
        String packetKind,
        String entityIdAccessor,
        String screenAccess,
        String guiField,
        String guiScreenField,
        boolean verified,
        boolean loaderVerified,
        String notes,
        String missing
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
        this.version = version;
        this.loader = loader;
        this.mapping = mapping;
        this.source = source;
        this.connectionField = connectionField;
        this.minecraftConnectionMethod = minecraftConnectionMethod;
        this.channelMethod = channelMethod;
        this.pipelineHandler = pipelineHandler;
        this.packetKind = packetKind;
        this.entityIdAccessor = entityIdAccessor;
        this.screenAccess = screenAccess;
        this.guiField = guiField;
        this.guiScreenField = guiScreenField;
        this.verified = verified;
        this.loaderVerified = loaderVerified;
        this.notes = notes;
        this.missing = missing;
    }

    /** TriggerBot icin gerekli asgari zincir katalogda dolu mu? */
    public boolean complete() {
        return missingRequired().isEmpty();
    }

    /** Eksik zorunlu alanlari virgulle ayrilmis olarak dondurur. */
    public String missingRequired() {
        StringBuilder builder = new StringBuilder();
        appendMissing(builder, "minecraftClass", minecraftClass);
        appendMissing(builder, "worldField", worldField);
        appendMissing(builder, "networkHandlerField", networkHandlerField);
        appendMissing(builder, "networkHandlerClass", networkHandlerClass);
        appendMissing(builder, "connectionClass", connectionClass);
        if (empty(connectionMethod) && empty(connectionField)
            && empty(minecraftConnectionMethod)) {
            appendName(builder, "connectionMethod|connectionField");
        }
        appendMissing(builder, "attackClass", attackClass);
        appendMissing(builder, "attackMethod", attackMethod);
        appendMissing(builder, "packetClass", packetClass);
        if (empty(channelField) && empty(channelMethod)) {
            appendName(builder, "channelField|channelMethod");
        }
        appendMissing(builder, "pipelineHandler", pipelineHandler);
        return builder.toString();
    }

    private static void appendMissing(
        StringBuilder builder, String name, String value
    ) {
        if (empty(value)) {
            appendName(builder, name);
        }
    }

    private static void appendName(StringBuilder builder, String name) {
        if (builder.length() > 0) {
            builder.append(',');
        }
        builder.append(name);
    }

    private static boolean empty(String value) {
        return value == null || value.isEmpty();
    }
}
