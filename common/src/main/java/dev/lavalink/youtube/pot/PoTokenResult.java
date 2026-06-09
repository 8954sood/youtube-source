package dev.lavalink.youtube.pot;

/**
 * Immutable result returned by a {@link PoTokenProvider}.
 *
 * <p>A poToken is bound to a particular {@code visitorData} (and, for GVS tokens, to a
 * specific videoId/client). The provider must therefore return the {@code visitorData}
 * that the {@code poToken} was minted against so that both can be applied together.
 */
public final class PoTokenResult {
    public final String poToken;
    public final String visitorData;

    /**
     * Absolute expiry time in epoch milliseconds, or {@code 0} when no explicit
     * expiry is supplied (in which case the cache falls back to its configured TTL).
     */
    public final long expiresAtEpochMs;

    public PoTokenResult(String poToken, String visitorData, long expiresAtEpochMs) {
        this.poToken = poToken;
        this.visitorData = visitorData;
        this.expiresAtEpochMs = expiresAtEpochMs;
    }
}
