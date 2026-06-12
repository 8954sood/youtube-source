package dev.lavalink.youtube.track;

import com.sedmelluq.discord.lavaplayer.tools.io.HttpClientTools;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import com.sedmelluq.discord.lavaplayer.tools.io.PersistentHttpStream;
import org.apache.http.client.utils.URIBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A persistent HTTP stream implementation that uses the range parameter instead of HTTP headers for specifying
 * the start position at which to start reading on a new connection.
 */
public class YoutubePersistentHttpStream extends PersistentHttpStream {
    private static final Logger log = LoggerFactory.getLogger(YoutubePersistentHttpStream.class);

    // Valid range for requesting without throttling is 0-11862014
    private static final long BUFFER_SIZE = 11862014;

    private long rangeEnd;

    /**
     * @param httpInterface The HTTP interface to use for requests
     * @param contentUrl The URL of the resource
     * @param contentLength The length of the resource in bytes
     */
    private final String videoId;
    private final long playbackStartedAt;
    private final AtomicBoolean firstReadLogged = new AtomicBoolean();

    public YoutubePersistentHttpStream(HttpInterface httpInterface, URI contentUrl, long contentLength) {
        this(httpInterface, contentUrl, contentLength, null, 0);
    }

    public YoutubePersistentHttpStream(HttpInterface httpInterface,
                                       URI contentUrl,
                                       long contentLength,
                                       String videoId,
                                       long playbackStartedAt) {
        super(httpInterface, contentUrl, contentLength);
        this.videoId = videoId;
        this.playbackStartedAt = playbackStartedAt;
    }

    @Override
    protected URI getConnectUrl() {
        if (!contentUrl.toString().contains("rn=")) {
            URI rangeUrl = getNextRangeUrl();

            log.debug("Range URL: {}", rangeUrl.toString());

            return rangeUrl;
        } else {
            return contentUrl;
        }
    }

    @Override
    protected int internalRead(byte[] b, int off, int len, boolean attemptReconnect) throws IOException {
        connect(false);
        long nextExpectedPosition = position + len + (len / 2);

        try {
            int result;
            if (nextExpectedPosition >= rangeEnd && rangeEnd != 0) {
                if (rangeEnd == contentLength) {
                    result = currentContent.read(b, off, len);
                    position += result;
                } else {
                    result = 0;
                    handleRangeEnd(null, attemptReconnect);
                }
            } else {
                result = currentContent.read(b, off, len);
                if (result >= 0) {
                    position += result;
                    if (position >= rangeEnd && !contentUrl.toString().contains("rn=")) {
                        handleRangeEnd(null, attemptReconnect);
                    }
                }
            }

            if (result > 0 && videoId != null && firstReadLogged.compareAndSet(false, true)) {
                log.info("First googlevideo stream read succeeded videoId={} bytes={} elapsedMs={}",
                    videoId, result, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - playbackStartedAt));
            }

            return result;
        } catch (IOException e) {
            handleRangeEnd(e, attemptReconnect);
            return internalRead(b, off, len, false);
        }
    }

    @Override
    protected long internalSkip(long n, boolean attemptReconnect) throws IOException {
        connect(false);
        long nextExpectedPosition = position + n;

        try {
            long result;
            if (nextExpectedPosition >= rangeEnd && rangeEnd != 0) {
                if (rangeEnd == contentLength) {
                    result = currentContent.skip(n);
                    position += result;
                } else {
                    result = n;
                    position += n;
                    handleRangeEnd(null, attemptReconnect);
                }
            } else {
                result = currentContent.skip(n);
                position += result;
                if (position >= rangeEnd && !contentUrl.toString().contains("rn=")) {
                    handleRangeEnd(null, attemptReconnect);
                }
            }

            return result;
        } catch (IOException e) {
            handleRangeEnd(e, attemptReconnect);
            return internalSkip(n, false);
        }
    }

    private URI getNextRangeUrl() {
        rangeEnd = position + BUFFER_SIZE;

        if (rangeEnd > contentLength) {
            rangeEnd = contentLength;
        }

        try {
            return new URIBuilder(contentUrl).addParameter("range", position + "-" + rangeEnd).build();
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }

    private void handleRangeEnd(IOException exception, boolean attemptReconnect) throws IOException {
        if (!attemptReconnect || (!HttpClientTools.isRetriableNetworkException(exception) && exception != null)) {
            throw exception;
        }

        close();
    }

    @Override
    protected boolean useHeadersForRange() {
        return false;
    }

    @Override
    public boolean canSeekHard() {
        return true;
    }
}
