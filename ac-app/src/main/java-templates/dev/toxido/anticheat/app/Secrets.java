package dev.toxido.anticheat.app;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Build-time uretilen istemci anahtarlari.
 *
 * <p>Degerler Maven filtresi ile Base64 olarak gomulur; kaynak depoda gercek
 * anahtar tutulmaz. Obfuscation bu sabitleri ve cozucu kodu gizler.</p>
 */
final class Secrets {
    private static final String API_KEY_B64 = "${api.key.b64}";
    private static final String API_SECRET_B64 = "${api.secret.b64}";

    private Secrets() {
    }

    static String apiKey() {
        return decode(API_KEY_B64);
    }

    static String apiSecret() {
        return decode(API_SECRET_B64);
    }

    private static String decode(String value) {
        if (value == null || value.isBlank() || value.startsWith("${")) {
            return "";
        }
        return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
    }
}
