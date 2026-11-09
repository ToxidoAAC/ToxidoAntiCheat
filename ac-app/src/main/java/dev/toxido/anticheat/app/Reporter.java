package dev.toxido.anticheat.app;

/**
 * Arayuzden bagimsiz durum raporlayici. Terminal splash'i sadece animasyon
 * gosterir; servis durumunu bu arayuz uzerinden dosyaya yazar.
 */
public interface Reporter {
    void status(String status, String detail);

    void log(String message);
}
