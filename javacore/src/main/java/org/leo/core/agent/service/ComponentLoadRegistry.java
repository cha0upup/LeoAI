package org.leo.core.agent.service;

import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.function.LongSupplier;

/**
 * Java Component 的节点级加载注册表。
 *
 * <p>同一个 JavaAgentNode 的全部 ComponentService 共享此实例，从而合并并发加载，
 * 避免相同类名被同一 ClassLoader 重复 define。注册表同时提供有界 LRU/TTL 状态和
 * 连续失败冷却。</p>
 */
public final class ComponentLoadRegistry {

    private final LinkedHashMap<String, HostState> hosts =
            new LinkedHashMap<String, HostState>(16, 0.75f, true);
    private final Map<String, Flight> flights = new LinkedHashMap<String, Flight>();
    private int maxHosts = 128;
    private int maxComponentsPerHost = 128;
    private long ttlMillis = 7L * 24L * 60L * 60L * 1000L;
    private int failureThreshold = 3;
    private long failureCooldownMillis = 30_000L;
    private LongSupplier clock = System::currentTimeMillis;
    private long generation;

    public Map<String, Object> loadOnce(String hostId, String componentName,
                                        LoadAction action) throws Exception {
        String host = hostKey(hostId);
        String component = componentKey(componentName);
        String key = host + '\u0000' + component;
        Flight flight;
        boolean leader = false;
        synchronized (this) {
            long now = clock.getAsLong();
            evictExpired(now);
            HostState state = state(host, now, true);
            if (state.components.contains(component)) return cachedSuccess();
            FailureState failure = state.failures.get(component);
            if (failure != null && failure.count >= failureThreshold && now < failure.blockedUntil) {
                return cooldownResult(failure.blockedUntil - now);
            }
            flight = flights.get(key);
            if (flight == null) {
                flight = new Flight(generation);
                flights.put(key, flight);
                leader = true;
            }
        }

        if (!leader) return await(flight);

        try {
            Map<String, Object> result = action.load();
            Map<String, Object> snapshot = copy(result);
            synchronized (this) {
                long now = clock.getAsLong();
                if (flight.generation == generation) {
                    HostState state = state(host, now, true);
                    if (isSuccess(snapshot)) {
                        markLoaded(state, component);
                        state.failures.remove(component);
                    } else {
                        recordFailure(state, component, now);
                    }
                }
                flight.result = snapshot;
            }
            return copy(snapshot);
        } catch (Throwable throwable) {
            synchronized (this) {
                if (flight.generation == generation) {
                    recordFailure(state(host, clock.getAsLong(), true), component, clock.getAsLong());
                }
                flight.failure = throwable;
            }
            if (throwable instanceof Exception) throw (Exception) throwable;
            if (throwable instanceof Error) throw (Error) throwable;
            throw new IllegalStateException(throwable);
        } finally {
            synchronized (this) {
                if (flights.get(key) == flight) flights.remove(key);
                flight.done.countDown();
            }
        }
    }

    public synchronized boolean contains(String hostId, String componentName) {
        long now = clock.getAsLong();
        evictExpired(now);
        HostState state = state(hostKey(hostId), now, false);
        return state != null && state.components.contains(componentKey(componentName));
    }

    public synchronized Set<String> snapshot(String hostId) {
        long now = clock.getAsLong();
        evictExpired(now);
        HostState state = state(hostKey(hostId), now, false);
        if (state == null) return Collections.emptySet();
        state.lastAccessMillis = now;
        return Collections.unmodifiableSet(new LinkedHashSet<String>(state.components));
    }

    public synchronized void seed(String hostId, Set<String> componentNames) {
        if (hostId == null || componentNames == null) return;
        HostState state = state(hostKey(hostId), clock.getAsLong(), true);
        for (String component : componentNames) {
            if (component == null || component.trim().isEmpty()) continue;
            markLoaded(state, component.trim());
            state.failures.remove(component.trim());
        }
    }

    public synchronized void configureCache(int hostLimit, int componentLimit, long idleTtlMillis) {
        maxHosts = Math.max(1, hostLimit);
        maxComponentsPerHost = Math.max(1, componentLimit);
        ttlMillis = Math.max(1L, idleTtlMillis);
        evictExpired(clock.getAsLong());
        trimHosts();
        for (HostState state : hosts.values()) trimComponents(state);
    }

    public synchronized void configureFailurePolicy(int threshold, long cooldownMillis) {
        failureThreshold = Math.max(1, threshold);
        failureCooldownMillis = Math.max(1L, cooldownMillis);
    }

