package dev.toxido.anticheat.agent;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * {@code version-catalog.json} dosyasini ajanin icine gomer ve calisan
 * surum/loader icin hazir hook zincirini secer.
 *
 * <p>Katalog bilincli olarak tek kaynaktir: eski sabit profiller ve
 * {@code VanillaProfiles} haritasi kaldirildi. Dosya bulunamaz, parse
 * edilemez veya istenen satir eksikse cozumleme sessizce yanlis profile
 * kaymaz; nedeni capability panosuna tasinir.</p>
 */
public final class VersionCatalog {
    public static final class Resolved {
        public final VersionProfile profile;
        public final String reason;
        public final String missing;
        public final String version;
        public final String loader;

        Resolved(
            VersionProfile profile,
            String reason,
            String missing,
            String version,
            String loader
        ) {
            this.profile = profile;
            this.reason = reason == null ? "unknown" : reason;
            this.missing = missing == null ? "" : missing;
            this.version = version == null ? "" : version;
            this.loader = loader == null ? "" : loader;
        }

        public boolean ok() {
            return profile != null;
        }
    }

    private static final String RESOURCE = "/version-catalog.json";

    private static final class Snapshot {
        final Map<String, Map<String, VersionProfile>> versions;
        final Map<String, List<VersionProfile>> byVersion;
        final Set<String> emptyRows;
        final String error;

        Snapshot(
            Map<String, Map<String, VersionProfile>> versions,
            Map<String, List<VersionProfile>> byVersion,
            Set<String> emptyRows,
            String error
        ) {
            this.versions = versions;
            this.byVersion = byVersion;
            this.emptyRows = emptyRows;
            this.error = error;
        }
    }

    private static volatile Snapshot snapshot;
    private static final Object LOCK = new Object();

    private VersionCatalog() {
    }

    public static void warmUp() {
        snapshot();
    }

    public static Resolved resolve(
        String version,
        String loader,
        String mapping
    ) {
        Snapshot data = snapshot();
        if (data.error != null) {
            return failure(data.error, version, loader);
        }
        String normalizedVersion = normalizeVersion(data, version);
        if (normalizedVersion.isEmpty() || "unknown".equals(normalizedVersion)) {
            return failure("version_unknown", normalizedVersion, loader);
        }
        Map<String, VersionProfile> rows = data.versions.get(normalizedVersion);
        if (rows == null || rows.isEmpty()) {
            return failure(
                "version_not_in_catalog:" + normalizedVersion,
                normalizedVersion,
                loader
            );
        }

        String normalizedLoader = normalizeLoader(loader, mapping);
        String reason = "ok";
        if (isBlank(loader) || "unknown".equalsIgnoreCase(loader.trim())) {
            reason = "ok:loader_tahmini=" + normalizedLoader;
        }

        VersionProfile profile = rows.get(normalizedLoader);
        if (profile == null && "neoforge".equals(normalizedLoader)) {
            profile = rows.get("forge");
            if (profile != null) {
                reason = reason + ":neoforge->forge";
            }
        }
        if (profile == null && "vanilla".equals(normalizedLoader)
            && mapping != null && mapping.toLowerCase(Locale.ROOT).contains("intermediary")) {
            profile = rows.get("fabric");
            if (profile != null) {
                reason = reason + ":intermediary->fabric";
            }
        }
        if (profile == null) {
            String key = normalizedVersion + "/" + normalizedLoader;
            String failure = data.emptyRows.contains(key)
                ? "catalog_row_empty:" + key
                : "loader_not_in_catalog:" + key;
            return failure(failure, normalizedVersion, normalizedLoader);
        }
        if (!profile.complete()) {
            return new Resolved(
                null,
                "catalog_incomplete:" + profile.id,
                missingOf(profile),
                normalizedVersion,
                normalizedLoader
            );
        }
        return new Resolved(
            profile,
            reason,
            missingOf(profile),
            normalizedVersion,
            normalizedLoader
        );
    }

    /** Ayni surumun dolu tum loader satirlari (obfuscated sinif aramasi icin). */
    public static List<VersionProfile> candidates(String version) {
        Snapshot data = snapshot();
        if (data.error != null) {
            return Collections.emptyList();
        }
        List<VersionProfile> rows = data.byVersion.get(
            normalizeVersion(data, version)
        );
        return rows == null ? Collections.emptyList() : rows;
    }

