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

    public BoundedTtlCache(int maxSize, long ttl, TimeUnit unit, EvictionOrder order, LongSupplier clock) {
        this.maxSize = maxSize;
        this.ttlMillis = unit.toMillis(ttl);
        this.clock = Objects.requireNonNull(clock);
        this.store = new LinkedHashMap<>(maxSize, 0.75f, Objects.requireNonNull(order) == EvictionOrder.ACCESS) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, CacheEntry<T>> eldest) {
                return size() > BoundedTtlCache.this.maxSize;
            }
        };
    }

    public synchronized T get(String key) {
        CacheEntry<T> entry = store.get(key);
        if (entry == null) return null;
        if (clock.getAsLong() > entry.expiresAt()) { store.remove(key); return null; }
        return entry.value();
    }

    public synchronized void put(String key, T value) {
        putMillis(key, value, ttlMillis);
    }

    /** 单条记录可覆盖默认保留时间，读取不会延长其有效期。 */
    public synchronized void put(String key, T value, long ttl, TimeUnit unit) {
        putMillis(key, value, unit.toMillis(ttl));
    }

    public synchronized void clear() { store.clear(); }

    public synchronized int size() { return store.size(); }

    /** 供持有大型临时制品的调用方主动释放全部过期条目。 */
    public synchronized void evictExpired() {
        long now = clock.getAsLong();
        store.values().removeIf(entry -> now > entry.expiresAt());
    }

    // ── 私有 ─────────────────────────────────────────────────────────────────

    private void putMillis(String key, T value, long duration) {
        evictExpiredSample();
        long now = clock.getAsLong();
        long expiresAt;
        try {
            expiresAt = Math.addExact(now, duration);
        } catch (ArithmeticException overflow) {
            expiresAt = duration >= 0 ? Long.MAX_VALUE : Long.MIN_VALUE;
        }
        store.put(key, new CacheEntry<>(value, expiresAt));
    }

    private void evictExpiredSample() {
        if (store.isEmpty()) return;
        long now = clock.getAsLong();
        var it = store.values().iterator();
        for (int checked = 0; it.hasNext() && checked < EVICT_SAMPLE; checked++) {
            if (now > it.next().expiresAt()) it.remove();
        }
    }

    private record CacheEntry<T>(T value, long expiresAt) {}
}
