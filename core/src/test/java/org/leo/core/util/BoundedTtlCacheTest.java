package org.leo.core.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class BoundedTtlCacheTest {

    @ParameterizedTest
    @CsvSource({"ACCESS,second,first,a", "INSERTION,first,second,b"})
    void respectsEvictionOrderAndKeepsLruAsTheDefault(BoundedTtlCache.EvictionOrder order,
                                                     String evicted, String retained, String value) {
        BoundedTtlCache<String> cache = order == BoundedTtlCache.EvictionOrder.ACCESS
                ? new BoundedTtlCache<>(2, 1, TimeUnit.MINUTES)
                : new BoundedTtlCache<>(2, 1, TimeUnit.MINUTES, order);
        cache.put("first", "a");
        cache.put("second", "b");
        assertEquals("a", cache.get("first"));
        cache.put("third", "c");
        assertNull(cache.get(evicted));
        assertEquals(value, cache.get(retained));
        assertEquals("c", cache.get("third"));
        cache.clear();
        assertEquals(0, cache.size());
    }

    @Test
    void readsDoNotRenewTtlAndReplacementRenewsOnlyItsOwnEntry() {
        AtomicLong now = new AtomicLong(0);
        BoundedTtlCache<String> cache = new BoundedTtlCache<>(4, 10, TimeUnit.SECONDS,
                BoundedTtlCache.EvictionOrder.ACCESS, now::get);
        cache.put("default", "default");
        cache.put("replaced", "old");
        cache.put("short", "short", 2, TimeUnit.SECONDS);
        cache.put("long", "long", 20, TimeUnit.SECONDS);

        now.set(2000);
        assertEquals("short", cache.get("short"));
        now.incrementAndGet();
        assertNull(cache.get("short"));
        assertEquals("default", cache.get("default"));

        now.set(9_000);
        cache.put("replaced", "new");
        now.set(10_001);
        assertNull(cache.get("default"));
        assertEquals("new", cache.get("replaced"));
        assertEquals("long", cache.get("long"));
        now.set(19_000);
        assertEquals("new", cache.get("replaced"));
        now.incrementAndGet();
        assertNull(cache.get("replaced"));
        now.set(20_001);
        assertNull(cache.get("long"));
        assertEquals(0, cache.size());
    }

    @Test
    void largeDefaultAndPerEntryTtlsDoNotOverflowIntoImmediateExpiry() {
        AtomicLong now = new AtomicLong(1000);
        BoundedTtlCache<String> cache = new BoundedTtlCache<>(2, Long.MAX_VALUE, TimeUnit.DAYS,
                BoundedTtlCache.EvictionOrder.INSERTION, now::get);
        cache.put("default", "default");
        cache.put("custom", "custom", Long.MAX_VALUE, TimeUnit.MILLISECONDS);

        now.set(Long.MAX_VALUE);

        assertEquals("default", cache.get("default"));
        assertEquals("custom", cache.get("custom"));
    }

    @Test
    void explicitCleanupReleasesAllExpiredEntriesIncludingUnreadOnes() {
        AtomicLong now = new AtomicLong(0);
        BoundedTtlCache<String> cache = new BoundedTtlCache<>(20, 10, TimeUnit.MILLISECONDS,
                BoundedTtlCache.EvictionOrder.INSERTION, now::get);
        for (int i = 0; i < 12; i++) cache.put("old-" + i, "old");
        now.set(9);
        cache.put("live", "new");
        now.set(11);
        cache.evictExpired();
        assertEquals(1, cache.size());
        assertEquals("new", cache.get("live"));
    }

    @Test
    void concurrentWritesAndReadsRespectCapacity() throws Exception {
        BoundedTtlCache<Integer> cache = new BoundedTtlCache<>(16, 1, TimeUnit.MINUTES);
        var executor = Executors.newFixedThreadPool(4);
        try {
            List<Future<?>> writes = new ArrayList<>();
            for (int i = 0; i < 200; i++) {
                int value = i;
                writes.add(executor.submit(() -> {
                    String key = "key-" + value;
                    if (value % 2 == 0) cache.put(key, value);
                    else cache.put(key, value, 1, TimeUnit.MINUTES);
                    Integer read = cache.get(key);
                    if (read != null) assertEquals(value, read.intValue());
                    assertTrue(cache.size() <= 16);
                }));
            }
            for (Future<?> write : writes) write.get(5, TimeUnit.SECONDS);
            assertEquals(16, cache.size());
        } finally {
            executor.shutdownNow();
        }
    }
}
