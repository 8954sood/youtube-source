package dev.lavalink.youtube.pot;

import com.grack.nanojson.JsonObject;
import com.grack.nanojson.JsonParser;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * A {@link PoTokenProvider} that delegates to an external executable, invoked once per
 * (videoId, clientName, tokenType, visitorData) and cached via {@link PoTokenCache}.
 *
 * <p>The command is run with a fixed argument list (never via a shell), so the arguments are
 * not subject to shell interpretation or injection:
 * <pre>{@code <command> <videoId> <clientName> <tokenType> <visitorData>}</pre>
 * The process must print a single JSON object to stdout:
 * <pre>{@code {"poToken":"...","visitorData":"...","expiresAtEpochMs":1780000000000}}</pre>
 *
 * <p>Every failure mode (missing/non-executable command, non-zero exit, timeout, malformed
 * output, any exception) is logged at WARN and results in {@code null} so the caller falls back
 * to existing behaviour. Token values are never logged. Failures are never cached.
 */
public class ExternalPoTokenProvider implements PoTokenProvider {
    private static final Logger log = LoggerFactory.getLogger(ExternalPoTokenProvider.class);

    private final String command;
    private final long timeoutMs;
    private final PoTokenCache cache;

    public ExternalPoTokenProvider(@NotNull String command, long timeoutMs, @NotNull PoTokenCache cache) {
        this.command = command;
        this.timeoutMs = timeoutMs;
        this.cache = cache;
    }

    @Override
    @Nullable
    public PoTokenResult fetchToken(@NotNull String videoId,
                                    @NotNull String clientName,
                                    @Nullable String visitorData,
                                    @NotNull String tokenType) {
        PoTokenResult cached = cache.get(videoId, clientName, tokenType, visitorData);

        if (cached != null) {
            log.debug("PO token cache hit videoId={} client={} tokenType={}", videoId, clientName, tokenType);
            return cached;
        }

        log.debug("PO token cache miss, invoking provider videoId={} client={} tokenType={}", videoId, clientName, tokenType);

        File executable = new File(command);

        if (!executable.exists() || !executable.canExecute()) {
            log.warn("External PO token provider command '{}' does not exist or is not executable.", command);
            return null;
        }

        Process process = null;

        try {
            List<String> args = new ArrayList<>();
            args.add(command);
            args.add(videoId);
            args.add(clientName);
            args.add(tokenType);
            args.add(visitorData != null ? visitorData : "");

            ProcessBuilder builder = new ProcessBuilder(args);
            builder.redirectError(ProcessBuilder.Redirect.DISCARD);

            process = builder.start();

            // Drain stdout on a background daemon thread so a hung/slow process cannot block us
            // past the timeout. destroyForcibly() on timeout closes the stream and unblocks it.
            final Process started = process;
            final StringBuilder stdout = new StringBuilder();
            Thread reader = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(started.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        stdout.append(line);
                    }
                } catch (Exception ignored) {
                    // stream closed (e.g. process destroyed) — nothing to do
                }
            }, "pot-provider-stdout");
            reader.setDaemon(true);
            reader.start();

            if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                log.warn("External PO token provider timed out after {}ms videoId={} client={} tokenType={}",
                    timeoutMs, videoId, clientName, tokenType);
                process.destroyForcibly();
                return null;
            }

            // Process exited; let the reader finish draining whatever is buffered.
            reader.join(1000);

            int exitCode = process.exitValue();

            if (exitCode != 0) {
                log.warn("External PO token provider exited with code {} videoId={} client={} tokenType={}",
                    exitCode, videoId, clientName, tokenType);
                return null;
            }

            JsonObject json = JsonParser.object().from(stdout.toString());
            String poToken = json.getString("poToken");

            if (poToken == null || poToken.isEmpty()) {
                log.warn("External PO token provider returned no poToken videoId={} client={} tokenType={}",
                    videoId, clientName, tokenType);
                return null;
            }

            String resolvedVisitorData = json.has("visitorData") ? json.getString("visitorData") : visitorData;
            long expiresAtEpochMs = json.getLong("expiresAtEpochMs", 0L);

            PoTokenResult result = new PoTokenResult(poToken, resolvedVisitorData, expiresAtEpochMs);
            cache.put(videoId, clientName, tokenType, visitorData, result);

            return result;
        } catch (Exception e) {
            log.warn("External PO token provider failed videoId={} client={} tokenType={}",
                videoId, clientName, tokenType, e);
            return null;
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }
}
