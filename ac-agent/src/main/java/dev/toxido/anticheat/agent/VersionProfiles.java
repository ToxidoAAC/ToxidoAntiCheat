package dev.toxido.anticheat.agent;

import java.util.List;

/**
 * Aktif katalog profilinin ince cephesi. Tum surum/loader bilgisi
 * {@link VersionCatalog} tarafindan {@code version-catalog.json} dosyasindan
 * okunur; burada sabit profil veya runtime isim aramasi yoktur.
 */
public final class VersionProfiles {
    private static volatile VersionProfile active;

    private VersionProfiles() {
    }

    public static void setActive(VersionProfile profile) {
        active = profile;
    }

    public static VersionProfile active() {
        return active;
    }

    public static VersionCatalog.Resolved resolve(
        String version,
        String loader,
        String mapping
    ) {
        return VersionCatalog.resolve(version, loader, mapping);
    }

    public static VersionProfile select(
        String version,
        String loader,
        String mapping
    ) {
        return VersionCatalog.resolve(version, loader, mapping).profile;
    }

    public static List<VersionProfile> candidates(String version) {
        return VersionCatalog.candidates(version);
    }
}
