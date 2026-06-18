package dev.lavalink.youtube.pot;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Integration-style tests that exercise {@link ExternalPoTokenProvider} against real
 * temporary shell scripts. POSIX-only (the server and dev machines are Unix).
 */
@DisabledOnOs(OS.WINDOWS)
class ExternalPoTokenProviderTest {
    @TempDir
    Path tempDir;

    private final List<Path> scripts = new ArrayList<>();

    @AfterEach
    void cleanup() throws IOException {
        for (Path script : scripts) {
            Files.deleteIfExists(script);
        }
    }

    /** Writes an executable script and returns its absolute path. */
    private String writeScript(String name, String body) throws IOException {
        Path script = tempDir.resolve(name);
        Files.write(script, ("#!/bin/sh\n" + body).getBytes(StandardCharsets.UTF_8));
        script.toFile().setExecutable(true);
        scripts.add(script);
        return script.toAbsolutePath().toString();
    }

    @Test
    void returnsTokenFromStdout() throws IOException {
        String cmd = writeScript("ok.sh",
            "echo '{\"poToken\":\"tok123\",\"visitorData\":\"vd123\",\"expiresAtEpochMs\":9999999999999}'\n");
        ExternalPoTokenProvider provider = new ExternalPoTokenProvider(cmd, 5000, new PoTokenCache(300));

        PoTokenResult result = provider.fetchToken("vid", "WEB", "seed", PoTokenProvider.TOKEN_TYPE_GVS);

        assertNotNull(result);
        assertEquals("tok123", result.poToken);
        assertEquals("vd123", result.visitorData);
        assertEquals(9999999999999L, result.expiresAtEpochMs);
    }

    @Test
    void secondCallIsServedFromCacheWithoutRespawning() throws IOException {
        // The script records each invocation by appending a line to a counter file.
        Path counter = tempDir.resolve("count.txt");
        String cmd = writeScript("counting.sh",
            "echo run >> '" + counter.toAbsolutePath() + "'\n" +
            "echo '{\"poToken\":\"tok\",\"visitorData\":\"vd\",\"expiresAtEpochMs\":9999999999999}'\n");
        ExternalPoTokenProvider provider = new ExternalPoTokenProvider(cmd, 5000, new PoTokenCache(300));

        provider.fetchToken("vid", "WEB", "seed", PoTokenProvider.TOKEN_TYPE_GVS);
        provider.fetchToken("vid", "WEB", "seed", PoTokenProvider.TOKEN_TYPE_GVS);

        assertEquals(1, Files.readAllLines(counter).size(), "provider should be spawned only once");
    }

    @Test
    void directLoadPlayerTokenIsReusedAcrossRoutePlannerAddressesAndClientAliases() throws IOException {
        Path counter = tempDir.resolve("count.txt");
        String cmd = writeScript("route-counting.sh",
            "echo run >> '" + counter.toAbsolutePath() + "'\n" +
            "echo '{\"poToken\":\"tok\",\"visitorData\":\"vd\",\"expiresAtEpochMs\":9999999999999}'\n");
        ExternalPoTokenProvider provider = new ExternalPoTokenProvider(cmd, 5000, new PoTokenCache(300));

        assertNotNull(provider.fetchToken(
            "vid", "TV", null, PoTokenProvider.TOKEN_TYPE_PLAYER, "2001:db8::1"));
        assertNotNull(provider.fetchToken(
            "vid", "TVHTML5", null, PoTokenProvider.TOKEN_TYPE_PLAYER, "2001:db8::2"));

        assertEquals(1, Files.readAllLines(counter).size(),
            "direct load and playback should share one player token");
    }

    @Test
    void concurrentRequestsUseSingleExternalProcess() throws Exception {
        Path counter = tempDir.resolve("count.txt");
        String cmd = writeScript("concurrent-counting.sh",
            "echo run >> '" + counter.toAbsolutePath() + "'\n" +
            "sleep 1\n" +
            "echo '{\"poToken\":\"tok\",\"expiresAtEpochMs\":9999999999999}'\n");
        ExternalPoTokenProvider provider = new ExternalPoTokenProvider(cmd, 5000, new PoTokenCache(300));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);

