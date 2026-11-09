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

    private VersionProfiles() {
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
        if (!fabric) {
            return null;
        }
        if (normalizedVersion.startsWith("1.21.11")) {
            return FABRIC_1_21_11;
        }
        return FABRIC_INTERMEDIARY;
    }
}
