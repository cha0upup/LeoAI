package org.leo.ai.agent;

import com.alibaba.fastjson.JSON;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.service.tool.ToolExecutionResult;
import dev.langchain4j.service.tool.ToolExecutor;
import org.leo.ai.config.AiAgentProperties;
import org.leo.core.util.json.JsonUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 所有 Agent 工具的最后一道执行边界：超时、写操作幂等、结果协议和超大结果归档。
 */
@Component
public class AiToolExecutionBoundary {

    private static final int MIN_PREVIEW_CHARS = 256;

    private final AiAgentProperties properties;
    private final ExecutorService executor;
    private final AiToolResultArchive archive;
    private final ConcurrentHashMap<CallKey, Invocation> inFlight = new ConcurrentHashMap<>();

    @Autowired
    public AiToolExecutionBoundary(
            AiAgentProperties properties,
            @Qualifier("aiToolBoundaryExecutor") ExecutorService executor,
            AiToolResultArchive archive) {
        this.properties = properties;
        this.executor = executor;
        this.archive = archive;
    }

    /** 测试和非 Spring 使用场景的安全默认值。 */
    public AiToolExecutionBoundary() {
        this(new AiAgentProperties(), daemonExecutor(), new AiToolResultArchive());
    }

    AiToolResultArchive archive() {
        return archive;
    }

    public ToolExecutionResult execute(
            AiToolAuthorizationPolicy.AgentScope scope,
            AiToolDescriptor descriptor,
            ToolExecutor delegate,
            ToolExecutionRequest request,
            InvocationContext context) {
        String toolName = descriptor.name();
        String memoryId = context != null && context.chatMemoryId() != null
                ? String.valueOf(context.chatMemoryId()) : "<no-memory>";
        AiToolOperation operation = descriptor.operation();
        long timeoutMs = settings(scope).getToolTimeoutMs();
        String invocationId = context != null && context.invocationId() != null
                ? context.invocationId().toString() : "<no-invocation>";
        CallKey key = operation.mutatesState() && request != null
                && request.id() != null && !request.id().isBlank()
                ? new CallKey(scope.name(), memoryId, invocationId, request.id()) : null;

        if (key == null) {
            return runOnce(scope, operation, toolName, delegate, request, context, timeoutMs, false);
        }

        cleanupExpired();
        Invocation created = new Invocation(toolName, arguments(request));
        created.contextSnapshot = AiToolContext.capture();
        Invocation existing = inFlight.putIfAbsent(key, created);
        if (existing != null) {
            if (!Objects.equals(existing.toolName, toolName)
                    || !Objects.equals(existing.arguments, arguments(request))) {
                throw AiToolException.modelCorrectable(
                        "TOOL_CALL_ID_CONFLICT",
                        "同一 tool-call ID 被用于不同的工具或参数。",
                        "请为新的工具调用生成全新的 tool-call ID；不要复用冲突 ID。");
            }
            return await(existing, scope, operation, toolName, request, timeoutMs, true);
        }
        return awaitOwner(created, scope, operation, toolName, delegate,
                request, context, timeoutMs);
    }

    ToolExecutionResult execute(
            AiToolAuthorizationPolicy.AgentScope scope,
            String toolName,
            ToolExecutor delegate,
            ToolExecutionRequest request,
            InvocationContext context) {
        AiToolDescriptor descriptor = AiToolDescriptor.conservative(toolName);
        AiToolContext.setToolDescriptor(descriptor);
        return execute(scope, descriptor, delegate, request, context);
    }

    private ToolExecutionResult awaitOwner(
            Invocation invocation,
            AiToolAuthorizationPolicy.AgentScope scope,
            AiToolOperation operation,
            String toolName,
            ToolExecutor delegate,
            ToolExecutionRequest request,
            InvocationContext context,
            long timeoutMs) {
        Future<?> task = executor.submit(() -> {
            AiToolContext.Snapshot snapshot = invocation.contextSnapshot;
            try {
                invocation.result.complete(runDelegate(
                        scope, operation, toolName, delegate, request, context,
                        timeoutMs, false, snapshot));
            } catch (Throwable error) {
                invocation.result.completeExceptionally(error);
            }
        });
        invocation.task = task;
        return await(invocation, scope, operation, toolName, request, timeoutMs, false);
    }

