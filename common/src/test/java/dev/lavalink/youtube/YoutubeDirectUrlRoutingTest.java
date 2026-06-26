package dev.lavalink.youtube;

import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import com.sedmelluq.discord.lavaplayer.track.AudioItem;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.BasicAudioPlaylist;
import dev.lavalink.youtube.clients.ClientOptions;
import dev.lavalink.youtube.clients.skeleton.Client;
import dev.lavalink.youtube.track.format.TrackFormats;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class YoutubeDirectUrlRoutingTest {
    @Test
    void watchUrlRoutesAsDirectVideo() throws Throwable {
        ExposedSourceManager source = new ExposedSourceManager();
        RecordingClient client = new RecordingClient();

        source.route("https://www.youtube.com/watch?v=siNFnlqtd8M", client);

        assertEquals("video", client.lastCall);
        assertEquals("siNFnlqtd8M", client.videoId);
    }

    @Test
    void playlistUrlRoutesAsDirectPlaylist() throws Throwable {
        ExposedSourceManager source = new ExposedSourceManager();
        RecordingClient client = new RecordingClient();

        source.route(
            "https://www.youtube.com/playlist?list=PLUQKJP1sVuNPMJad6pUdbjp2vvU2hGIAp",
            client
        );

        assertEquals("playlist", client.lastCall);
        assertEquals("PLUQKJP1sVuNPMJad6pUdbjp2vvU2hGIAp", client.playlistId);
        assertEquals(null, client.selectedVideoId);
    }

    @Test
    void watchUrlWithListKeepsPlaylistContext() throws Throwable {
        ExposedSourceManager source = new ExposedSourceManager();
        RecordingClient client = new RecordingClient();

        source.route(
            "https://www.youtube.com/watch?v=siNFnlqtd8M&list=PLUQKJP1sVuNPMJad6pUdbjp2vvU2hGIAp",
            client
        );

        assertEquals("playlist", client.lastCall);
        assertEquals("PLUQKJP1sVuNPMJad6pUdbjp2vvU2hGIAp", client.playlistId);
        assertEquals("siNFnlqtd8M", client.selectedVideoId);
    }

    @Test
    void watchUrlWithStartRadioRoutesAsDirectVideo() throws Throwable {
        ExposedSourceManager source = new ExposedSourceManager();
        RecordingClient client = new RecordingClient();

        source.route(
            "https://www.youtube.com/watch?v=5Of2HNJa_gs&list=RD5Of2HNJa_gs&start_radio=1",
            client
        );

        assertEquals("video", client.lastCall);
        assertEquals("5Of2HNJa_gs", client.videoId);
    }

    @Test
    void directPlaylistIdRoutesWhenAllowed() throws Throwable {
        ExposedSourceManager source = new ExposedSourceManager();
        RecordingClient client = new RecordingClient();

        source.route("PLUQKJP1sVuNPMJad6pUdbjp2vvU2hGIAp", client);

        assertEquals("playlist", client.lastCall);
        assertEquals("PLUQKJP1sVuNPMJad6pUdbjp2vvU2hGIAp", client.playlistId);
    }

    static class ExposedSourceManager extends YoutubeAudioSourceManager {
        AudioItem route(String identifier, Client client) throws CannotBeLoaded, IOException {
            try (HttpInterface httpInterface = getInterface()) {
                Router router = getRouter(httpInterface, identifier);
                assertNotNull(router);
                return router.route(client);
            }
        }
    }

    static class RecordingClient implements Client {
        String lastCall;
        String videoId;
        String playlistId;
        String selectedVideoId;

        @Override
        public String getIdentifier() {
            return "TEST";
        }

        @Override
        public String getPlayerParams() {
            return null;
        }

        @Override
        public ClientOptions getOptions() {
            return ClientOptions.DEFAULT;
        }

        @Override
        public boolean canHandleRequest(String identifier) {
            return true;
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
            this.lastCall = "video";
            this.videoId = videoId;
            return source.buildAudioTrack(new AudioTrackInfo(
                "Video title",
                "Author",
                1000,
                videoId,
                false,
                WATCH_URL + videoId
            ));
        }

        @Override
        public AudioItem loadSearch(YoutubeAudioSourceManager source,
                                    HttpInterface httpInterface,
                                    String searchQuery) {
            this.lastCall = "search";
            return null;
        }

        @Override
        public AudioItem loadSearchMusic(YoutubeAudioSourceManager source,
                                         HttpInterface httpInterface,
                                         String searchQuery) {
            this.lastCall = "musicSearch";
            return null;
        }

        @Override
        public AudioItem loadMix(YoutubeAudioSourceManager source,
                                 HttpInterface httpInterface,
                                 String mixId,
                                 String selectedVideoId) {
            this.lastCall = "mix";
            this.playlistId = mixId;
            this.selectedVideoId = selectedVideoId;
            return null;
        }

        @Override
        public AudioItem loadPlaylist(YoutubeAudioSourceManager source,
                                      HttpInterface httpInterface,
                                      String playlistId,
                                      String selectedVideoId) {
            this.lastCall = "playlist";
            this.playlistId = playlistId;
            this.selectedVideoId = selectedVideoId;
            AudioTrack track = source.buildAudioTrack(new AudioTrackInfo(
                "Playlist track",
                "Author",
                1000,
                "siNFnlqtd8M",
                false,
                WATCH_URL + "siNFnlqtd8M"
            ));
            return new BasicAudioPlaylist("Playlist title", Collections.singletonList(track), track, false);
        }
    }
}