    /** Teshis: katalogdaki surum/loader satirlarini ozetler. */
    public static String describe() {
        Snapshot data = snapshot();
        if (data.error != null) {
            return data.error;
        }
        return "surum=" + data.versions.size()
            + " satir=" + data.byVersion.values().stream()
                .mapToInt(List::size).sum()
            + " bos=" + data.emptyRows.size();
    }

    private static Resolved failure(
        String reason,
        String version,
        String loader
    ) {
        return new Resolved(null, reason, "", version, loader);
    }

    private static Snapshot snapshot() {
        Snapshot current = snapshot;
        if (current != null) {
            return current;
        }
        synchronized (LOCK) {
            if (snapshot == null) {
                snapshot = load();
            }
            return snapshot;
        }
    }

    private static Snapshot load() {
        InputStream stream = VersionCatalog.class.getResourceAsStream(RESOURCE);
        if (stream == null) {
            return error("catalog_resource_missing");
        }
        try (InputStream input = stream) {
            String text = new String(readAll(input), StandardCharsets.UTF_8);
            Object parsed = new Parser(text).parseDocument();
            Map<String, Object> root = object(parsed, "kok");
            Map<String, Object> versions = object(root.get("versions"), "versions");
            Map<String, Map<String, VersionProfile>> byVersion =
                new LinkedHashMap<>();
            Map<String, List<VersionProfile>> candidates =
                new LinkedHashMap<>();
            Set<String> emptyRows = new LinkedHashSet<>();

            for (Map.Entry<String, Object> versionEntry : versions.entrySet()) {
                String version = normalizeVersionKey(versionEntry.getKey());
                Map<String, Object> loaders = object(
                    versionEntry.getValue(), "versions." + versionEntry.getKey()
                );
                Map<String, VersionProfile> rows = new LinkedHashMap<>();
                List<VersionProfile> rowList = new ArrayList<>();
                for (Map.Entry<String, Object> loaderEntry : loaders.entrySet()) {
                    String loader = normalizeLoaderKey(loaderEntry.getKey());
                    Map<String, Object> row = object(
                        loaderEntry.getValue(),
                        versionEntry.getKey() + "." + loaderEntry.getKey()
                    );
                    if (row.isEmpty()) {
                        emptyRows.add(version + "/" + loader);
                        continue;
                    }
                    VersionProfile profile = profile(version, loader, row);
                    rows.put(loader, profile);
                    rowList.add(profile);
                }
                byVersion.put(version, rows);
                candidates.put(version, rowList);
            }
            if (byVersion.isEmpty()) {
                return error("catalog_empty");
            }
            return new Snapshot(byVersion, candidates, emptyRows, null);
        } catch (Throwable throwable) {
            String message = throwable.getMessage();
            return error(
                "catalog_parse_error:"
                    + (message == null || message.isEmpty()
                        ? throwable.getClass().getSimpleName()
                        : message)
            );
        }
    }

    private static Snapshot error(String reason) {
        return new Snapshot(
            Collections.emptyMap(),
            Collections.emptyMap(),
            Collections.emptySet(),
            reason
        );
    }

    private static VersionProfile profile(
        String version,
        String loader,
        Map<String, Object> row
    ) {
        String id = string(row, "id");
        if (isBlank(id)) {
            id = version + "-" + loader;
        }
        String pipelineHandler = string(row, "pipelineHandler");
        if (isBlank(pipelineHandler)) {
            pipelineHandler = "packet_handler";
        }
        String screenAccess = string(row, "screenAccess");
        if (isBlank(screenAccess)) {
            screenAccess = "field";
        }
        return new VersionProfile(
            id,
            className(row, "minecraftClass"),
            string(row, "worldField"),
            string(row, "networkHandlerField"),
            className(row, "networkHandlerClass"),
            string(row, "connectionMethod"),
            className(row, "connectionClass"),
            string(row, "channelField"),
            className(row, "attackClass"),
            string(row, "attackMethod"),
            className(row, "packetClass"),
            className(row, "localPlayerClass"),
            className(row, "screenClass"),
            string(row, "screenField"),
            className(row, "optionsClass"),
            string(row, "optionsField"),
            className(row, "keyMappingClass"),
            string(row, "attackKeyField"),
            string(row, "keyNameField"),
            string(row, "keyDownMethod"),
            className(row, "livingClass"),
            className(row, "playerEntityClass"),
            className(row, "armorStandClass"),
            className(row, "entityClass"),
            string(row, "packetEntityField"),
            string(row, "packetActionField"),
            string(row, "packetAttackActionField"),
            version,
            loader,
            string(row, "mapping"),
            string(row, "source"),
            string(row, "connectionField"),
            string(row, "minecraftConnectionMethod"),
            string(row, "channelMethod"),
            pipelineHandler,
            string(row, "packetKind"),
            string(row, "entityIdAccessor"),
            screenAccess,
            string(row, "guiField"),
            string(row, "guiScreenField"),
            bool(row, "verified"),
            bool(row, "loaderVerified"),
            join(row.get("notes"), "; "),
            join(row.get("missing"), ",")
        );
    }

