package dev.lavalink.youtube.clients;

import com.sedmelluq.discord.lavaplayer.tools.DataFormatTools;
import com.sedmelluq.discord.lavaplayer.tools.JsonBrowser;
import com.sedmelluq.discord.lavaplayer.tools.Units;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import com.sedmelluq.discord.lavaplayer.track.AudioItem;
import com.sedmelluq.discord.lavaplayer.track.AudioReference;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.BasicAudioPlaylist;
import dev.lavalink.youtube.OptionDisabledException;
import dev.lavalink.youtube.YoutubeAudioSourceManager;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public class MWeb extends Web {
    public static ClientConfig BASE_CONFIG = new ClientConfig()
        .withClientName("MWEB")
        .withClientField("clientVersion", "2.20260115.01.00")
        .withClientField("hl", "en")
        .withUserAgent("Mozilla/5.0 (iPad; CPU OS 16_7_10 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1,gzip(gfe)");

    public MWeb() {
        super();
    }

    public MWeb(@NotNull ClientOptions options) {
        super(options);
    }

    @Override
    @NotNull
    public ClientConfig getBaseClientConfig(@NotNull HttpInterface httpInterface) {
        return BASE_CONFIG.copy();
    }

    @Override
    protected String getConfigVisitorData() {
        return BASE_CONFIG.getVisitorData();
    }

    @Override
    public String getPlayerParams() {
        return null;
    }

    @Override
    @NotNull
    protected List<AudioTrack> extractSearchResults(@NotNull YoutubeAudioSourceManager source,
                                                    @NotNull JsonBrowser json) {
        return json.get("contents")
            .get("sectionListRenderer")
            .get("contents")
            .values() // .index(0)
            .stream()
            .flatMap(item -> item.get("itemSectionRenderer").get("contents").values().stream()) // actual results
            .map(item -> extractAudioTrack(item.get("videoWithContextRenderer"), source))
            .filter(Objects::nonNull)
            .collect(Collectors.toList());
    }

    @Override
    @NotNull
    protected JsonBrowser extractMixPlaylistData(@NotNull JsonBrowser json) {
        return json.get("contents")
            .get("singleColumnWatchNextResults")
            .get("playlist")
            .get("playlist");
    }

    @Override
    protected String extractPlaylistName(@NotNull JsonBrowser json) {
        String pageTitle = json.get("header")
            .get("pageHeaderRenderer")
            .get("pageTitle")
            .text();

        if (pageTitle != null) {
            return pageTitle;
        }

        return json.get("header")
            .get("pageHeaderRenderer")
            .get("content")
            .get("pageHeaderViewModel")
            .get("title")
            .get("dynamicTextViewModel")
            .get("text")
            .get("content")
            .text();
    }

    @Override
    @NotNull
    protected JsonBrowser extractPlaylistVideoList(@NotNull JsonBrowser json) {
        JsonBrowser itemSection = json.get("contents")
            .get("singleColumnBrowseResultsRenderer")
            .get("tabs")
            .index(0)
            .get("tabRenderer")
            .get("content")
            .get("sectionListRenderer")
            .get("contents")
            .index(0)
            .get("itemSectionRenderer");

        JsonBrowser legacyVideoList = itemSection
            .get("contents")
            .index(0)
            .get("playlistVideoListRenderer");

        if (!legacyVideoList.isNull()) {
            return legacyVideoList;
        }

        return itemSection.get("contents");
    }

    @Override
    protected void extractPlaylistTracks(@NotNull JsonBrowser json,
                                         @NotNull List<AudioTrack> tracks,
                                         @NotNull YoutubeAudioSourceManager source) {
        super.extractPlaylistTracks(json, tracks, source);

        if (!tracks.isEmpty()) {
            return;
        }

        JsonBrowser contents = json.get("contents");
        if (contents.isNull()) {
            contents = json;
        }

        if (contents.isNull()) {
            return;
        }

        for (JsonBrowser track : contents.values()) {
            JsonBrowser item = track.get("lockupViewModel");
            if (item.isNull() || !"LOCKUP_CONTENT_TYPE_VIDEO".equals(item.get("contentType").text())) {
                continue;
            }

            String videoId = item.get("contentId").text();
            JsonBrowser metadata = item.get("metadata").get("lockupMetadataViewModel");
            String title = metadata.get("title").get("content").text();
            String author = metadata
                .get("metadata")
                .get("contentMetadataViewModel")
                .get("metadataRows")
                .index(0)
                .get("metadataParts")
                .index(0)
                .get("text")
                .get("content")
                .text();
            String lengthText = item
                .get("contentImage")
                .get("thumbnailViewModel")
                .get("overlays")
                .index(0)
                .get("thumbnailBottomOverlayViewModel")
                .get("badges")
                .index(0)
                .get("thumbnailBadgeViewModel")
                .get("text")
                .text();

            if (videoId == null || title == null) {
                continue;
            }

            long duration = lengthText == null
                ? Units.DURATION_MS_UNKNOWN
                : DataFormatTools.durationTextToMillis(lengthText);
            tracks.add(buildAudioTrack(
                source,
                item,
                title,
                firstNonEmpty(author, "Unknown artist"),
                duration,
                videoId,
                false
            ));
        }
    }

    @Override
    @NotNull
    public String getIdentifier() {
        return BASE_CONFIG.getName();
    }
}
