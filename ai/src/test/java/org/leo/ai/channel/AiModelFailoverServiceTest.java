package org.leo.ai.channel;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.leo.ai.service.AiErrorClassifier.Classification;
import org.leo.core.entity.AiModelConfig;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.leo.ai.service.AiErrorClassifier.*;
import static org.mockito.Mockito.*;

class AiModelFailoverServiceTest {
    private final AiModelConfigService configs = mock(AiModelConfigService.class);
    private final AtomicLong clock = new AtomicLong(1000L);
    private final AiModelFailoverService service = new AiModelFailoverService(configs, clock::get);

    @Test
    void createsServiceThroughSpringConstructorInjection() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(AiModelConfigService.class, () -> configs);
            context.register(AiModelFailoverService.class);
            context.refresh();

            assertNotNull(context.getBean(AiModelFailoverService.class));
        }
    }

    @Test
    void healthySelectionNeedsNoPersistenceMetadata() {
        AiModelConfig draft = new AiModelConfig();

        var selection = service.selectForExecution(draft);

        assertSame(draft, selection.effectiveConfig());
        assertNull(selection.message());
        verifyNoInteractions(configs);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void fallbackCyclesStopBeforeLookingUpAnAlreadyVisitedModel(int cycleLength) {
        AiModelConfig primary = model(1, "primary", cycleLength == 1 ? 1 : 2);
        when(configs.resolve(2)).thenReturn(model(2, "fallback", 1));
        for (int id = 1; id <= cycleLength; id++) openCircuit(id);

        assertThrows(IllegalStateException.class, () -> service.selectForExecution(primary));

        verify(configs, never()).resolve(1);
        verify(configs, times(cycleLength - 1)).resolve(2);
    }

    @Test
    void circuitWithoutFallbackRejectsAndAllowsOnlyOneRecoveryRequest() throws Exception {
        AiModelConfig model = model(1, "primary", null);
        openCircuit(1);
        assertThrows(IllegalStateException.class, () -> service.selectForExecution(model));
        clock.addAndGet(120_001L);
        var executor = Executors.newFixedThreadPool(8);
        try {
            var attempts = new ArrayList<Callable<Boolean>>();
            for (int i = 0; i < 8; i++) attempts.add(() -> {
                try { service.selectForExecution(model); return true; }
                catch (IllegalStateException unavailable) { return false; }
            });
            int accepted = 0;
            for (var result : executor.invokeAll(attempts, 5, TimeUnit.SECONDS)) if (result.get()) accepted++;
            assertEquals(1, accepted);
            assertEquals("half_open", service.snapshot(1).status());
        } finally {
            executor.shutdownNow();
        }
        service.recordFailure(1, failure(CATEGORY_SERVER_ERROR));
        assertThrows(IllegalStateException.class, () -> service.selectForExecution(model));
        clock.addAndGet(120_001L);
        assertSame(model, service.selectForExecution(model).effectiveConfig());
        service.recordSuccess(1);
        assertSame(model, service.selectForExecution(model).effectiveConfig());
        assertFalse(service.snapshot(1).circuitOpen());
    }

    @Test
    void anAbandonedRecoveryPermitExpires() {
        AiModelConfig model = model(1, "primary", null);
        openCircuit(1);
        clock.addAndGet(120_001L);
        service.selectForExecution(model);
        assertThrows(IllegalStateException.class, () -> service.selectForExecution(model));
        clock.addAndGet(300_001L);
        assertSame(model, service.selectForExecution(model).effectiveConfig());
    }

    @Test
    void switchesOnlyNewSelectionsAfterTransientFailureCircuitOpens() {
        AiModelConfig primary = model(1, "primary", 2);
        AiModelConfig fallback = model(2, "fallback", null);
        when(configs.resolve(2)).thenReturn(fallback);
        assertSame(primary, service.selectForExecution(primary).effectiveConfig());
        openCircuit(1);

        var selection = service.selectForExecution(primary);

        assertSame(fallback, selection.effectiveConfig());
        assertNotNull(selection.message());
        assertTrue(service.snapshot(1).circuitOpen());
        service.recordSuccess(1);
        var recovered = service.selectForExecution(primary);
        assertSame(primary, recovered.effectiveConfig());
        assertNull(recovered.message());
        assertFalse(service.snapshot(1).circuitOpen());
        verify(configs).resolve(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {CATEGORY_RATE_LIMIT, CATEGORY_TIMEOUT, CATEGORY_NETWORK,
            CATEGORY_SERVER_ERROR, CATEGORY_MALFORMED_RESPONSE})
    void transientFailuresOpenTheCircuitOnlyAtTheThreshold(String category) {
        AiModelConfig primary = model(1, "primary", null);
        service.recordFailure(1, failure(category));
        assertEquals(1, service.snapshot(1).consecutiveFailures());
        assertFalse(service.snapshot(1).circuitOpen());
        assertSame(primary, service.selectForExecution(primary).effectiveConfig());

        service.recordFailure(1, failure(category));

        assertTrue(service.snapshot(1).circuitOpen());
        assertThrows(IllegalStateException.class, () -> service.selectForExecution(primary));
        verifyNoInteractions(configs);
    }

    @ParameterizedTest
    @ValueSource(strings = {CATEGORY_AUTH, CATEGORY_MODEL_NOT_FOUND, CATEGORY_UNSUPPORTED_PARAMETER,
            CATEGORY_THINKING_MODE, CATEGORY_TOOL_CALLING, CATEGORY_CONTEXT_LIMIT, CATEGORY_UNKNOWN})
    void configurationFailuresNeverTriggerAutomaticFailover(String category) {
        AiModelConfig primary = model(9, "primary", 2);
        service.recordFailure(9, failure(category));
        service.recordFailure(9, failure(category));

        var snapshot = service.snapshot(9);

        assertFalse(snapshot.circuitOpen());
        assertEquals(0, snapshot.consecutiveFailures());
        assertEquals(category, snapshot.lastCategory());
        assertSame(primary, service.selectForExecution(primary).effectiveConfig());
        verifyNoInteractions(configs);
    }

    @Test
    void followsTheConfiguredChainPastOpenFallbacks() {
        AiModelConfig primary = model(1, "primary", 2);
        AiModelConfig fallback = model(2, "fallback", 3);
        AiModelConfig healthy = model(3, "healthy", null);
        when(configs.resolve(2)).thenReturn(fallback);
        when(configs.resolve(3)).thenReturn(healthy);
        openCircuit(1);
        openCircuit(2);

        assertSame(healthy, service.selectForExecution(primary).effectiveConfig());

        verify(configs).resolve(2);
        verify(configs).resolve(3);
        verifyNoMoreInteractions(configs);
    }

    @Test
    void keepsThePublicHealthShapeAcrossFailureRecoveryAndReset() {
        openCircuit(1);
        var snapshot = service.snapshot(1).toMap();
        assertEquals(Set.of("configId", "status", "circuitOpen", "consecutiveFailures", "openUntil",
                "lastCategory", "lastSuccessAt", "lastFailureAt"), snapshot.keySet());
        assertEquals("open", snapshot.get("status"));
        assertEquals(CATEGORY_TIMEOUT, snapshot.get("lastCategory"));
        assertEquals(1000L, snapshot.get("lastFailureAt"));
        assertNull(snapshot.get("lastSuccessAt"));
        clock.incrementAndGet();

        service.recordSuccess(1);

        var healthy = service.snapshot(1).toMap();
        assertEquals("healthy", healthy.get("status"));
        assertEquals(1001L, healthy.get("lastSuccessAt"));
        assertNull(healthy.get("openUntil"));
        assertNull(healthy.get("lastCategory"));
        service.reset(1);
        assertEquals("unknown", service.snapshot(1).status());
    }

    private void openCircuit(int id) {
        service.recordFailure(id, failure(CATEGORY_TIMEOUT));
        service.recordFailure(id, failure(CATEGORY_TIMEOUT));
    }

    private static Classification failure(String category) {
        return new Classification(category, "fixture failure", null, List.of());
    }

    private static AiModelConfig model(int id, String name, Integer fallbackId) {
        AiModelConfig config = new AiModelConfig();
        config.setId(id);
        config.setName(name);
        config.setEnabled(1);
        config.setFallbackModelId(fallbackId);
        return config;
    }
}
