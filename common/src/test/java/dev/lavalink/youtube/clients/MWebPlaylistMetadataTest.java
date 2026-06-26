package dev.lavalink.youtube.clients;

import com.sedmelluq.discord.lavaplayer.tools.JsonBrowser;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import dev.lavalink.youtube.YoutubeAudioSourceManager;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MWebPlaylistMetadataTest {
    @Test
    void extractsLockupViewModelPlaylistTracks() throws Exception {
        ExposedMWeb client = new ExposedMWeb();
        YoutubeAudioSourceManager source = new YoutubeAudioSourceManager();
        JsonBrowser json = JsonBrowser.parse(
            "{"
                + "\"contents\":[{\"lockupViewModel\":{"
                + "\"contentId\":\"siNFnlqtd8M\","
                + "\"contentType\":\"LOCKUP_CONTENT_TYPE_VIDEO\","
                + "\"contentImage\":{\"thumbnailViewModel\":{\"overlays\":[{\"thumbnailBottomOverlayViewModel\":{\"badges\":[{\"thumbnailBadgeViewModel\":{\"text\":\"3:50\"}}]}}]}},"
                + "\"metadata\":{\"lockupMetadataViewModel\":{"
                + "\"title\":{\"content\":\"又三郎\"},"
                + "\"metadata\":{\"contentMetadataViewModel\":{\"metadataRows\":[{\"metadataParts\":[{\"text\":{\"content\":\"ヨルシカ / n-buna Official\"}}]}]}}"
                + "}}"
                + "}}]}"
                + "}"
        );
        List<AudioTrack> tracks = new ArrayList<>();

        client.extract(json, tracks, source);

        assertEquals(1, tracks.size());
        assertEquals("siNFnlqtd8M", tracks.get(0).getInfo().identifier);
        assertEquals("又三郎", tracks.get(0).getInfo().title);
        assertEquals("ヨルシカ / n-buna Official", tracks.get(0).getInfo().author);
        assertEquals(230000, tracks.get(0).getInfo().length);
    }

    @Test
    void extractsPageHeaderViewModelPlaylistName() throws Exception {
        ExposedMWeb client = new ExposedMWeb();
        JsonBrowser json = JsonBrowser.parse(
            "{"
                + "\"header\":{\"pageHeaderRenderer\":{\"content\":{\"pageHeaderViewModel\":{\"title\":{\"dynamicTextViewModel\":{\"text\":{\"content\":\"幻燈\"}}}}}}}"
                + "}"
        );

        assertEquals("幻燈", client.name(json));
    }

    static class ExposedMWeb extends MWeb {
        void extract(JsonBrowser json, List<AudioTrack> tracks, YoutubeAudioSourceManager source) {
            extractPlaylistTracks(json, tracks, source);
        }

        String name(JsonBrowser json) {
            return extractPlaylistName(json);
        }
    }
}