    synchronized void setClock(LongSupplier value) {
        clock = value == null ? System::currentTimeMillis : value;
    }

    public synchronized void clear() {
        generation++;
        hosts.clear();
        flights.clear();
    }

    /**
     * Invalidates one logical component so an explicit reload reaches the agent instead of
     * returning the regular load cache hit.
     */
    public synchronized void invalidate(String hostId, String componentName) {
        String host = hostKey(hostId);
        String component = componentKey(componentName);
        HostState state = state(host, clock.getAsLong(), false);
        if (state != null) {
            state.components.remove(component);
            state.failures.remove(component);
        }
        generation++;
        flights.remove(host + '\u0000' + component);
    }

    private Map<String, Object> await(Flight flight) throws Exception {
        try {
            flight.done.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Component load wait interrupted", e);
        }
        if (flight.failure instanceof Exception) throw (Exception) flight.failure;
        if (flight.failure instanceof Error) throw (Error) flight.failure;
        return copy(flight.result);
    }

    private HostState state(String host, long now, boolean create) {
        HostState state = hosts.get(host);
        if (state == null && create) {
            state = new HostState(now);
            hosts.put(host, state);
            trimHosts();
        }
        if (state != null) state.lastAccessMillis = now;
        return state;
    }

    private void markLoaded(HostState state, String component) {
        state.components.remove(component);
        state.components.add(component);
        trimComponents(state);
    }

    private void recordFailure(HostState state, String component, long now) {
        FailureState failure = state.failures.get(component);
        if (failure == null) {
            failure = new FailureState();
            state.failures.put(component, failure);
        }
        failure.count++;
        if (failure.count >= failureThreshold) failure.blockedUntil = now + failureCooldownMillis;
        while (state.failures.size() > maxComponentsPerHost) {
            Iterator<String> iterator = state.failures.keySet().iterator();
            iterator.next();
            iterator.remove();
        }
    }

    private void trimComponents(HostState state) {
        while (state.components.size() > maxComponentsPerHost) {
            Iterator<String> iterator = state.components.iterator();
            String removed = iterator.next();
            iterator.remove();
            state.failures.remove(removed);
        }
        while (state.failures.size() > maxComponentsPerHost) {
            Iterator<String> iterator = state.failures.keySet().iterator();
            iterator.next();
            iterator.remove();
        }
    }

    private void trimHosts() {
        while (hosts.size() > maxHosts) {
            Iterator<Map.Entry<String, HostState>> iterator = hosts.entrySet().iterator();
            iterator.next();
            iterator.remove();
        }
    }

    private void evictExpired(long now) {
        Iterator<Map.Entry<String, HostState>> iterator = hosts.entrySet().iterator();
        while (iterator.hasNext()) {
            if (now - iterator.next().getValue().lastAccessMillis >= ttlMillis) iterator.remove();
        }
    }

    private String hostKey(String value) {
        return value == null || value.trim().isEmpty() ? "bootstrap" : value.trim();
    }

    private String componentKey(String value) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException("componentName is empty");
        return value.trim();
    }

    private boolean isSuccess(Map<String, Object> result) {
        return result != null && result.get("code") instanceof Number
                && ((Number) result.get("code")).intValue() == 200;
    }

    private Map<String, Object> cachedSuccess() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("code", Integer.valueOf(200));
        result.put("cached", Boolean.TRUE);
        result.put("msg", "Component already loaded");
        return result;
    }

    private Map<String, Object> cooldownResult(long remainingMillis) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("code", Integer.valueOf(503));
        result.put("msg", "Component load cooling down");
        result.put("retryAfterMillis", Long.valueOf(Math.max(1L, remainingMillis)));
        return result;
    }

    private Map<String, Object> copy(Map<String, Object> value) {
        return value == null ? new LinkedHashMap<String, Object>()
                : new LinkedHashMap<String, Object>(value);
    }

    public interface LoadAction {
        Map<String, Object> load() throws Exception;
    }

    private static final class HostState {
        private final Set<String> components = new LinkedHashSet<String>();
        private final Map<String, FailureState> failures = new LinkedHashMap<String, FailureState>();
        private long lastAccessMillis;

        private HostState(long now) { lastAccessMillis = now; }
    }

    private static final class FailureState {
        private int count;
        private long blockedUntil;
    }

    private static final class Flight {
        private final CountDownLatch done = new CountDownLatch(1);
        private final long generation;
        private Map<String, Object> result;
        private Throwable failure;

        private Flight(long generation) { this.generation = generation; }
    }
}
