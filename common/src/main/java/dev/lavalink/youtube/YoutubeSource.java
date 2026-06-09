package dev.lavalink.youtube;

import dev.lavalink.youtube.clients.Web;
import dev.lavalink.youtube.clients.WebEmbedded;
import dev.lavalink.youtube.pot.PoTokenProvider;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;

public class YoutubeSource {
    private static final Logger log = LoggerFactory.getLogger(YoutubeSource.class);

    public static String VERSION = "Unknown";

    private static volatile PoTokenProvider poTokenProvider = null;

    static {
        try (InputStream versionStream = YoutubeSource.class.getResourceAsStream("/yts-version.txt")) {
            if (versionStream != null) {
                byte[] content = new byte[versionStream.available()];
                versionStream.read(content);

                String versionS = new String(content);

                if (!versionS.startsWith("@")) {
                    VERSION = versionS;
                }
            }
        } catch (IOException ignored) {

        }
    }

    /**
     * Sets the given PoToken and VisitorData pair on all POT-supporting clients.
     * This is a convenience method to allow for setting this from one method call.
     * @param poToken The poToken to use. This must be paired to the specified visitorData.
     *                You may specify {@code null} to unset.
     * @param visitorData The visitorData to use. This must be paired to the specified poToken.
     *                    You may specify {@code null} to unset.
     */
    public static void setPoTokenAndVisitorData(String poToken, String visitorData) {
        log.debug("Applying configured poToken and visitorData to WEB, WEBEMBEDDED");
        Web.setPoTokenAndVisitorData(poToken, visitorData);
        WebEmbedded.setPoTokenAndVisitorData(poToken, visitorData);
    }

    /**
     * Registers an external {@link PoTokenProvider} consulted on a per-video basis at the
     * player request and GVS stream URL injection points. Pass {@code null} to unset, which
     * restores the static {@link #setPoTokenAndVisitorData(String, String)} behaviour.
     */
    public static void setPoTokenProvider(@Nullable PoTokenProvider provider) {
        poTokenProvider = provider;
    }

    /**
     * @return the registered {@link PoTokenProvider}, or {@code null} if none is set.
     */
    @Nullable
    public static PoTokenProvider getPoTokenProvider() {
        return poTokenProvider;
    }
}