    private ToolExecutionResult await(
            Invocation invocation,
            AiToolAuthorizationPolicy.AgentScope scope,
            AiToolOperation operation,
            String toolName,
            ToolExecutionRequest request,
            long timeoutMs,
            boolean duplicate) {
        try {
            ToolExecutionResult result = invocation.result.get(
                    Math.max(1L, timeoutMs), TimeUnit.MILLISECONDS);
            return duplicate ? withAttribute(result, "deduplicated", true) : result;
        } catch (TimeoutException timeout) {
            AiToolException boundaryError = timeoutError(operation, toolName, timeoutMs);
            invocation.result.completeExceptionally(boundaryError);
            Future<?> task = invocation.task;
            if (task != null) task.cancel(true);
            throw boundaryError;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            AiToolException boundaryError = AiToolException.userActionRequired(
                    "TOOL_INTERRUPTED", "工具执行被中断，执行结果未知。",
                    "请先查询目标状态，不要使用新的 tool-call ID 重复提交。");
            if (!duplicate) {
                invocation.result.completeExceptionally(boundaryError);
                Future<?> task = invocation.task;
                if (task != null) task.cancel(true);
            }
            throw boundaryError;
        } catch (ExecutionException error) {
            Throwable cause = error.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("工具执行失败", cause);
        }
    }

    private ToolExecutionResult runOnce(
            AiToolAuthorizationPolicy.AgentScope scope,
            AiToolOperation operation,
            String toolName,
            ToolExecutor delegate,
            ToolExecutionRequest request,
            InvocationContext context,
            long timeoutMs,
            boolean duplicate) {
        Invocation invocation = new Invocation(toolName, arguments(request));
        invocation.contextSnapshot = AiToolContext.capture();
        return awaitOwner(invocation, scope, operation, toolName,
                delegate, request, context, timeoutMs);
    }

    private ToolExecutionResult runDelegate(
            AiToolAuthorizationPolicy.AgentScope scope,
            AiToolOperation operation,
            String toolName,
            ToolExecutor delegate,
            ToolExecutionRequest request,
            InvocationContext context,
            long timeoutMs,
            boolean duplicate,
            AiToolContext.Snapshot snapshot) {
        long started = System.nanoTime();
        try {
            // 工具在独立保护线程执行，必须显式传播 ThreadLocal 上下文。
            AiToolContext.restore(snapshot);
            ToolExecutionResult raw = delegate.executeWithContext(request, context);
            return normalize(scope, operation, toolName, request, raw,
                    context != null && context.chatMemoryId() != null
                            ? String.valueOf(context.chatMemoryId()) : "<no-memory>",
                    elapsedMs(started), timeoutMs, duplicate);
        } finally {
            AiToolContext.clear();
        }
    }

    private ToolExecutionResult normalize(
            AiToolAuthorizationPolicy.AgentScope scope,
            AiToolOperation operation,
            String toolName,
            ToolExecutionRequest request,
            ToolExecutionResult raw,
            String memoryId,
            long durationMs,
            long timeoutMs,
            boolean duplicate) {
        if (raw == null) {
            throw AiToolException.systemRetryable(
                    "TOOL_EMPTY_RESULT", "工具没有返回结果。", null);
        }
        String rawText;
        try {
            rawText = raw.resultText();
        } catch (RuntimeException unsupportedContent) {
            // 当前 Agent 工具均返回文本/JSON；多媒体结果保留框架原生内容，
            // 同时把边界指标放在 attributes 中供事件和 trace 使用。
            return withAttributes(raw, metadata(
                    scope, operation, toolName, request, durationMs,
                    timeoutMs, false, null, duplicate, -1));
        }

        Object rawData = raw.result();
        if (rawData == null) rawData = rawText;
        int originalChars = rawText != null ? rawText.length() : 0;
        AiAgentProperties.MainAgentConfig config = settings(scope);
        int maxChars = Math.max(512, config.getMaxToolResultChars());
        Map<String, Object> envelope = raw.isError()
                ? errorEnvelope(scope, operation, toolName, request, rawData,
                        durationMs, timeoutMs, false, null, duplicate, originalChars)
                : envelope(scope, operation, toolName, request, rawData,
                        durationMs, timeoutMs, false, null, duplicate, originalChars);
        String encoded = JsonUtil.toJsonString(envelope);
        boolean truncated = encoded.length() > maxChars;
        String archiveId = null;
        if (truncated) {
            Map<String, Object> archiveMeta = new LinkedHashMap<>();
            archiveMeta.put("scope", scope.name());
            archiveMeta.put("operation", operation.name());
            archiveMeta.put("durationMs", durationMs);
            archiveId = archive.put(
                    memoryId,
                    toolName, rawText, archiveMeta, config.getToolArchiveTtlMs());
            int previewChars = Math.max(MIN_PREVIEW_CHARS, maxChars - 1_024);
            do {
                envelope = raw.isError()
                        ? errorEnvelope(scope, operation, toolName, request,
                                preview(rawText, previewChars), durationMs, timeoutMs,
                                true, archiveId, duplicate, originalChars)
                        : envelope(scope, operation, toolName, request,
                                preview(rawText, previewChars), durationMs, timeoutMs,
                                true, archiveId, duplicate, originalChars);
                encoded = JsonUtil.toJsonString(envelope);
                previewChars -= 512;
            } while (encoded.length() > maxChars && previewChars >= MIN_PREVIEW_CHARS);
            if (encoded.length() > maxChars) {
                envelope = raw.isError()
                        ? errorEnvelope(scope, operation, toolName, request,
                                "完整结果已归档，请使用 archiveId 分页读取。",
                                durationMs, timeoutMs, true, archiveId,
                                duplicate, originalChars)
                        : envelope(scope, operation, toolName, request,
                                "完整结果已归档，请使用 archiveId 分页读取。",
                                durationMs, timeoutMs, true, archiveId,
                                duplicate, originalChars);
                encoded = JsonUtil.toJsonString(envelope);
            }
        }
        Map<String, Object> metadata = metadata(
                scope, operation, toolName, request, durationMs, timeoutMs,
                truncated, archiveId, duplicate, originalChars);
        return ToolExecutionResult.builder()
                .isError(raw.isError())
                .result(rawData)
                .resultText(encoded)
                .attributes(metadata)
                .build();
    }

