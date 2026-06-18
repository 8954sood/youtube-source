package dev.lavalink.youtube.track;

import dev.lavalink.youtube.UrlTools;
import dev.lavalink.youtube.track.format.StreamFormat;
import org.jetbrains.annotations.NotNull;

import java.net.URI;

public final class CachedPlaybackFormat {
    public final StreamFormat format;
    public final URI signedUrl;
    public final String clientIdentifier;

    public CachedPlaybackFormat(@NotNull StreamFormat format,
                                @NotNull URI signedUrl,
                                @NotNull String clientIdentifier) {
        this.format = format;
        this.signedUrl = signedUrl;
        this.clientIdentifier = clientIdentifier;
    }

    public boolean isExpired() {
        String expire = UrlTools.getUrlInfo(signedUrl.toString(), true).parameters.get("expire");

        if (expire == null) {
            return false;
        }

        long expiresAtMs = Long.parseLong(expire) * 1000;
        return System.currentTimeMillis() >= expiresAtMs - 30_000;
    }
}
