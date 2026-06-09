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
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
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
    private final ConcurrentMap<String, String> visitorDataByVideoAndClient = new ConcurrentHashMap<>();

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
        String sessionKey = videoId + "|" + clientName;
        String effectiveVisitorData = visitorData;

        if (effectiveVisitorData == null && tokenType.equals(TOKEN_TYPE_GVS)) {
            effectiveVisitorData = visitorDataByVideoAndClient.get(sessionKey);
        }

        PoTokenResult cached = cache.get(videoId, clientName, tokenType, effectiveVisitorData);
        String tokenLabel = tokenType.equals(TOKEN_TYPE_PLAYER) ? "Player" : "GVS";

        if (cached != null) {
            log.info("{} PO token provider cache hit videoId={} client={}", tokenLabel, videoId, clientName);
            return cached;
        }

        log.info("{} PO token provider cache miss videoId={} client={}", tokenLabel, videoId, clientName);
        log.info("Calling external PO token provider tokenType={} videoId={} client={}",
            tokenType, videoId, clientName);

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
            args.add(effectiveVisitorData != null ? effectiveVisitorData : "");

            ProcessBuilder builder = new ProcessBuilder(args);

            process = builder.start();

            final Process started = process;
            final StringBuilder stdout = new StringBuilder();
            final StringBuilder stderr = new StringBuilder();
            Thread stdoutReader = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(started.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        stdout.append(line);
                    }
                } catch (Exception ignored) {
                }
            }, "pot-provider-stdout");
            Thread stderrReader = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(started.getErrorStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        if (stderr.length() > 0) {
                            stderr.append(' ');
                        }
                        stderr.append(line);
                    }
                } catch (Exception ignored) {
                }
            }, "pot-provider-stderr");
            stdoutReader.setDaemon(true);
            stderrReader.setDaemon(true);
            stdoutReader.start();
            stderrReader.start();

            if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                log.warn("External PO token provider timed out after {}ms tokenType={} videoId={} client={} stderr={}",
                    timeoutMs, tokenType, videoId, clientName, summarizeStderr(stderr));
                process.destroyForcibly();
                return null;
            }

            stdoutReader.join(1000);
            stderrReader.join(1000);

            int exitCode = process.exitValue();

            if (exitCode != 0) {
                log.warn("External PO token provider exited with code {} tokenType={} videoId={} client={} stderr={}",
                    exitCode, tokenType, videoId, clientName, summarizeStderr(stderr));
                return null;
            }

            JsonObject json = JsonParser.object().from(stdout.toString());
            String poToken = json.getString("poToken");

            if (poToken == null || poToken.isEmpty()) {
                log.warn("External PO token provider returned no poToken videoId={} client={} tokenType={}",
                    videoId, clientName, tokenType);
                return null;
            }

            Object visitorDataValue = json.get("visitorData");
            String resolvedVisitorData = visitorDataValue instanceof String && !((String) visitorDataValue).isEmpty()
                ? (String) visitorDataValue
                : effectiveVisitorData;
            long expiresAtEpochMs = json.getLong("expiresAtEpochMs", 0L);

            PoTokenResult result = new PoTokenResult(poToken, resolvedVisitorData, expiresAtEpochMs);
            cache.put(videoId, clientName, tokenType, effectiveVisitorData, result);

            if (tokenType.equals(TOKEN_TYPE_PLAYER) && resolvedVisitorData != null) {
                visitorDataByVideoAndClient.put(sessionKey, resolvedVisitorData);
            }

            log.info("External PO token provider succeeded tokenType={} videoId={} client={}",
                tokenType, videoId, clientName);

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

    private static String summarizeStderr(StringBuilder stderr) {
        if (stderr.length() == 0) {
            return "<empty>";
        }

        String summary = stderr.toString()
            .replaceAll("(?i)(authorization|cookie|token|secret|password)(\\s*[:=]\\s*)[^\\s,;]+", "$1$2<redacted>")
            .replaceAll("[\\r\\n\\t]+", " ")
            .trim();

        if (summary.toLowerCase(Locale.ROOT).contains("potoken")) {
            summary = summary.replaceAll("(?i)(potoken\\s*[:=]\\s*)[^\\s,;]+", "$1<redacted>");
        }

        return summary.length() <= 500 ? summary : summary.substring(0, 500) + "...";
    }
}
