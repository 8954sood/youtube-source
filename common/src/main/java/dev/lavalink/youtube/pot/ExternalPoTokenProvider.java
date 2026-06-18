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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A {@link PoTokenProvider} that delegates to an external executable, invoked once per
 * (videoId, clientName, tokenType, visitorData) and cached via {@link PoTokenCache}.
 *
 * <p>The command is run with a fixed argument list (never via a shell), so the arguments are
 * not subject to shell interpretation or injection:
 * <pre>{@code <command> <videoId> <clientName> <tokenType> <visitorData> <sourceAddress>}</pre>
 * The process must print a single JSON object to stdout:
 * <pre>{@code {"poToken":"...","visitorData":"...","expiresAtEpochMs":1780000000000}}</pre>
 *
 * <p>Every failure mode (missing/non-executable command, non-zero exit, timeout, malformed
 * output, any exception) is logged at WARN and results in {@code null} so the caller falls back
 * to existing behaviour. Token values are never logged. Failures are never cached.
 */
public class ExternalPoTokenProvider implements PoTokenProvider {
    private static final Logger log = LoggerFactory.getLogger(ExternalPoTokenProvider.class);
    private static final long BACKGROUND_FETCH_DELAY_MS = 2000;

    private final String command;
    private final long timeoutMs;
    private final long criticalPathTimeoutMs;
    private final PoTokenCache cache;
    private final boolean playerTokenEnabled;
    private final boolean gvsTokenEnabled;
    private final ConcurrentMap<String, String> visitorDataByVideoAndClient = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, CompletableFuture<PoTokenResult>> inFlightRequests = new ConcurrentHashMap<>();
    private final ExecutorService executor;

    public ExternalPoTokenProvider(@NotNull String command, long timeoutMs, @NotNull PoTokenCache cache) {
        this(command, timeoutMs, cache, true, true);
    }

    public ExternalPoTokenProvider(@NotNull String command,
                                   long timeoutMs,
                                   @NotNull PoTokenCache cache,
                                   boolean playerTokenEnabled,
                                   boolean gvsTokenEnabled) {
        this(command, timeoutMs, timeoutMs, cache, playerTokenEnabled, gvsTokenEnabled);
    }

    public ExternalPoTokenProvider(@NotNull String command,
                                   long timeoutMs,
                                   long criticalPathTimeoutMs,
                                   @NotNull PoTokenCache cache,
                                   boolean playerTokenEnabled,
                                   boolean gvsTokenEnabled) {
        this.command = command;
        this.timeoutMs = timeoutMs;
        this.criticalPathTimeoutMs = Math.max(0, Math.min(criticalPathTimeoutMs, timeoutMs));
        this.cache = cache;
        this.playerTokenEnabled = playerTokenEnabled;
        this.gvsTokenEnabled = gvsTokenEnabled;
        this.executor = Executors.newCachedThreadPool(new ProviderThreadFactory());
    }

    @Override
    @Nullable
    public PoTokenResult fetchToken(@NotNull String videoId,
                                    @NotNull String clientName,
                                    @Nullable String visitorData,
                                    @NotNull String tokenType) {
        return fetchToken(videoId, clientName, visitorData, tokenType, null);
    }

    @Override
    @Nullable
    public PoTokenResult fetchToken(@NotNull String videoId,
                                    @NotNull String clientName,
                                    @Nullable String visitorData,
                                    @NotNull String tokenType,
                                    @Nullable String sourceAddress) {
        String normalizedTokenType = PoTokenCache.normalizeTokenType(tokenType);

        if ((TOKEN_TYPE_PLAYER.equals(normalizedTokenType) && !playerTokenEnabled)
            || (TOKEN_TYPE_GVS.equals(normalizedTokenType) && !gvsTokenEnabled)) {
            return null;
        }

        String normalizedClientName = PoTokenCache.normalizeClientName(clientName);
        String sessionKey = videoId + "|" + normalizedClientName + "|" + (sourceAddress != null ? sourceAddress : "");
        String effectiveVisitorData = visitorData;

        if (effectiveVisitorData == null && normalizedTokenType.equals(TOKEN_TYPE_GVS)) {
            effectiveVisitorData = visitorDataByVideoAndClient.get(sessionKey);
        }

        final String finalEffectiveVisitorData = effectiveVisitorData;
        String keyFingerprint = PoTokenCache.keyFingerprint(
            videoId, normalizedClientName, normalizedTokenType, finalEffectiveVisitorData, sourceAddress);
        PoTokenResult cached = cache.get(
            videoId, normalizedClientName, normalizedTokenType, finalEffectiveVisitorData, sourceAddress);
        String tokenLabel = normalizedTokenType.equals(TOKEN_TYPE_PLAYER) ? "Player" : "GVS";

        if (cached != null) {
            log.info("{} PO token provider cache hit videoId={} client={} key={}",
                tokenLabel, videoId, normalizedClientName, keyFingerprint);
            return cached;
        }

        CompletableFuture<PoTokenResult> future = inFlightRequests.computeIfAbsent(keyFingerprint, ignored ->
            startFetch(videoId, normalizedClientName, finalEffectiveVisitorData,
                normalizedTokenType, sourceAddress, sessionKey, keyFingerprint, tokenLabel));

        try {
            PoTokenResult result = criticalPathTimeoutMs == 0
                ? future.getNow(null)
                : future.get(criticalPathTimeoutMs, TimeUnit.MILLISECONDS);

            if (result != null) {
                return result;
            }

            return cache.get(
                videoId, normalizedClientName, normalizedTokenType, finalEffectiveVisitorData, sourceAddress);
        } catch (TimeoutException e) {
            log.info("{} PO token provider still running after {}ms videoId={} client={} key={}, continuing without token",
                tokenLabel, criticalPathTimeoutMs, videoId, normalizedClientName, keyFingerprint);
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException e) {
            log.warn("External PO token provider failed videoId={} client={} tokenType={}",
                videoId, normalizedClientName, normalizedTokenType, e.getCause());
            return null;
        } finally {
            if (future.isDone()) {
                inFlightRequests.remove(keyFingerprint, future);
            }
        }
    }

