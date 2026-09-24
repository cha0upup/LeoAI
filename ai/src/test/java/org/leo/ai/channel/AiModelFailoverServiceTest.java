package org.leo.ai.channel;

import org.junit.jupiter.api.Test;
import org.leo.ai.service.AiErrorClassifier;
import org.leo.core.entity.AiModelConfig;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiModelFailoverServiceTest {

    private final AiErrorClassifier classifier = new AiErrorClassifier();

    @Test
    void createsServiceThroughSpringConstructorInjection() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(AiModelConfigService.class, () -> mock(AiModelConfigService.class));
            context.register(AiModelFailoverService.class);
            context.refresh();

            assertNotNull(context.getBean(AiModelFailoverService.class));
        }
    }

    @Test
    void circuitWithoutFallbackRejectsAndAllowsOnlyOneRecoveryRequest() throws Exception {
        var clock = new java.util.concurrent.atomic.AtomicLong(1000L);
        AiModelFailoverService service = new AiModelFailoverService(mock(AiModelConfigService.class), clock::get);
        AiModelConfig model = model(1, "primary", null);
        service.recordFailure(1, classifier.classify("HTTP 503 Service Unavailable"));
        service.recordFailure(1, classifier.classify("HTTP 503 Service Unavailable"));
        assertThrows(IllegalStateException.class, () -> service.selectForExecution(model));
        clock.addAndGet(120_001L);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(8);
        try {
            var attempts = new java.util.ArrayList<java.util.concurrent.Callable<Boolean>>();
            for (int i = 0; i < 8; i++) attempts.add(() -> {
                try { service.selectForExecution(model); return true; }
                catch (IllegalStateException unavailable) { return false; }
            });
            int accepted = 0;
            for (var result : executor.invokeAll(attempts)) if (result.get()) accepted++;
            assertEquals(1, accepted);
            assertEquals("half_open", service.snapshot(1).status());
        } finally {
            executor.shutdownNow();
        }
        service.recordFailure(1, classifier.classify("HTTP 502 Bad Gateway"));
        assertThrows(IllegalStateException.class, () -> service.selectForExecution(model));
        clock.addAndGet(120_001L);
        assertEquals(1, service.selectForExecution(model).effectiveConfig().getId());
        service.recordSuccess(1);
        assertEquals(1, service.selectForExecution(model).effectiveConfig().getId());
        assertFalse(service.snapshot(1).circuitOpen());
    }

    @Test
    void anAbandonedRecoveryPermitExpires() {
        var clock = new java.util.concurrent.atomic.AtomicLong(1000L);
        AiModelFailoverService service = new AiModelFailoverService(mock(AiModelConfigService.class), clock::get);
        AiModelConfig model = model(1, "primary", null);
        service.recordFailure(1, classifier.classify("timeout"));
        service.recordFailure(1, classifier.classify("timeout"));
        clock.addAndGet(120_001L);
        service.selectForExecution(model);
        assertThrows(IllegalStateException.class, () -> service.selectForExecution(model));
        clock.addAndGet(300_001L);
        assertEquals(1, service.selectForExecution(model).effectiveConfig().getId());
    }

    @Test
    void switchesOnlyNewSelectionsAfterTransientFailureCircuitOpens() {
        AiModelConfigService configService = mock(AiModelConfigService.class);
        AiModelFailoverService service = new AiModelFailoverService(configService);
        AiModelConfig primary = model(1, "主模型", 2);
        AiModelConfig fallback = model(2, "备用模型", null);
        when(configService.resolve(2)).thenReturn(fallback);

        service.recordFailure(1, classifier.classify("request timed out"));
        assertFalse(service.snapshot(1).circuitOpen());
        assertEquals(1, service.selectForExecution(primary).effectiveConfig().getId());

        service.recordFailure(1, classifier.classify("request timed out"));
        AiModelFailoverService.ModelSelection selection = service.selectForExecution(primary);

        assertTrue(selection.failover());
        assertEquals(2, selection.effectiveConfig().getId());
        assertTrue(service.snapshot(1).circuitOpen());

        service.recordSuccess(1);
        assertFalse(service.snapshot(1).circuitOpen());
        assertEquals(1, service.selectForExecution(primary).effectiveConfig().getId());
    }

    @Test
    void doesNotOpenCircuitForConfigurationFailures() {
        AiModelFailoverService service = new AiModelFailoverService(mock(AiModelConfigService.class));

        service.recordFailure(9, classifier.classify("HTTP 401 Unauthorized"));
        service.recordFailure(9, classifier.classify("HTTP 401 Unauthorized"));

        AiModelFailoverService.HealthSnapshot snapshot = service.snapshot(9);
        assertFalse(snapshot.circuitOpen());
        assertEquals("auth", snapshot.lastCategory());
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