    private static String normalizeVersion(Snapshot data, String value) {
        String version = normalizeVersionKey(value);
        if (data.versions.containsKey(version)) {
            return version;
        }
        int dash = version.indexOf('-');
        if (dash > 0) {
            String base = version.substring(0, dash);
            if (data.versions.containsKey(base)) {
                return base;
            }
        }
        return version;
    }

    private static String normalizeVersionKey(String value) {
        if (value == null) {
            return "";
        }
        String version = value.trim().toLowerCase(Locale.ROOT);
        if (version.length() > 1
            && version.charAt(0) == 'v'
            && Character.isDigit(version.charAt(1))) {
            version = version.substring(1);
        }
        return version;
    }

    private static String normalizeLoaderKey(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizeLoader(String loader, String mapping) {
        String value = normalizeLoaderKey(loader);
        if (!value.isEmpty() && !"unknown".equals(value)) {
            if (value.contains("quilt")) {
                return "fabric";
            }
            if (value.contains("neoforge")) {
                return "neoforge";
            }
            if (value.contains("forge")) {
                return "forge";
            }
            if (value.contains("fabric")) {
                return "fabric";
            }
            if (value.contains("vanilla") || value.contains("mojang")) {
                return "vanilla";
            }
        }

        String map = normalizeLoaderKey(mapping);
        if (map.contains("intermediary")) {
            return "fabric";
        }
        if (map.contains("srg")) {
            return "forge";
        }
        if (map.contains("obfuscated")) {
            return "vanilla";
        }
        return "vanilla";
    }

    private static String missingOf(VersionProfile profile) {
        String required = profile.missingRequired();
        String documented = profile.missing == null ? "" : profile.missing.trim();
        if (documented.isEmpty()) {
            return required;
        }
        if (required.isEmpty()) {
            return documented;
        }
        return documented + "," + required;
    }

    private static String string(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value instanceof String text) {
            String trimmed = text.trim();
            return trimmed.isEmpty() ? null : trimmed;
        }
        return null;
    }

    private static String className(Map<String, Object> row, String key) {
        String value = string(row, key);
        return value == null ? null : value.replace('/', '.');
    }

