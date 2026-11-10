package dev.toxido.anticheat.agent;

import java.util.Locale;

/**
 * Desteklenen surum profilleri. Yeni surum eklemek icin buraya sabit bir
 * {@link VersionProfile} eklenir; Netty zinciri profil alanlarindan okunur.
 */
public final class VersionProfiles {
    public static final VersionProfile FABRIC_1_21_11 = new VersionProfile(
        "fabric-1.21.11",
        "net.minecraft.class_310",
        "field_1724",
        "field_3944",
        "net.minecraft.class_634",
        "method_48296",
        "net.minecraft.class_2535",
        "field_11651",
        "net.minecraft.class_636",
        "method_2918",
        "net.minecraft.class_2824"
    );

    /**
     * Fabric intermediary ailesi icin genel zincir. 1.21.11 disindaki
     * surumlerde alan/metot adlari degisebilir; profil bulunamazsa
     * Netty hook kendini kapatmaz, teşhis nedeniyle bekler.
     */
    public static final VersionProfile FABRIC_INTERMEDIARY = new VersionProfile(
        "fabric-intermediary",
        "net.minecraft.class_310",
        "field_1724",
        "field_3944",
        "net.minecraft.class_634",
        "method_48296",
        "net.minecraft.class_2535",
        "field_11651",
        "net.minecraft.class_636",
        "method_2918",
        "net.minecraft.class_2824"
    );

    /**
     * Mojang isimleriyle calisan istemciler. 26.x istemcileri artik
     * obfuscate edilmedigi icin Fabric/vanilla fark etmeksizin bu isimler
     * kullanilir. Ayni zincir 1.18-1.21.11 Mojang mapping'lerinde de
     * dogrulanmistir.
     */
    public static final VersionProfile MOJANG_MODERN = new VersionProfile(
        "mojang-1.18-26.3",
        "net.minecraft.client.Minecraft",
        "player",
        "connection",
        "net.minecraft.client.multiplayer.ClientPacketListener",
        "getConnection",
        "net.minecraft.network.Connection",
        "channel",
        "net.minecraft.client.multiplayer.MultiPlayerGameMode",
        "attack",
        "net.minecraft.network.protocol.game.ServerboundAttackPacket"
    );

    private static volatile VersionProfile active;

    private VersionProfiles() {
    }

    public static void setActive(VersionProfile profile) {
        active = profile;
    }

    public static VersionProfile active() {
        return active;
    }

    public static VersionProfile select(
        String version,
        String loader,
        String mapping
    ) {
        String normalizedVersion = version == null
            ? ""
            : version.trim().toLowerCase(Locale.ROOT);
        String normalizedLoader = loader == null
            ? ""
            : loader.trim().toLowerCase(Locale.ROOT);
        String normalizedMapping = mapping == null
            ? ""
            : mapping.trim().toLowerCase(Locale.ROOT);

        boolean fabric = normalizedLoader.contains("fabric")
            || normalizedLoader.contains("quilt")
            || normalizedMapping.contains("intermediary");
        boolean mojang = normalizedMapping.contains("mojang");
        boolean modern26 = normalizedVersion.startsWith("26.");
        boolean forge = normalizedLoader.contains("forge")
            || normalizedLoader.contains("neoforge");

        // 26.x istemcileri obfuscate edilmiyor; Mojang isimleri dogrudan
        // classloader'da bulunur. Modern Forge/NeoForge de Mojang adlarini
        // kullanir. Mapping tespiti kacirsa surumden anla.
        if (mojang || modern26 || forge) {
            return MOJANG_MODERN;
        }
        if (fabric) {
            if (normalizedVersion.startsWith("1.21.11")) {
                return FABRIC_1_21_11;
            }
            return FABRIC_INTERMEDIARY;
        }

        // Duz (vanilla) istemciler: surume ozel obfuscated profil.
        return VanillaProfiles.get(normalizedVersion);
    }
}