    private Map<String, Object> envelope(
            AiToolAuthorizationPolicy.AgentScope scope,
            AiToolOperation operation,
            String toolName,
            ToolExecutionRequest request,
            Object data,
            long durationMs,
            long timeoutMs,
            boolean truncated,
            String archiveId,
            boolean duplicate,
            int originalChars) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("protocol", "leo.tool.result.v1");
        result.put("code", "OK");
        result.put("message", "工具执行成功");
        result.put("data", data);
        result.put("evidence", java.util.List.of());
        result.put("retryable", false);
        result.put("metadata", metadata(scope, operation, toolName, request,
                durationMs, timeoutMs, truncated, archiveId, duplicate, originalChars));
        return result;
    }

    private Map<String, Object> errorEnvelope(
            AiToolAuthorizationPolicy.AgentScope scope,
            AiToolOperation operation,
            String toolName,
            ToolExecutionRequest request,
            Object data,
            long durationMs,
            long timeoutMs,
            boolean truncated,
            String archiveId,
            boolean duplicate,
            int originalChars) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", false);
        result.put("protocol", "leo.tool.result.v1");
        result.put("code", "TOOL_EXECUTION_FAILED");
        result.put("message", "工具返回失败结果");
        result.put("data", data);
        result.put("evidence", java.util.List.of());
        result.put("retryable", false);
        result.put("metadata", metadata(scope, operation, toolName, request,
                durationMs, timeoutMs, truncated, archiveId, duplicate, originalChars));
        return result;
    }

    private Map<String, Object> metadata(
            AiToolAuthorizationPolicy.AgentScope scope,
            AiToolOperation operation,
            String toolName,
            ToolExecutionRequest request,
            long durationMs,
            long timeoutMs,
            boolean truncated,
            String archiveId,
            boolean duplicate,
            int originalChars) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("scope", scope.name());
        metadata.put("tool", toolName);
        metadata.put("operation", operation.name());
        AiToolDescriptor descriptor = AiToolContext.getToolDescriptor();
        if (descriptor != null) {
            metadata.put("toolKind", descriptor.kind().name());
            metadata.put("terminal", descriptor.terminal());
            metadata.put("exclusive", descriptor.exclusive());
            metadata.put("parallelizable", descriptor.parallelizable());
            metadata.put("businessTool", descriptor.business());
        }
        metadata.put("toolCallId", request != null ? request.id() : null);
        metadata.put("durationMs", durationMs);
        metadata.put("timeoutMs", timeoutMs);
        metadata.put("truncated", truncated);
        metadata.put("originalChars", originalChars);
        metadata.put("deduplicated", duplicate);
        if (archiveId != null) metadata.put("archiveId", archiveId);
        return metadata;
    }

    @SuppressWarnings("unchecked")
    private ToolExecutionResult withAttribute(ToolExecutionResult result, String key, Object value) {
        Map<String, Object> attributes = new LinkedHashMap<>(result.attributes());
        attributes.put(key, value);
        try {
            Map<String, Object> envelope = JSON.parseObject(result.resultText(), Map.class);
            if (envelope != null && envelope.get("metadata") instanceof Map<?, ?> rawMetadata) {
                Map<String, Object> metadata = new LinkedHashMap<>();
                rawMetadata.forEach((metadataKey, metadataValue) ->
                        metadata.put(String.valueOf(metadataKey), metadataValue));
                metadata.put(key, value);
                envelope.put("metadata", metadata);
                return ToolExecutionResult.builder()
                        .isError(result.isError())
                        .result(result.result())
                        .resultText(JsonUtil.toJsonString(envelope))
                        .attributes(attributes)
                        .build();
            }
        } catch (RuntimeException ignored) {
            // 非统一 JSON 结果仅更新 attributes。
        }
        return withAttributes(result, attributes);
    }

    private ToolExecutionResult withAttributes(ToolExecutionResult result, Map<String, Object> attributes) {
        try {
            return ToolExecutionResult.builder()
                    .isError(result.isError())
                    .result(result.result())
                    .resultText(result.resultText())
                    .attributes(attributes)
                    .build();
        } catch (RuntimeException ignored) {
            return ToolExecutionResult.builder()
                    .isError(result.isError())
                    .result(result.result())
                    .resultContents(result.resultContents())
                    .attributes(attributes)
                    .build();
        }
    }

    private AiToolException timeoutError(AiToolOperation operation, String toolName, long timeoutMs) {
        if (operation == AiToolOperation.READ_ONLY) {
            return AiToolException.modelCorrectable(
                    "TOOL_TIMEOUT",
                    "工具 " + toolName + " 执行超过 " + timeoutMs + "ms。",
                    "该工具为只读操作，可以稍后重试或缩小查询范围。");
        }
        return AiToolException.userActionRequired(
                "TOOL_TIMEOUT_UNKNOWN",
                "工具 " + toolName + " 执行超时，写入结果未知。",
                "请先查询目标状态；不要使用新的 tool-call ID 重复提交写入操作。");
    }

    private AiAgentProperties.MainAgentConfig settings(
            AiToolAuthorizationPolicy.AgentScope scope) {
        return scope == AiToolAuthorizationPolicy.AgentScope.PLATFORM
                ? properties.getPlatform().getMain()
                : properties.getPuppetNode().getMain();
    }

    private void cleanupExpired() {
        long ttl = Math.max(1_000L,
                Math.max(properties.getPlatform().getMain().getToolIdempotencyTtlMs(),
                        properties.getPuppetNode().getMain().getToolIdempotencyTtlMs()));
        long cutoff = System.currentTimeMillis() - ttl;
        inFlight.entrySet().removeIf(entry -> entry.getValue().createdAt < cutoff
                && entry.getValue().result.isDone());
    }

    private static String preview(String value, int max) {
        if (value == null) return null;
        if (value.length() <= max) return value;
        return value.substring(0, Math.max(MIN_PREVIEW_CHARS, max))
                + "\n...(已截断，请使用 archiveId 分页读取)";
    }

    private static long elapsedMs(long started) {
        return Math.max(0L, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
    }

    private static String arguments(ToolExecutionRequest request) {
        return request != null && request.arguments() != null
                ? request.arguments() : "{}";
    }

    private static ExecutorService daemonExecutor() {
        java.util.concurrent.atomic.AtomicInteger counter = new java.util.concurrent.atomic.AtomicInteger();
        return java.util.concurrent.Executors.newCachedThreadPool(task -> {
            Thread thread = new Thread(task, "ai-tool-boundary-test-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    private static final class Invocation {
        private final long createdAt = System.currentTimeMillis();
        private final CompletableFuture<ToolExecutionResult> result = new CompletableFuture<>();
        private final String toolName;
        private final String arguments;
        private volatile Future<?> task;
        private volatile AiToolContext.Snapshot contextSnapshot;

        private Invocation(String toolName, String arguments) {
            this.toolName = toolName;
            this.arguments = arguments;
        }
    }

    private record CallKey(String scope, String memoryId,
                           String invocationId, String callId) {
    }
}
