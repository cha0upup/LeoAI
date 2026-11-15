package org.leo.ai.agent;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.exception.ToolExecutionException;
import dev.langchain4j.internal.Json;
import dev.langchain4j.service.tool.ToolErrorContext;
import dev.langchain4j.service.tool.ToolErrorHandlerResult;
import dev.langchain4j.service.tool.ToolExecution;
import org.leo.core.util.ThrowableUtils;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Agent 工具错误的统一恢复边界。
 *
 * <p>模型能够通过修改参数、资源选择或调用顺序解决的错误会转换成
 * {@code leo.tool.error.v1} 工具结果；系统与致命错误继续抛给 Turn
 * 终态处理。返回模型的内容保留原始诊断值，仅清理控制字符并限制长度。
 */
@Component
public class AiToolErrorHandler {

    static final String PROTOCOL = "leo.tool.error.v1";
    static final int MAX_MODEL_CORRECTION_ATTEMPTS = 3;

    private static final int MAX_SAFE_TEXT_LENGTH = 500;
    private final ConcurrentMap<String, TurnState> activeTurns =
            new ConcurrentHashMap<>();

    public TurnScope beginTurn(Object memoryId) {
        String key = memoryKey(memoryId);
        TurnState state = new TurnState();
        activeTurns.put(key, state);
        return new TurnScope(key, state);
    }

    public ToolErrorHandlerResult handleArguments(
            Throwable error,
            ToolErrorContext context) {
        return modelError(
                "INVALID_TOOL_ARGUMENTS",
                "工具参数不符合声明的 JSON Schema："
                        + safeText(error != null ? error.getMessage() : null,
                        "参数格式或类型错误"),
                "请严格依据工具 Schema 修正缺失字段、字段类型和 JSON 格式后重新调用。",
                context);
    }

    public ToolErrorHandlerResult handleExecution(
            Throwable error,
            ToolErrorContext context) {
        AiToolException domain = findDomainError(error);
        if (domain != null) {
            return switch (domain.recovery()) {
                case MODEL -> modelError(
                        domain.code(), domain.safeMessage(),
                        domain.hint(), context);
                case USER -> userError(
                        domain.code(),
                        domain.safeMessage(), domain.hint(),
                        context);
                case SYSTEM, FATAL -> throw propagate(error);
            };
        }

        if (error instanceof IllegalArgumentException) {
            return modelError(
                    "INVALID_TOOL_INPUT",
                    safeText(error.getMessage(), "工具输入不合法"),
                    "请根据错误信息和工具 Schema 修正参数后重新调用。",
                    context);
        }
        if (error instanceof SecurityException) {
            return userError(
                    "TOOL_PERMISSION_DENIED",
                    "当前操作没有所需权限。",
                    "不要绕过权限或重复调用；请向用户说明需要的权限或改用已授权资源。",
                    context);
        }
        throw propagate(error);
    }

    public ToolExecutionResultMessage handleUnknownTool(
            ToolExecutionRequest request) {
        String toolName = toolName(request);
        Map<String, Object> error = payload(
                "UNKNOWN_TOOL",
                AiToolException.Recovery.MODEL,
                toolName,
                "请求的工具不存在或当前 Agent 未注册该工具。",
                "请从当前已提供的工具列表中选择正确工具名，不要继续调用 "
                        + toolName + "。",
                true, 1);
        return ToolExecutionResultMessage.builder()
                .id(request != null ? request.id() : null)
                .toolName(toolName)
                .text(Json.toJson(error))
                .isError(true)
                .build();
    }

    public void recordSuccess(Object memoryId, String toolName) {
        TurnState state = activeTurns.get(memoryKey(memoryId));
        if (state == null || toolName == null || toolName.isBlank()) {
            return;
        }
        state.attempts.keySet().removeIf(
                fingerprint -> fingerprint.toolName().equals(toolName));
    }

    /**
     * LangChain4j 1.x 的未知工具策略会保留错误正文，但丢失 isError 标志。
     * 领域层统一同时检查标志和协议，避免把未知工具显示或记录成成功。
     */
    public static boolean isErrorResult(ToolExecution execution) {
        return execution != null
                && (execution.hasFailed()
                || isErrorProtocol(execution.result()));
    }

    static boolean isErrorProtocol(String result) {
        return result != null
                && result.contains("\"protocol\":\"" + PROTOCOL + "\"");
    }

    private ToolErrorHandlerResult modelError(
            String code,
            String message,
            String hint,
            ToolErrorContext context) {
        String toolName = toolName(context);
        int attempt = registerAttempt(
                context != null ? context.memoryId() : null,
                new ErrorFingerprint(
                        toolName, code, safeText(message, "工具调用错误")));
        if (attempt > MAX_MODEL_CORRECTION_ATTEMPTS) {
            throw new ToolExecutionException(
                    "模型连续生成相同的无效工具调用，已停止自动纠错: "
                            + toolName + "/" + code);
        }
        boolean retryable =
                attempt < MAX_MODEL_CORRECTION_ATTEMPTS;
        String effectiveHint = retryable
                ? hint
                : "相同错误已连续发生 "
                        + MAX_MODEL_CORRECTION_ATTEMPTS
                        + " 次。不要再次调用该工具；请更换方案或向用户说明。";
        return response(
                code, AiToolException.Recovery.MODEL,
                message, effectiveHint, context,
                retryable, attempt);
    }

