package org.leo.core.util;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * 有界 TTL 缓存。
 *
 * <p>基于 {@link LinkedHashMap}，默认采用 LRU，也支持按写入顺序淘汰。
 * 超出 {@code maxSize} 时自动驱逐最旧条目；每次 {@link #put} 时
 * 对最多 {@value #EVICT_SAMPLE} 个最旧条目做过期采样清理。
 *
 * <p>所有公开方法均为线程安全（{@code synchronized}）。
 */
public final class BoundedTtlCache<T> {

    public enum EvictionOrder { ACCESS, INSERTION }

    private static final int EVICT_SAMPLE = 8;

    private final int maxSize;
    private final long ttlMillis;
    private final LongSupplier clock;
    private final LinkedHashMap<String, CacheEntry<T>> store;

    public BoundedTtlCache(int maxSize, long ttl, TimeUnit unit) {
        this(maxSize, ttl, unit, EvictionOrder.ACCESS);
    }

    public BoundedTtlCache(int maxSize, long ttl, TimeUnit unit, EvictionOrder order) {
        this(maxSize, ttl, unit, order, System::currentTimeMillis);
    }

    BoundedTtlCache(int maxSize, long ttl, TimeUnit unit, EvictionOrder order, LongSupplier clock) {
        this.maxSize   = maxSize;
        this.ttlMillis = unit.toMillis(ttl);
        this.clock = Objects.requireNonNull(clock);
        this.store     = new LinkedHashMap<>(maxSize, 0.75f, Objects.requireNonNull(order) == EvictionOrder.ACCESS) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, CacheEntry<T>> eldest) {
                return size() > BoundedTtlCache.this.maxSize;
            }
        };
    }

    public synchronized T get(String key) {
        CacheEntry<T> entry = store.get(key);
        if (entry == null) return null;
        if (clock.getAsLong() > entry.expiresAt) { store.remove(key); return null; }
        return entry.value;
    }

    public synchronized void put(String key, T value) {
        evictExpiredSample();
        store.put(key, new CacheEntry<>(value, clock.getAsLong() + ttlMillis));
    }

    public synchronized void clear() { store.clear(); }

    public synchronized int size() { return store.size(); }

    /** 供持有大型临时制品的调用方主动释放全部过期条目。 */
    public synchronized void evictExpired() {
        long now = clock.getAsLong();
        store.values().removeIf(entry -> now > entry.expiresAt);
    }

    // ── 私有 ─────────────────────────────────────────────────────────────────

    private void evictExpiredSample() {
        if (store.isEmpty()) return;
        int checked = 0;
        long now = clock.getAsLong();
        java.util.Iterator<Map.Entry<String, CacheEntry<T>>> it = store.entrySet().iterator();
        while (it.hasNext() && checked < EVICT_SAMPLE) {
            if (now > it.next().getValue().expiresAt) it.remove();
            checked++;
        }
    }

    private static final class CacheEntry<T> {
        final T value;
        final long expiresAt;

        CacheEntry(T value, long expiresAt) {
            this.value     = value;
            this.expiresAt = expiresAt;
        }
    }
}
