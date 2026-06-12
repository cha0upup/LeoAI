package org.leo.core.util;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 有界 TTL 缓存。
 *
 * <p>基于 {@link LinkedHashMap} 的 LRU 淘汰策略，结合条目级 TTL 过期机制。
 * 超出 {@code maxSize} 时自动驱逐最久未访问的条目；每次 {@link #put} 时
 * 对最多 {@value #EVICT_SAMPLE} 个最旧条目做过期采样清理。
 *
 * <p>所有公开方法均为线程安全（{@code synchronized}）。
 */
public final class BoundedTtlCache {

    private static final int EVICT_SAMPLE = 8;

    private final int maxSize;
    private final long ttlMillis;
    private final LinkedHashMap<String, CacheEntry> store;

    public BoundedTtlCache(int maxSize, long ttl, TimeUnit unit) {
        this.maxSize   = maxSize;
        this.ttlMillis = unit.toMillis(ttl);
        this.store     = new LinkedHashMap<>(maxSize, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, CacheEntry> eldest) {
                return size() > BoundedTtlCache.this.maxSize;
            }
        };
    }

    public synchronized Object get(String key) {
        CacheEntry entry = store.get(key);
        if (entry == null) return null;
        if (entry.isExpired()) { store.remove(key); return null; }
        return entry.value;
    }

    public synchronized void put(String key, Object value) {
        evictExpiredSample();
        store.put(key, new CacheEntry(value, ttlMillis));
    }

    public synchronized Object remove(String key) {
        CacheEntry entry = store.remove(key);
        return entry != null ? entry.value : null;
    }

    /**
     * 删除所有 key 以 {@code prefix} 开头的缓存条目（前缀失效）。
     */
    public synchronized void removeByPrefix(String prefix) {
        if (prefix == null || prefix.isEmpty()) return;
        store.entrySet().removeIf(e -> e.getKey().startsWith(prefix));
    }

    public synchronized void clear() { store.clear(); }

    public synchronized int size() { return store.size(); }

    // ── 私有 ─────────────────────────────────────────────────────────────────

    private void evictExpiredSample() {
        if (store.isEmpty()) return;
        int checked = 0;
        java.util.Iterator<Map.Entry<String, CacheEntry>> it = store.entrySet().iterator();
        while (it.hasNext() && checked < EVICT_SAMPLE) {
            if (it.next().getValue().isExpired()) it.remove();
            checked++;
        }
    }

    private static final class CacheEntry {
        final Object value;
        final long expiresAt;

        CacheEntry(Object value, long ttlMillis) {
            this.value     = value;
            this.expiresAt = System.currentTimeMillis() + ttlMillis;
        }

        boolean isExpired() { return System.currentTimeMillis() > expiresAt; }
    }
}