    private static boolean bool(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value instanceof Boolean flag) {
            return flag;
        }
        return value instanceof String text && Boolean.parseBoolean(text);
    }

    private static String join(Object value, String separator) {
        if (!(value instanceof List<?> list)) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (Object item : list) {
            if (item == null) {
                continue;
            }
            String text = String.valueOf(item).trim();
            if (text.isEmpty()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(separator);
            }
            builder.append(text);
        }
        return builder.toString();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value, String where) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException(where + " nesne degil");
        }
        return (Map<String, Object>) map;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static byte[] readAll(InputStream input) throws java.io.IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(256 * 1024);
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) >= 0) {
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    /**
     * Bagimliliksiz kucuk JSON okuyucu. Katalog yalnizca nesne, dizi, metin,
     * sayi, boolean ve null icerir; tam JSON kapsamina gerek yok.
     */
    private static final class Parser {
        private final String text;
        private int position;

        Parser(String text) {
            this.text = text;
        }

        Object parseDocument() {
            Object value = value();
            skipWhitespace();
            if (position < text.length()) {
                throw error("fazla veri");
            }
            return value;
        }

        private Object value() {
            skipWhitespace();
            if (position >= text.length()) {
                throw error("beklenmeyen dosya sonu");
            }
            char current = text.charAt(position);
            switch (current) {
                case '{':
                    return object();
                case '[':
                    return array();
                case '"':
                    return string();
                case 't':
                    expect("true");
                    return Boolean.TRUE;
                case 'f':
                    expect("false");
                    return Boolean.FALSE;
                case 'n':
                    expect("null");
                    return null;
                default:
                    return number();
            }
        }

        private Map<String, Object> object() {
            position++;
            Map<String, Object> result = new LinkedHashMap<>();
            skipWhitespace();
            if (peek('}')) {
                position++;
                return result;
            }
            while (true) {
                skipWhitespace();
                String key = string();
                skipWhitespace();
                if (position >= text.length() || text.charAt(position) != ':') {
                    throw error("':' bekleniyor");
                }
                position++;
                result.put(key, value());
                skipWhitespace();
                if (peek('}')) {
                    position++;
                    return result;
                }
                if (!peek(',')) {
                    throw error("',' veya '}' bekleniyor");
                }
                position++;
            }
        }

        private List<Object> array() {
            position++;
            List<Object> result = new ArrayList<>();
            skipWhitespace();
            if (peek(']')) {
                position++;
                return result;
            }
            while (true) {
                result.add(value());
                skipWhitespace();
                if (peek(']')) {
                    position++;
                    return result;
                }
                if (!peek(',')) {
                    throw error("',' veya ']' bekleniyor");
                }
                position++;
            }
        }

        private String string() {
            if (position >= text.length() || text.charAt(position) != '"') {
                throw error("metin bekleniyor");
            }
            position++;
            StringBuilder builder = new StringBuilder();
            while (position < text.length()) {
                char current = text.charAt(position++);
                if (current == '"') {
                    return builder.toString();
                }
                if (current != '\\') {
                    builder.append(current);
                    continue;
                }
                if (position >= text.length()) {
                    throw error("kacis dizisi yarim");
                }
                char escaped = text.charAt(position++);
                switch (escaped) {
                    case '"':
                    case '\\':
                    case '/':
                        builder.append(escaped);
                        break;
                    case 'b':
                        builder.append('\b');
                        break;
                    case 'f':
                        builder.append('\f');
                        break;
                    case 'n':
                        builder.append('\n');
                        break;
                    case 'r':
                        builder.append('\r');
                        break;
                    case 't':
                        builder.append('\t');
                        break;
                    case 'u':
                        if (position + 4 > text.length()) {
                            throw error("unicode kacisi yarim");
                        }
                        String hex = text.substring(position, position + 4);
                        position += 4;
                        builder.append((char) Integer.parseInt(hex, 16));
                        break;
                    default:
                        throw error("bilinmeyen kacis: \\" + escaped);
                }
            }
            throw error("metin kapanmadi");
        }

        private Object number() {
            int start = position;
            while (position < text.length()) {
                char current = text.charAt(position);
                if ((current >= '0' && current <= '9')
                    || current == '-' || current == '+'
                    || current == '.' || current == 'e' || current == 'E') {
                    position++;
                } else {
                    break;
                }
            }
            String number = text.substring(start, position);
            if (number.isEmpty()) {
                throw error("deger bekleniyor");
            }
            if (number.indexOf('.') < 0 && number.indexOf('e') < 0
                && number.indexOf('E') < 0) {
                try {
                    return Long.parseLong(number);
                } catch (NumberFormatException ignored) {
                    // Cok buyuk tam sayi; asagi duser.
                }
            }
            return Double.parseDouble(number);
        }

        private void expect(String expected) {
            if (!text.startsWith(expected, position)) {
                throw error("'" + expected + "' bekleniyor");
            }
            position += expected.length();
        }

        private boolean peek(char expected) {
            return position < text.length() && text.charAt(position) == expected;
        }

        private void skipWhitespace() {
            while (position < text.length()) {
                char current = text.charAt(position);
                if (current == ' ' || current == '\n'
                    || current == '\r' || current == '\t') {
                    position++;
                } else {
                    break;
                }
            }
        }

        private IllegalArgumentException error(String message) {
            return new IllegalArgumentException(message + " @" + position);
        }
    }
}
