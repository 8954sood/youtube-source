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
import java.util.List;

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
    void missingExpiresAtDefaultsToZero() throws IOException {
        String cmd = writeScript("noexpiry.sh",
            "echo '{\"poToken\":\"tok\",\"visitorData\":\"vd\"}'\n");
        ExternalPoTokenProvider provider = new ExternalPoTokenProvider(cmd, 5000, new PoTokenCache(300));

        PoTokenResult result = provider.fetchToken("vid", "WEB", "seed", PoTokenProvider.TOKEN_TYPE_GVS);
        assertNotNull(result);
        assertEquals(0L, result.expiresAtEpochMs);
    }
}
