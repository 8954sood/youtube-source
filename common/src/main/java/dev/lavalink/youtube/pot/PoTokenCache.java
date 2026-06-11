package dev.lavalink.youtube.pot;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

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
        return videoId + "|" + clientName + "|" + tokenType + "|"
            + (visitorData != null ? visitorData : "") + "|"
            + (sourceAddress != null ? sourceAddress : "");
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
