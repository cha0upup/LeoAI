package org.leo.ai.agent;

import com.alibaba.fastjson.JSON;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.service.tool.ToolExecutionResult;
import dev.langchain4j.service.tool.ToolExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.leo.ai.config.AiAgentProperties;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiToolExecutionBoundaryTest {

    private final List<ExecutorService> executors = new ArrayList<>();

    @AfterEach
    void tearDown() {
        executors.forEach(ExecutorService::shutdownNow);
        AiToolContext.clear();
    }

    @Test
    void wrapsSuccessfulResultsInStableProtocol() {
        AiToolExecutionBoundary boundary = boundary(5_000, 4_000);

        ToolExecutionResult result = boundary.execute(
                AiToolAuthorizationPolicy.AgentScope.PLATFORM,
                descriptor("getStatus", AiToolOperation.READ_ONLY),
                (request, memoryId) -> "ready",
                request("call-1", "getStatus"),
                context("memory-1"));

        Map<String, Object> envelope = json(result.resultText());
        assertEquals(true, envelope.get("ok"));
        assertEquals("leo.tool.result.v1", envelope.get("protocol"));
        assertEquals("OK", envelope.get("code"));
        assertEquals("ready", envelope.get("data"));
        assertEquals(false, envelope.get("retryable"));
        assertEquals("READ_ONLY", metadata(envelope).get("operation"));
    }

    @Test
    void deduplicatesWriteToolsByToolCallId() {
        AiToolExecutionBoundary boundary = boundary(5_000, 4_000);
        AtomicInteger calls = new AtomicInteger();
        ToolExecutor executor = (request, memoryId) -> "created-" + calls.incrementAndGet();
        ToolExecutionRequest request = request("same-call", "createItem");

        ToolExecutionResult first = boundary.execute(
                AiToolAuthorizationPolicy.AgentScope.PLATFORM,
                "createItem", executor, request, context("memory-1"));
        ToolExecutionResult second = boundary.execute(
                AiToolAuthorizationPolicy.AgentScope.PLATFORM,
                "createItem", executor, request, context("memory-1"));

        assertEquals(1, calls.get());
        assertEquals("created-1", json(first.resultText()).get("data"));
        assertEquals(true, metadata(json(second.resultText())).get("deduplicated"));
    }

    @Test
    void doesNotDeduplicateReadOnlyTools() {
        AiToolExecutionBoundary boundary = boundary(5_000, 4_000);
        AtomicInteger calls = new AtomicInteger();
        ToolExecutor executor = (request, memoryId) -> "read-" + calls.incrementAndGet();
        ToolExecutionRequest request = request("same-call", "getItem");

        boundary.execute(AiToolAuthorizationPolicy.AgentScope.PLATFORM,
                descriptor("getItem", AiToolOperation.READ_ONLY),
                executor, request, context("memory-1"));
        boundary.execute(AiToolAuthorizationPolicy.AgentScope.PLATFORM,
                descriptor("getItem", AiToolOperation.READ_ONLY),
                executor, request, context("memory-1"));

        assertEquals(2, calls.get());
    }

    @Test
    void rejectsReusedWriteCallIdWithDifferentArguments() {
        AiToolExecutionBoundary boundary = boundary(5_000, 4_000);
        AtomicInteger calls = new AtomicInteger();
        ToolExecutor executor = (request, memoryId) -> "write-" + calls.incrementAndGet();

        boundary.execute(AiToolAuthorizationPolicy.AgentScope.PLATFORM,
                "createItem", executor,
                request("same-call", "createItem", "{\"name\":\"first\"}"),
                context("memory-1"));
        AiToolException conflict = assertThrows(AiToolException.class,
                () -> boundary.execute(AiToolAuthorizationPolicy.AgentScope.PLATFORM,
                        "createItem", executor,
                        request("same-call", "createItem", "{\"name\":\"second\"}"),
                        context("memory-1")));

        assertEquals("TOOL_CALL_ID_CONFLICT", conflict.code());
        assertEquals(1, calls.get());
    }

    @Test
    void sameProviderCallIdCanBeUsedInDifferentAgentInvocations() {
        AiToolExecutionBoundary boundary = boundary(5_000, 4_000);
        AtomicInteger calls = new AtomicInteger();
        ToolExecutor executor = (request, memoryId) -> "write-" + calls.incrementAndGet();
        ToolExecutionRequest request = request("provider-call-1", "createItem");

        boundary.execute(AiToolAuthorizationPolicy.AgentScope.PLATFORM,
                "createItem", executor, request,
                context("memory-1", java.util.UUID.randomUUID()));
        boundary.execute(AiToolAuthorizationPolicy.AgentScope.PLATFORM,
                "createItem", executor, request,
                context("memory-1", java.util.UUID.randomUUID()));

        assertEquals(2, calls.get());
    }

    @Test
    void classifiesReadTimeoutAsRetryableAndWriteTimeoutAsUnknownState() {
        AiToolExecutionBoundary boundary = boundary(20, 4_000);
        ToolExecutor slow = (request, memoryId) -> {
            try {
                Thread.sleep(1_000);
                return "late";
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted", interrupted);
            }
        };

        AiToolException readTimeout = assertThrows(AiToolException.class,
                () -> boundary.execute(AiToolAuthorizationPolicy.AgentScope.PLATFORM,
                        descriptor("getSlow", AiToolOperation.READ_ONLY),
                        slow, request("read-timeout", "getSlow"),
                        context("memory-1")));
        AiToolException writeTimeout = assertThrows(AiToolException.class,
                () -> boundary.execute(AiToolAuthorizationPolicy.AgentScope.PLATFORM,
                        "createSlow", slow, request("write-timeout", "createSlow"),
                        context("memory-1")));

        assertEquals("TOOL_TIMEOUT", readTimeout.code());
        assertEquals(AiToolException.Recovery.MODEL, readTimeout.recovery());
        assertEquals("TOOL_TIMEOUT_UNKNOWN", writeTimeout.code());
        assertEquals(AiToolException.Recovery.USER, writeTimeout.recovery());
    }

    @Test
    void interruptingOwnerCancelsWorkerAndPreservesWriteDeduplication() throws Exception {
        AiToolExecutionBoundary boundary = boundary(5_000, 4_000);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch cancelled = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        CompletableFuture<AiToolException> failure = new CompletableFuture<>();
        ToolExecutor tool = (request, memoryId) -> {
            calls.incrementAndGet();
            started.countDown();
            try {
                new CountDownLatch(1).await();
                return "unexpected";
            } catch (InterruptedException error) {
                cancelled.countDown();
                Thread.currentThread().interrupt();
                throw new IllegalStateException(error);
            }
        };
        Thread owner = new Thread(() -> {
            try {
                boundary.execute(AiToolAuthorizationPolicy.AgentScope.PLATFORM,
                        "createItem", tool, request("cancel-call", "createItem"), context("memory-1"));
                failure.completeExceptionally(new AssertionError("expected cancellation"));
            } catch (AiToolException error) {
                failure.complete(error);
            }
        });
        try {
            owner.start();
            assertTrue(started.await(5, TimeUnit.SECONDS));
            owner.interrupt();
            assertEquals("TOOL_INTERRUPTED", failure.get(5, TimeUnit.SECONDS).code());
            assertTrue(cancelled.await(5, TimeUnit.SECONDS));
            AiToolException duplicate = assertThrows(AiToolException.class, () ->
                    boundary.execute(AiToolAuthorizationPolicy.AgentScope.PLATFORM,
                            "createItem", tool, request("cancel-call", "createItem"), context("memory-1")));
            assertEquals("TOOL_INTERRUPTED", duplicate.code());
            assertEquals(1, calls.get());
        } finally {
            owner.interrupt();
            owner.join(5_000);
        }
    }

    @Test
    void truncatesLargeResultAndArchivesFullContentPerMemoryId() {
        AiToolResultArchive archive = new AiToolResultArchive();
        AiToolExecutionBoundary boundary = boundary(5_000, 1_024, archive);
        String full = "x".repeat(5_000);

        ToolExecutionResult result = boundary.execute(
                AiToolAuthorizationPolicy.AgentScope.PUPPET_NODE,
                descriptor("getLargeReport", AiToolOperation.READ_ONLY),
                (request, memoryId) -> full,
                request("large-call", "getLargeReport"),
                context("session-1:thread-1"));

        assertTrue(result.resultText().length() <= 1_024);
        Map<String, Object> envelope = json(result.resultText());
        Map<String, Object> metadata = metadata(envelope);
        assertEquals(true, metadata.get("truncated"));
        String archiveId = String.valueOf(metadata.get("archiveId"));
        assertNotNull(archiveId);
        AiToolResultArchive.ArchivePage page = archive.page(
                "session-1:thread-1", archiveId, 0, 8_000);
        assertNotNull(page);
        assertEquals(full, page.content());
        assertFalse(page.hasMore());
        assertEquals(null, archive.page("other-memory", archiveId, 0, 100));
    }

    private AiToolExecutionBoundary boundary(long timeoutMs, int maxChars) {
        return boundary(timeoutMs, maxChars, new AiToolResultArchive());
    }

    private AiToolExecutionBoundary boundary(long timeoutMs, int maxChars,
                                               AiToolResultArchive archive) {
        AiAgentProperties properties = new AiAgentProperties();
        properties.getPlatform().getMain().setToolTimeoutMs(timeoutMs);
        properties.getPuppetNode().getMain().setToolTimeoutMs(timeoutMs);
        properties.getPlatform().getMain().setMaxToolResultChars(maxChars);
        properties.getPuppetNode().getMain().setMaxToolResultChars(maxChars);
        ExecutorService executor = Executors.newFixedThreadPool(4);
        executors.add(executor);
        return new AiToolExecutionBoundary(properties, executor, archive);
    }

    private static ToolExecutionRequest request(String id, String name) {
        return request(id, name, "{}");
    }

    private static AiToolDescriptor descriptor(String name, AiToolOperation operation) {
        return new AiToolDescriptor(name, AiToolKind.QUERY, operation,
                false, false, true, true);
    }

    private static ToolExecutionRequest request(String id, String name, String arguments) {
        return ToolExecutionRequest.builder()
                .id(id).name(name).arguments(arguments).build();
    }

    private static InvocationContext context(String memoryId) {
        return InvocationContext.builder().chatMemoryId(memoryId).build();
    }

    private static InvocationContext context(String memoryId, java.util.UUID invocationId) {
        return InvocationContext.builder()
                .chatMemoryId(memoryId)
                .invocationId(invocationId)
                .build();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> json(String value) {
        return JSON.parseObject(value, Map.class);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> metadata(Map<String, Object> envelope) {
        return (Map<String, Object>) envelope.get("metadata");
    }
}