    private ToolErrorHandlerResult userError(
            String code,
            String message,
            String hint,
            ToolErrorContext context) {
        String toolName = toolName(context);
        int attempt = registerAttempt(
                context != null ? context.memoryId() : null,
                new ErrorFingerprint(
                        toolName, code,
                        safeText(message, "需要用户处理")));
        if (attempt > 2) {
            throw new ToolExecutionException(
                    "模型重复调用需要用户处理的失败工具，已停止: "
                            + toolName + "/" + code);
        }
        String effectiveHint = attempt == 1
                ? hint
                : "该错误无法由模型修复，且已经重复发生。不要再次调用；请立即向用户说明。";
        return response(
                code, AiToolException.Recovery.USER,
                message, effectiveHint, context,
                false, attempt);
    }

    private ToolErrorHandlerResult response(
            String code,
            AiToolException.Recovery recovery,
            String message,
            String hint,
            ToolErrorContext context,
            boolean retryable,
            int attempt) {
        return ToolErrorHandlerResult.text(Json.toJson(payload(
                code, recovery, toolName(context),
                safeText(message, "工具调用失败"),
                safeText(hint, null),
                retryable, attempt)));
    }

    private Map<String, Object> payload(
            String code,
            AiToolException.Recovery recovery,
            String toolName,
            String message,
            String hint,
            boolean retryable,
            int attempt) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("recoverableBy", recovery.name());
        error.put("tool", toolName);
        error.put("message", message);
        if (hint != null && !hint.isBlank()) {
            error.put("hint", hint);
        }
        error.put("retryable", retryable);
        error.put("attempt", attempt);
        if (recovery == AiToolException.Recovery.MODEL) {
            error.put("maxAttempts", MAX_MODEL_CORRECTION_ATTEMPTS);
        }

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("ok", false);
        envelope.put("protocol", PROTOCOL);
        envelope.put("code", code);
        envelope.put("message", message);
        envelope.put("data", null);
        envelope.put("evidence", java.util.List.of());
        envelope.put("retryable", retryable);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("tool", toolName);
        metadata.put("recoverableBy", recovery.name());
        metadata.put("attempt", attempt);
        if (hint != null && !hint.isBlank()) metadata.put("hint", hint);
        if (recovery == AiToolException.Recovery.MODEL) {
            metadata.put("maxAttempts", MAX_MODEL_CORRECTION_ATTEMPTS);
        }
        envelope.put("metadata", metadata);
        envelope.put("error", error);
        return envelope;
    }

    private int registerAttempt(
            Object memoryId,
            ErrorFingerprint fingerprint) {
        TurnState state = activeTurns.computeIfAbsent(
                memoryKey(memoryId), ignored -> new TurnState());
        return state.attempts
                .computeIfAbsent(
                        fingerprint, ignored -> new AtomicInteger())
                .incrementAndGet();
    }

    private AiToolException findDomainError(Throwable error) {
        for (Throwable current : ThrowableUtils.causes(error)) {
            if (current instanceof AiToolException domain) {
                return domain;
            }
        }
        return null;
    }

    private ToolExecutionException propagate(Throwable error) {
        return error instanceof ToolExecutionException toolError
                ? toolError
                : new ToolExecutionException(Objects.requireNonNullElseGet(
                        error,
                        () -> new IllegalStateException(
                                "未知工具执行错误")));
    }

    private String toolName(ToolErrorContext context) {
        return context != null
                ? toolName(context.toolExecutionRequest())
                : "unknown";
    }

    private String toolName(ToolExecutionRequest request) {
        return safeText(
                request != null ? request.name() : null,
                "unknown");
    }

    private String safeText(String value, String fallback) {
        String text = value != null && !value.isBlank()
                ? value.trim()
                : fallback;
        if (text == null) {
            return null;
        }
        text = text.replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]", " ");
        if (text.length() > MAX_SAFE_TEXT_LENGTH) {
            return text.substring(0, MAX_SAFE_TEXT_LENGTH) + "…";
        }
        return text;
    }

    private String memoryKey(Object memoryId) {
        return memoryId != null
                ? String.valueOf(memoryId)
                : "<no-memory>";
    }

    private static final class TurnState {
        private final ConcurrentMap<ErrorFingerprint, AtomicInteger> attempts =
                new ConcurrentHashMap<>();
    }

    private record ErrorFingerprint(
            String toolName,
            String code,
            String message) {
    }

    public final class TurnScope implements AutoCloseable {
        private final String key;
        private final TurnState state;
        private final AtomicBoolean closed = new AtomicBoolean(false);

        private TurnScope(String key, TurnState state) {
            this.key = key;
            this.state = state;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                activeTurns.remove(key, state);
            }
        }
    }
}
