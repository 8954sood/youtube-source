package dev.lavalink.youtube.pot;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PoTokenCacheTest {
    @Test
    void getOnEmptyCacheReturnsNull() {
        PoTokenCache cache = new PoTokenCache(300);
        assertNull(cache.get("vid", "WEB", PoTokenProvider.TOKEN_TYPE_GVS, "vd"));
    }

    @Test
    void putThenGetRoundTrips() {
        PoTokenCache cache = new PoTokenCache(300);
        PoTokenResult result = new PoTokenResult("token", "vd", 0);
        cache.put("vid", "WEB", PoTokenProvider.TOKEN_TYPE_GVS, "vd", result);

        PoTokenResult fetched = cache.get("vid", "WEB", PoTokenProvider.TOKEN_TYPE_GVS, "vd");
        assertNotNull(fetched);
        assertEquals("token", fetched.poToken);
        assertEquals("vd", fetched.visitorData);
    }

    @Test
    void keyComponentsAreDistinct() {
        PoTokenCache cache = new PoTokenCache(300);
        cache.put("vid", "WEB", PoTokenProvider.TOKEN_TYPE_GVS, "vd", new PoTokenResult("t", "vd", 0));

        assertNull(cache.get("other", "WEB", PoTokenProvider.TOKEN_TYPE_GVS, "vd"));
        assertNull(cache.get("vid", "WEB_EMBEDDED_PLAYER", PoTokenProvider.TOKEN_TYPE_GVS, "vd"));
        assertNull(cache.get("vid", "WEB", PoTokenProvider.TOKEN_TYPE_PLAYER, "vd"));
        assertNull(cache.get("vid", "WEB", PoTokenProvider.TOKEN_TYPE_GVS, "other"));
        assertNotNull(cache.get("vid", "WEB", PoTokenProvider.TOKEN_TYPE_GVS, "vd"));
    }

    @Test
    void sourceAddressIsPartOfCacheKey() {
        PoTokenCache cache = new PoTokenCache(300);
        cache.put("vid", "MWEB", PoTokenProvider.TOKEN_TYPE_GVS, "vd", "2001:db8::1",
            new PoTokenResult("t", "vd", 0));

        assertNotNull(cache.get("vid", "MWEB", PoTokenProvider.TOKEN_TYPE_GVS, "vd", "2001:db8::1"));
        assertNull(cache.get("vid", "MWEB", PoTokenProvider.TOKEN_TYPE_GVS, "vd", "2001:db8::2"));
    }

    @Test
    void nullVisitorDataIsAValidKey() {
        PoTokenCache cache = new PoTokenCache(300);
        cache.put("vid", "WEB", PoTokenProvider.TOKEN_TYPE_GVS, null, new PoTokenResult("t", null, 0));
        assertNotNull(cache.get("vid", "WEB", PoTokenProvider.TOKEN_TYPE_GVS, null));
    }

    @Test
    void expiredEntryByExplicitTimestampIsEvicted() {
        PoTokenCache cache = new PoTokenCache(300);
        // expiresAtEpochMs in the past
        cache.put("vid", "WEB", PoTokenProvider.TOKEN_TYPE_GVS, "vd",
            new PoTokenResult("t", "vd", System.currentTimeMillis() - 1000));
        assertNull(cache.get("vid", "WEB", PoTokenProvider.TOKEN_TYPE_GVS, "vd"));
    }

    @Test
    void expiredEntryByTtlIsEvicted() throws InterruptedException {
        PoTokenCache cache = new PoTokenCache(0); // TTL = 0s -> immediately expired
        cache.put("vid", "WEB", PoTokenProvider.TOKEN_TYPE_GVS, "vd", new PoTokenResult("t", "vd", 0));
        Thread.sleep(5);
        assertNull(cache.get("vid", "WEB", PoTokenProvider.TOKEN_TYPE_GVS, "vd"));
    }

    @Test
    void concurrentPutAndGetIsSafe() throws InterruptedException {
        PoTokenCache cache = new PoTokenCache(300);
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicBoolean failed = new AtomicBoolean(false);

        for (int i = 0; i < threads; i++) {
            final int id = i;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int j = 0; j < 1000; j++) {
                        String vid = "vid" + (id % 4);
                        cache.put(vid, "WEB", PoTokenProvider.TOKEN_TYPE_GVS, "vd", new PoTokenResult("t" + j, "vd", 0));
                        cache.get(vid, "WEB", PoTokenProvider.TOKEN_TYPE_GVS, "vd");
                    }
                } catch (Throwable t) {
                    failed.set(true);
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "concurrent workload did not finish in time");
        pool.shutdownNow();
        assertFalse(failed.get(), "a thread threw during concurrent access");
    }
}
