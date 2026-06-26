package dev.lavalink.youtube.clients.skeleton;

import com.sedmelluq.discord.lavaplayer.tools.JsonBrowser;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import com.sedmelluq.discord.lavaplayer.track.AudioItem;
import dev.lavalink.youtube.YoutubeAudioSourceManager;
import dev.lavalink.youtube.clients.ClientConfig;
import dev.lavalink.youtube.track.format.TrackFormats;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class YoutubeDirectVideoMetadataTest {
    @Test
    void directVideoTitleUsesVideoDetailsWhenPresent() throws Exception {
        ExposedClient client = new ExposedClient();
        JsonBrowser json = JsonBrowser.parse(
            "{"
                + "\"videoDetails\":{\"title\":\"Video details title\"},"
                + "\"microformat\":{\"playerMicroformatRenderer\":{\"title\":{\"simpleText\":\"Microformat title\"}}}"
                + "}"
        );

        assertEquals(
            "Video details title",
            client.title(json.get("videoDetails"), json.get("microformat").get("playerMicroformatRenderer"))
        );
    }

    @Test
    void directVideoTitleFallsBackToMicroformatSimpleText() throws Exception {
        ExposedClient client = new ExposedClient();
        JsonBrowser json = JsonBrowser.parse(
            "{"
                + "\"videoDetails\":{\"title\":\"\"},"
                + "\"microformat\":{\"playerMicroformatRenderer\":{\"title\":{\"simpleText\":\"Microformat title\"}}}"
                + "}"
        );

        assertEquals(
            "Microformat title",
            client.title(json.get("videoDetails"), json.get("microformat").get("playerMicroformatRenderer"))
        );
    }

    @Test
    void directVideoTitleFallsBackToMicroformatRuns() throws Exception {
        ExposedClient client = new ExposedClient();
        JsonBrowser json = JsonBrowser.parse(
            "{"
                + "\"videoDetails\":{},"
                + "\"microformat\":{\"playerMicroformatRenderer\":{\"title\":{\"runs\":[{\"text\":\"Run title\"}]}}}"
                + "}"
        );

        assertEquals(
            "Run title",
            client.title(json.get("videoDetails"), json.get("microformat").get("playerMicroformatRenderer"))
        );
    }

    @Test
    void directVideoTitleDoesNotTreatUnknownTitleAsValidMetadata() throws Exception {
        ExposedClient client = new ExposedClient();
        JsonBrowser json = JsonBrowser.parse(
            "{"
                + "\"videoDetails\":{\"title\":\"Unknown title\"},"
                + "\"microformat\":{\"playerMicroformatRenderer\":{}}"
                + "}"
        );

        assertNull(client.title(
            json.get("videoDetails"),
            json.get("microformat").get("playerMicroformatRenderer")
        ));
    }

    @Test
    void directVideoAuthorKeepsExistingFallbackOrderIncludingOwnerProfileUrl() throws Exception {
        ExposedClient client = new ExposedClient();
        JsonBrowser json = JsonBrowser.parse(
            "{"
                + "\"videoDetails\":{},"
                + "\"microformat\":{\"playerMicroformatRenderer\":{\"ownerProfileUrl\":\"https://www.youtube.com/@channel\"}}"
                + "}"
        );

        assertEquals(
            "https://www.youtube.com/@channel",
            client.author(json.get("videoDetails"), json.get("microformat").get("playerMicroformatRenderer"))
        );
    }

    static class ExposedClient extends NonMusicClient {
        String title(JsonBrowser videoDetails, JsonBrowser microformat) {
            return extractDirectVideoTitle(videoDetails, microformat);
        }

        String author(JsonBrowser videoDetails, JsonBrowser microformat) {
            return extractDirectVideoAuthor(videoDetails, microformat);
        }

        @Override
        protected ClientConfig getBaseClientConfig(HttpInterface httpInterface) {
            return new ClientConfig().withClientName("TEST");
        }

        @Override
        public String getIdentifier() {
            return "TEST";
        }

        @Override
        public String getPlayerParams() {
            return null;
        }

        @Override
        public void setPlaylistPageCount(int count) {
        }

        @Override
        public TrackFormats loadFormats(YoutubeAudioSourceManager source,
                                        HttpInterface httpInterface,
                                        String videoId) {
            return null;
        }

        @Override
        public AudioItem loadVideo(YoutubeAudioSourceManager source,
                                   HttpInterface httpInterface,
                                   String videoId) {
            return null;
        }
    }
}