    @NotNull
    private CompletableFuture<PoTokenResult> startFetch(@NotNull String videoId,
                                                        @NotNull String clientName,
                                                        @Nullable String effectiveVisitorData,
                                                        @NotNull String tokenType,
                                                        @Nullable String sourceAddress,
                                                        @NotNull String sessionKey,
                                                        @NotNull String keyFingerprint,
                                                        @NotNull String tokenLabel) {
        CompletableFuture<PoTokenResult> future = CompletableFuture.supplyAsync(() ->
            fetchUncachedAfterOptionalDelay(videoId, clientName, effectiveVisitorData, tokenType, sourceAddress,
                sessionKey, keyFingerprint, tokenLabel), executor);
        future.whenComplete((ignoredResult, ignoredError) -> inFlightRequests.remove(keyFingerprint, future));
        return future;
    }

    @Nullable
    private PoTokenResult fetchUncachedAfterOptionalDelay(@NotNull String videoId,
                                                          @NotNull String clientName,
                                                          @Nullable String effectiveVisitorData,
                                                          @NotNull String tokenType,
                                                          @Nullable String sourceAddress,
                                                          @NotNull String sessionKey,
                                                          @NotNull String keyFingerprint,
                                                          @NotNull String tokenLabel) {
        if (criticalPathTimeoutMs == 0) {
            try {
                Thread.sleep(BACKGROUND_FETCH_DELAY_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }

        return fetchUncached(videoId, clientName, effectiveVisitorData, tokenType, sourceAddress,
            sessionKey, keyFingerprint, tokenLabel);
    }

    @Nullable
    private PoTokenResult fetchUncached(@NotNull String videoId,
                                        @NotNull String clientName,
                                        @Nullable String effectiveVisitorData,
                                        @NotNull String tokenType,
                                        @Nullable String sourceAddress,
                                        @NotNull String sessionKey,
                                        @NotNull String keyFingerprint,
                                        @NotNull String tokenLabel) {
        long startedAt = System.nanoTime();
        log.info("{} PO token provider cache miss videoId={} client={} key={}",
            tokenLabel, videoId, clientName, keyFingerprint);
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
            args.add(sourceAddress != null ? sourceAddress : "");

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
            cache.put(videoId, clientName, tokenType, effectiveVisitorData, sourceAddress, result);

            if (tokenType.equals(TOKEN_TYPE_PLAYER) && resolvedVisitorData != null) {
                visitorDataByVideoAndClient.put(sessionKey, resolvedVisitorData);
            }

            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
            log.info("External PO token provider succeeded tokenType={} videoId={} client={} key={} elapsedMs={}",
                tokenType, videoId, clientName, keyFingerprint, elapsedMs);

            return result;
        } catch (Exception e) {
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
            log.warn("External PO token provider failed videoId={} client={} tokenType={}",
                videoId, clientName, tokenType, e);
            log.info("External PO token provider finished unsuccessfully tokenType={} videoId={} client={} key={} elapsedMs={}",
                tokenType, videoId, clientName, keyFingerprint, elapsedMs);
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

    private static final class ProviderThreadFactory implements ThreadFactory {
        private final AtomicInteger threadId = new AtomicInteger();

        @Override
        public Thread newThread(@NotNull Runnable runnable) {
            Thread thread = new Thread(runnable, "external-po-token-provider-" + threadId.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
