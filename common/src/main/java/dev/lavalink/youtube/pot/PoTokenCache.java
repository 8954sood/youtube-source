package dev.lavalink.youtube.pot;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe cache for {@link PoTokenResult} entries, keyed by
 * {@code videoId + clientName + tokenType + visitorData + sourceAddress}.
 *
 * <p>An entry expires at {@link PoTokenResult#expiresAtEpochMs} when that is greater than zero,
 * otherwise at insertion time plus the configured TTL. Failed lookups are never cached — only
 * successful results are ever {@link #put(String, String, String, String, PoTokenResult) put}.
 */
public class PoTokenCache {
    private final ConcurrentHashMap<String, CachedEntry> cache = new ConcurrentHashMap<>();
    private final long cacheTtlMs;

    public PoTokenCache(long cacheTtlSeconds) {
        this.cacheTtlMs = cacheTtlSeconds * 1000L;
    }

    @Nullable
    public PoTokenResult get(@NotNull String videoId,
                             @NotNull String clientName,
                             @NotNull String tokenType,
                             @Nullable String visitorData) {
        return get(videoId, clientName, tokenType, visitorData, null);
    }

    @Nullable
    public PoTokenResult get(@NotNull String videoId,
                             @NotNull String clientName,
                             @NotNull String tokenType,
                             @Nullable String visitorData,
                             @Nullable String sourceAddress) {
        String key = makeKey(videoId, clientName, tokenType, visitorData, sourceAddress);
        CachedEntry entry = cache.get(key);

        if (entry == null) {
            return null;
        }

        if (System.currentTimeMillis() >= entry.expiresAtMs) {
            cache.remove(key, entry);
            return null;
        }

        return entry.result;
    }

    public void put(@NotNull String videoId,
                    @NotNull String clientName,
                    @NotNull String tokenType,
                    @Nullable String visitorData,
                    @NotNull PoTokenResult result) {
        put(videoId, clientName, tokenType, visitorData, null, result);
    }

    public void put(@NotNull String videoId,
                    @NotNull String clientName,
                    @NotNull String tokenType,
                    @Nullable String visitorData,
                    @Nullable String sourceAddress,
                    @NotNull PoTokenResult result) {
        long expiresAtMs = result.expiresAtEpochMs > 0
            ? result.expiresAtEpochMs
            : System.currentTimeMillis() + cacheTtlMs;
        cache.put(makeKey(videoId, clientName, tokenType, visitorData, sourceAddress),
            new CachedEntry(result, expiresAtMs));
    }

    private static String makeKey(String videoId,
                                  String clientName,
                                  String tokenType,
                                  String visitorData,
                                  String sourceAddress) {
        String normalizedTokenType = normalizeTokenType(tokenType);
        return videoId.trim() + "|" + normalizeClientName(clientName) + "|" + normalizedTokenType + "|"
            + normalizeNullable(visitorData) + "|"
            + normalizeSourceAddress(normalizedTokenType, sourceAddress);
    }

    static String normalizeClientName(String clientName) {
        String normalized = clientName.trim().toUpperCase(Locale.ROOT).replace('-', '_');

        switch (normalized) {
            case "TV":
            case "TV_HTML5":
                return "TVHTML5";
            case "M_WEB":
                return "MWEB";
            default:
                return normalized;
        }
    }

    static String normalizeTokenType(String tokenType) {
        return tokenType.trim().toLowerCase(Locale.ROOT);
    }

    static String keyFingerprint(String videoId,
                                 String clientName,
                                 String tokenType,
                                 String visitorData,
                                 String sourceAddress) {
        String key = makeKey(videoId, clientName, tokenType, visitorData, sourceAddress);

        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            StringBuilder fingerprint = new StringBuilder(12);

            for (int i = 0; i < 6; i++) {
                fingerprint.append(String.format(Locale.ROOT, "%02x", digest[i]));
            }

            return fingerprint.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static String normalizeNullable(String value) {
        return value != null ? value.trim() : "";
    }

    private static String normalizeSourceAddress(String tokenType, String sourceAddress) {
        // Player tokens are video/client-bound. Keeping NanoIpRoutePlanner's per-request IPv6
        // address in this key prevents direct-load prewarming from being reused by playback.
        if (PoTokenProvider.TOKEN_TYPE_PLAYER.equals(tokenType)) {
            return "";
        }

        return normalizeNullable(sourceAddress).toLowerCase(Locale.ROOT);
    }

    private static final class CachedEntry {
        final PoTokenResult result;
        final long expiresAtMs;

        CachedEntry(PoTokenResult result, long expiresAtMs) {
            this.result = result;
            this.expiresAtMs = expiresAtMs;
        }
    }
}