        try {
            Future<PoTokenResult> first = executor.submit(() -> {
                start.await();
                return provider.fetchToken(
                    "vid", "MWEB", null, PoTokenProvider.TOKEN_TYPE_PLAYER, "2001:db8::1");
            });
            Future<PoTokenResult> second = executor.submit(() -> {
                start.await();
                return provider.fetchToken(
                    "vid", "MWEB", null, PoTokenProvider.TOKEN_TYPE_PLAYER, "2001:db8::2");
            });
            start.countDown();

            assertNotNull(first.get());
            assertNotNull(second.get());
            assertEquals(1, Files.readAllLines(counter).size(),
                "concurrent cache misses should be coalesced");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void expiredResultCausesRespawn() throws IOException {
        Path counter = tempDir.resolve("count.txt");
        // Returns an already-expired token, so the cache never retains it.
        String cmd = writeScript("expired.sh",
            "echo run >> '" + counter.toAbsolutePath() + "'\n" +
            "echo '{\"poToken\":\"tok\",\"visitorData\":\"vd\",\"expiresAtEpochMs\":1}'\n");
        ExternalPoTokenProvider provider = new ExternalPoTokenProvider(cmd, 5000, new PoTokenCache(300));

        provider.fetchToken("vid", "WEB", "seed", PoTokenProvider.TOKEN_TYPE_GVS);
        provider.fetchToken("vid", "WEB", "seed", PoTokenProvider.TOKEN_TYPE_GVS);

        assertEquals(2, Files.readAllLines(counter).size(), "expired token must not be cached");
    }

    @Test
    void nonZeroExitReturnsNullAndIsNotCached() throws IOException {
        Path counter = tempDir.resolve("count.txt");
        String cmd = writeScript("fail.sh",
            "echo run >> '" + counter.toAbsolutePath() + "'\n" +
            "echo 'boom' >&2\n" +
            "exit 1\n");
        ExternalPoTokenProvider provider = new ExternalPoTokenProvider(cmd, 5000, new PoTokenCache(300));

        assertNull(provider.fetchToken("vid", "WEB", "seed", PoTokenProvider.TOKEN_TYPE_GVS));
        assertNull(provider.fetchToken("vid", "WEB", "seed", PoTokenProvider.TOKEN_TYPE_GVS));
        assertEquals(2, Files.readAllLines(counter).size(), "failures must not be cached");
    }

    @Test
    void missingCommandReturnsNull() {
        ExternalPoTokenProvider provider = new ExternalPoTokenProvider(
            tempDir.resolve("does-not-exist").toAbsolutePath().toString(), 5000, new PoTokenCache(300));
        assertNull(provider.fetchToken("vid", "WEB", "seed", PoTokenProvider.TOKEN_TYPE_GVS));
    }

    @Test
    void malformedJsonReturnsNull() throws IOException {
        String cmd = writeScript("garbage.sh", "echo 'not valid json'\n");
        ExternalPoTokenProvider provider = new ExternalPoTokenProvider(cmd, 5000, new PoTokenCache(300));
        assertNull(provider.fetchToken("vid", "WEB", "seed", PoTokenProvider.TOKEN_TYPE_GVS));
    }

    @Test
    void emptyPoTokenReturnsNull() throws IOException {
        String cmd = writeScript("empty.sh",
            "echo '{\"poToken\":\"\",\"visitorData\":\"vd\"}'\n");
        ExternalPoTokenProvider provider = new ExternalPoTokenProvider(cmd, 5000, new PoTokenCache(300));
        assertNull(provider.fetchToken("vid", "WEB", "seed", PoTokenProvider.TOKEN_TYPE_GVS));
    }

    @Test
    void timeoutReturnsNull() throws IOException {
        String cmd = writeScript("slow.sh",
            "sleep 60\n" +
            "echo '{\"poToken\":\"tok\",\"visitorData\":\"vd\"}'\n");
        ExternalPoTokenProvider provider = new ExternalPoTokenProvider(cmd, 200, new PoTokenCache(300));

        long start = System.currentTimeMillis();
        PoTokenResult result = provider.fetchToken("vid", "WEB", "seed", PoTokenProvider.TOKEN_TYPE_GVS);
        long elapsed = System.currentTimeMillis() - start;

        assertNull(result);
        // should give up promptly rather than waiting the full 60s
        org.junit.jupiter.api.Assertions.assertTrue(elapsed < 10_000, "provider should time out promptly, took " + elapsed + "ms");
    }

    @Test
    void criticalPathTimeoutReturnsNullButCachesLateSuccess() throws Exception {
        Path counter = tempDir.resolve("count.txt");
        String cmd = writeScript("slow-success.sh",
            "echo run >> '" + counter.toAbsolutePath() + "'\n" +
            "sleep 1\n" +
            "echo '{\"poToken\":\"tok\",\"visitorData\":\"vd\",\"expiresAtEpochMs\":9999999999999}'\n");
        ExternalPoTokenProvider provider = new ExternalPoTokenProvider(
            cmd, 5000, 100, new PoTokenCache(300), true, true);

        long start = System.currentTimeMillis();
        PoTokenResult first = provider.fetchToken("vid", "WEB", "seed", PoTokenProvider.TOKEN_TYPE_GVS);
        long elapsed = System.currentTimeMillis() - start;

        assertNull(first);
        org.junit.jupiter.api.Assertions.assertTrue(elapsed < 1000,
            "critical path should not wait for the full provider runtime, took " + elapsed + "ms");

        Thread.sleep(1500);

        PoTokenResult second = provider.fetchToken("vid", "WEB", "seed", PoTokenProvider.TOKEN_TYPE_GVS);
        assertNotNull(second);
        assertEquals("tok", second.poToken);
        assertEquals(1, Files.readAllLines(counter).size(),
            "late provider success should populate the cache without respawning");
    }

    @Test
    void zeroCriticalPathTimeoutDelaysBackgroundFetch() throws Exception {
        Path counter = tempDir.resolve("count.txt");
        String cmd = writeScript("delayed-background.sh",
            "echo run >> '" + counter.toAbsolutePath() + "'\n" +
            "echo '{\"poToken\":\"tok\",\"visitorData\":\"vd\",\"expiresAtEpochMs\":9999999999999}'\n");
        ExternalPoTokenProvider provider = new ExternalPoTokenProvider(
            cmd, 5000, 0, new PoTokenCache(300), true, true);

        long start = System.currentTimeMillis();
        PoTokenResult first = provider.fetchToken("vid", "WEB", "seed", PoTokenProvider.TOKEN_TYPE_GVS);
        long elapsed = System.currentTimeMillis() - start;

        assertNull(first);
        org.junit.jupiter.api.Assertions.assertTrue(elapsed < 500,
            "zero critical path timeout should return immediately, took " + elapsed + "ms");
        org.junit.jupiter.api.Assertions.assertFalse(Files.exists(counter),
            "background provider should not start on the immediate playback path");

        Thread.sleep(3000);

        PoTokenResult second = provider.fetchToken("vid", "WEB", "seed", PoTokenProvider.TOKEN_TYPE_GVS);
        assertNotNull(second);
        assertEquals(1, Files.readAllLines(counter).size());
    }

    @Test
    void missingExpiresAtDefaultsToZero() throws IOException {
        String cmd = writeScript("noexpiry.sh",
            "echo '{\"poToken\":\"tok\",\"visitorData\":\"vd\"}'\n");
        ExternalPoTokenProvider provider = new ExternalPoTokenProvider(cmd, 5000, new PoTokenCache(300));

        PoTokenResult result = provider.fetchToken("vid", "WEB", "seed", PoTokenProvider.TOKEN_TYPE_GVS);
        assertNotNull(result);
        assertEquals(0L, result.expiresAtEpochMs);
    }

    @Test
    void passesSourceAddressAsSeparateArgument() throws IOException {
        Path arguments = tempDir.resolve("arguments.txt");
        String cmd = writeScript("arguments.sh",
            "printf '%s\\n' \"$@\" > '" + arguments.toAbsolutePath() + "'\n" +
            "echo '{\"poToken\":\"tok\",\"visitorData\":\"vd\"}'\n");
        ExternalPoTokenProvider provider = new ExternalPoTokenProvider(cmd, 5000, new PoTokenCache(300));

        PoTokenResult result = provider.fetchToken(
            "vid;touch /tmp/not-run", "MWEB", "visitor data",
            PoTokenProvider.TOKEN_TYPE_GVS, "2001:db8::1234");

        assertNotNull(result);
        assertEquals(Arrays.asList(
            "vid;touch /tmp/not-run",
            "MWEB",
            "gvs",
            "visitor data",
            "2001:db8::1234"
        ), Files.readAllLines(arguments));
    }

    @Test
    void disabledTokenTypeDoesNotInvokeProvider() throws IOException {
        Path counter = tempDir.resolve("count.txt");
        String cmd = writeScript("disabled.sh",
            "echo run >> '" + counter.toAbsolutePath() + "'\n" +
            "echo '{\"poToken\":\"tok\"}'\n");
        ExternalPoTokenProvider provider = new ExternalPoTokenProvider(
            cmd, 5000, new PoTokenCache(300), false, true);

        assertNull(provider.fetchToken("vid", "MWEB", null, PoTokenProvider.TOKEN_TYPE_PLAYER));
        org.junit.jupiter.api.Assertions.assertFalse(Files.exists(counter));
        assertNotNull(provider.fetchToken("vid", "MWEB", null, PoTokenProvider.TOKEN_TYPE_GVS));
        assertEquals(1, Files.readAllLines(counter).size());
    }
}
