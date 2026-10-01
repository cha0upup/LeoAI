package org.leo.ai.agent;

import org.junit.jupiter.api.Test;
import org.leo.ai.service.AutoReconAppendService;
import org.leo.ai.service.ReconSummaryDigestService;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.lang.reflect.Method;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiAsyncExecutionConfigTest {

    @Test
    void backgroundAiWorkUsesDedicatedExecutor() throws Exception {
        ThreadPoolTaskExecutor executor =
                new AiAsyncExecutionConfig().aiBackgroundTaskExecutor();
        executor.initialize();
        try {
            Future<String> threadName =
                    executor.submit(() -> Thread.currentThread().getName());

            assertTrue(threadName.get().startsWith("ai-background-"));
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void modelBackedAsyncMethodsCannotFallBackToTaskScheduler() throws Exception {
        assertDedicatedExecutor(
                ReconSummaryDigestService.class.getMethod(
                        "generateAndSaveAsync",
                        org.leo.core.session.AgentNodeSession.class));
        assertDedicatedExecutor(
                AutoReconAppendService.class.getMethod(
                        "analyzeAndAppend",
                        String.class, String.class, String.class));
    }

    private static void assertDedicatedExecutor(Method method) {
        Async async = method.getAnnotation(Async.class);
        assertEquals(AiAsyncExecutionConfig.BACKGROUND_EXECUTOR, async.value());
    }
}
