package dev.lavalink.youtube.clients;

import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException.Severity;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import com.sedmelluq.discord.lavaplayer.track.AudioItem;
import dev.lavalink.youtube.CannotBeLoaded;
import dev.lavalink.youtube.YoutubeAudioSourceManager;
import dev.lavalink.youtube.YoutubeSource;
import dev.lavalink.youtube.clients.skeleton.StreamingNonMusicClient;
import dev.lavalink.youtube.pot.PoTokenProvider;
import dev.lavalink.youtube.pot.PoTokenResult;
import org.apache.http.client.utils.URIBuilder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;

public class Tv extends StreamingNonMusicClient {
    private static final Logger log = LoggerFactory.getLogger(Tv.class);

    public static ClientConfig BASE_CONFIG = new ClientConfig()
        .withClientName("TVHTML5")
        .withUserAgent("Mozilla/5.0 (ChromiumStylePlatform) Cobalt/Version")
        .withClientField("clientVersion", "7.20250319.10.00");

    protected ClientOptions options;

    public Tv() {
        this(ClientOptions.DEFAULT);
    }

    public Tv(@NotNull ClientOptions options) {
        this.options = options;
    }

    @Override
    @NotNull
    protected ClientConfig getBaseClientConfig(@NotNull HttpInterface httpInterface) {
        return BASE_CONFIG.copy();
    }

    @Override
    @NotNull
    public String getPlayerParams() {
        return WEB_PLAYER_PARAMS;
    }

    @Override
    @NotNull
    public ClientOptions getOptions() {
        return this.options;
    }

    @Override
    public boolean canHandleRequest(@NotNull String identifier) {
        return super.canHandleRequest(identifier);
    }

    @Override
    public boolean supportsOAuth() {
        return true;
    }

    @Override
    protected boolean supportsExternalPlayerPoToken() {
        return true;
    }

    @Override
    @NotNull
    public URI transformPlaybackUri(@NotNull HttpInterface httpInterface,
                                    @NotNull URI originalUri,
                                    @NotNull URI resolvedPlaybackUri,
                                    @NotNull String videoId) {
        PoTokenProvider provider = YoutubeSource.getPoTokenProvider();

        if (provider == null) {
            return resolvedPlaybackUri;
        }

        try {
            PoTokenResult result = provider.fetchToken(
                videoId,
                getIdentifier(),
                BASE_CONFIG.getVisitorData(),
                PoTokenProvider.TOKEN_TYPE_GVS,
                getRoutePlannerAddress(httpInterface)
            );

            if (result == null || result.poToken == null) {
                return resolvedPlaybackUri;
            }

            URIBuilder builder = new URIBuilder(resolvedPlaybackUri);
            builder.addParameter("pot", result.poToken);
            log.info("Applied GVS PO token to OAuth TV format URL videoId={} client={}",
                videoId, getIdentifier());
            return builder.build();
        } catch (URISyntaxException e) {
            log.warn("Failed to apply GVS PO token to OAuth TV format URL videoId={} client={}",
                videoId, getIdentifier());
        } catch (Exception e) {
            log.warn("External PO token provider failed for OAuth TV GVS request videoId={} client={}, falling back.",
                videoId, getIdentifier(), e);
        }

        return resolvedPlaybackUri;
    }

    @Override
    @NotNull
    public String getIdentifier() {
        return BASE_CONFIG.getName();
    }

    @Override
    public AudioItem loadPlaylist(@NotNull YoutubeAudioSourceManager source,
                                  @NotNull HttpInterface httpInterface,
                                  @NotNull String playlistId,
                                  @Nullable String selectedVideoId) {
        throw new FriendlyException("This client cannot load playlists", Severity.COMMON,
            new RuntimeException("TVHTML5 cannot be used to load playlists"));
    }

    @Override
    public AudioItem loadVideo(@NotNull YoutubeAudioSourceManager source, @NotNull HttpInterface httpInterface, @NotNull String videoId) throws CannotBeLoaded, IOException {
        return super.loadVideo(source, httpInterface, videoId);
    }

    @Override
    public AudioItem loadMix(@NotNull YoutubeAudioSourceManager source, @NotNull HttpInterface httpInterface, @NotNull String mixId, @Nullable String selectedVideoId) {
        throw new FriendlyException("This client cannot load mixes", Severity.COMMON,
            new RuntimeException("TVHTML5 cannot be used to load mixes"));
    }

    @Override
    public AudioItem loadSearch(@NotNull YoutubeAudioSourceManager source, @NotNull HttpInterface httpInterface, @NotNull String searchQuery) {
        throw new FriendlyException("This client cannot search", Severity.COMMON,
            new RuntimeException("TVHTML5 cannot be used to search"));
    }
}
