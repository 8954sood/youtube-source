package dev.lavalink.youtube.pot;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Hook for supplying PO Tokens on a per-video basis.
 *
 * <p>YouTube increasingly binds the GVS PO Token to {@code videoId + visitorData + client},
 * so a single static token is no longer sufficient. Implementations of this interface are
 * consulted at the two injection points (the {@code /player} request body and the final
 * googlevideo stream URL) and may return a freshly-minted token for the given video.
 *
 * <p>Implementations must be safe to call concurrently and must never throw fatal errors
 * that would prevent playback from falling back to existing behaviour. Returning
 * {@code null} signals "not handled" and the caller falls through to the static token path.
 */
@FunctionalInterface
public interface PoTokenProvider {
    /** Token used in the {@code /player} Innertube request body ({@code serviceIntegrityDimensions.poToken}). */
    String TOKEN_TYPE_PLAYER = "player";

    /** Token appended as the {@code pot} query parameter on the googlevideo (GVS) stream URL. */
    String TOKEN_TYPE_GVS = "gvs";

    /**
     * Fetch a PO Token for the given video and client.
     *
     * @param videoId     the YouTube video id.
     * @param clientName  the client identifier (e.g. {@code WEB}, {@code WEB_EMBEDDED_PLAYER}).
     * @param visitorData the visitorData currently configured for this client, used as a seed/hint.
     *                    May be {@code null}.
     * @param tokenType   one of {@link #TOKEN_TYPE_PLAYER} or {@link #TOKEN_TYPE_GVS}.
     * @return the token result, or {@code null} to fall back to existing behaviour.
     */
    @Nullable
    PoTokenResult fetchToken(@NotNull String videoId,
                             @NotNull String clientName,
                             @Nullable String visitorData,
                             @NotNull String tokenType);

    /**
     * Fetch a PO Token while preserving the local address selected by an HTTP route planner.
     *
     * <p>The default implementation keeps existing providers source-compatible. Providers that
     * perform network requests should override this method and bind those requests to
     * {@code sourceAddress} when it is present.
     *
     * @param sourceAddress the selected local IP address, or {@code null} when no route planner
     *                      address has been selected yet.
     */
    @Nullable
    default PoTokenResult fetchToken(@NotNull String videoId,
                                     @NotNull String clientName,
                                     @Nullable String visitorData,
                                     @NotNull String tokenType,
                                     @Nullable String sourceAddress) {
        return fetchToken(videoId, clientName, visitorData, tokenType);
    }
}
