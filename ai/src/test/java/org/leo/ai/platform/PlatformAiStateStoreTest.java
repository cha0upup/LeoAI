package org.leo.ai.platform;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class PlatformAiStateStoreTest {

    private final String prefix = "state-store-test-" + UUID.randomUUID() + "-";
    private final List<String> stateIds = new ArrayList<>();
    private final List<Consumer<String>> listeners = new ArrayList<>();

    @AfterEach
    void releaseStatesAndListeners() {
        listeners.forEach(PlatformAiStateStore::unregisterDestroyListener);
        stateIds.forEach(PlatformAiStateStore::remove);
    }

    @Test
    void reusesRunningStateWithoutResettingConfigurationOrEvents() {
        String id = id("running");
        List<String> destroyed = new ArrayList<>();
        listen(destroyed::add);
        PlatformAiState original = PlatformAiStateStore.getOrCreate(id, 7);
        assertTrue(original.claimExecution());
        original.offerSseEvent("fixture", Map.of("value", "kept"));
        long sequence = original.getLastSseEventSeq();

        PlatformAiState reused = PlatformAiStateStore.getOrCreate(id, 99);

        assertSame(original, reused);
        assertSame(original, PlatformAiStateStore.get(id));
        assertEquals(7, reused.getAiConfigId());
        assertTrue(reused.isExecuting());
        assertEquals(sequence, reused.getLastSseEventSeq());
        assertTrue(destroyed.isEmpty());
    }

    @Test
    void concurrentCreationPublishesOneFullyConfiguredStateWithoutDestroyingIt() throws Exception {
        String id = id("concurrent");
        List<String> destroyed = new CopyOnWriteArrayList<>();
        listen(destroyed::add);
        var executor = Executors.newFixedThreadPool(8);
        CountDownLatch ready = new CountDownLatch(8);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<PlatformAiState>> pending = new ArrayList<>();
            for (int index = 0; index < 8; index++) {
                pending.add(executor.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    PlatformAiState state = PlatformAiStateStore.getOrCreate(id, 7);
                    assertEquals(7, state.getAiConfigId());
                    return state;
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            PlatformAiState published = pending.get(0).get(5, TimeUnit.SECONDS);
            for (Future<PlatformAiState> result : pending) assertSame(published, result.get(5, TimeUnit.SECONDS));
            assertSame(published, PlatformAiStateStore.get(id));
            assertTrue(destroyed.isEmpty());
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void removalNotifiesOnceAndAllowsAFreshConfiguredState() {
        String id = id("removed");
        List<String> destroyed = new ArrayList<>();
        Consumer<String> listener = listen(destroyed::add);
        PlatformAiStateStore.registerDestroyListener(listener);
        PlatformAiState original = PlatformAiStateStore.getOrCreate(id, 7);

        PlatformAiStateStore.remove(id);
        PlatformAiStateStore.remove(id);

        assertNull(PlatformAiStateStore.get(id));
        assertEquals(List.of(id), destroyed);
        PlatformAiState replacement = PlatformAiStateStore.getOrCreate(id, 9);
        assertNotSame(original, replacement);
        assertEquals(9, replacement.getAiConfigId());
        PlatformAiStateStore.unregisterDestroyListener(listener);
        PlatformAiStateStore.remove(id);
        assertEquals(List.of(id), destroyed);
    }

    @Test
    void failingListenerDoesNotPreventOtherCleanupCallbacks() {
        String id = id("listener-failure");
        List<String> destroyed = new ArrayList<>();
        listen(ignored -> { throw new IllegalStateException("fixture cleanup failure"); });
        listen(destroyed::add);
        PlatformAiStateStore.getOrCreate(id);

        assertDoesNotThrow(() -> PlatformAiStateStore.remove(id));

        assertEquals(List.of(id), destroyed);
        assertNull(PlatformAiStateStore.get(id));
    }

    @Test
    void capacityCleanupKeepsActiveStatesAndTheNewlyPublishedState() {
        List<PlatformAiState> running = new ArrayList<>();
        for (int index = 0; index < 500; index++) {
            PlatformAiState state = PlatformAiStateStore.getOrCreate(id("busy-" + index));
            assertTrue(state.claimExecution());
            running.add(state);
        }

        PlatformAiState incoming = PlatformAiStateStore.getOrCreate(id("incoming"), 9);

        assertSame(incoming, PlatformAiStateStore.get(incoming.getStateId()));
        for (PlatformAiState state : running) {
            assertSame(state, PlatformAiStateStore.get(state.getStateId()));
            assertTrue(state.isExecuting());
        }
    }

    private String id(String suffix) {
        String id = prefix + suffix;
        stateIds.add(id);
        return id;
    }

    private Consumer<String> listen(Consumer<String> callback) {
        Consumer<String> listener = id -> {
            if (id.startsWith(prefix)) callback.accept(id);
        };
        listeners.add(listener);
        PlatformAiStateStore.registerDestroyListener(listener);
        return listener;
    }
}
