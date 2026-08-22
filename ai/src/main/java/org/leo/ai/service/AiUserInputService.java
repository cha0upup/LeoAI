package org.leo.ai.service;

import com.alibaba.fastjson.JSON;
import org.leo.ai.agent.AiToolContext;
import org.leo.ai.agent.AiToolCatalog;
import org.leo.ai.agent.AiToolDescriptor;
import org.leo.ai.agent.AiToolOperation;
import org.leo.ai.agent.AiToolException;
import org.leo.ai.platform.PlatformAiState;
import org.leo.ai.platform.PlatformAiStateStore;
import org.leo.ai.thread.AiConversationStoreService;
import org.leo.core.entity.AiUserInputRequest;
import org.leo.core.entity.AiUserInputOption;
import org.leo.core.session.AiThread;
import org.leo.core.session.PuppetNodeSession;
import org.leo.core.session.PuppetNodeSessionContainer;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/** Agent 结构化提问、持久化等待和回答恢复服务。 */
@Service
public class AiUserInputService {

    private static final long DEFAULT_EXPIRES_MS = 24L * 60L * 60L * 1_000L;
    private static final long MAX_EXPIRES_MS = 7L * DEFAULT_EXPIRES_MS;

    private final AiConversationStoreService store;
    private final AiToolCatalog toolCatalog;

    public AiUserInputService(AiConversationStoreService store) {
        this(store, new AiToolCatalog());
    }

    @Autowired
    public AiUserInputService(AiConversationStoreService store, AiToolCatalog toolCatalog) {
        this.store = store;
        this.toolCatalog = toolCatalog;
    }

    public Map<String, Object> request(String requestedType,
                                       String prompt,
                                       List<AiUserInputOption> options,
                                       Boolean allowFreeText,
                                       String actionSummary,
                                       String toolName,
                                       String argumentsJson,
                                       String risk,
                                       Long expiresInSeconds) {
        RuntimeTarget target = requireRuntimeTarget();
        String type = normalizeType(requestedType);
        String normalizedPrompt = requiredText(prompt, "问题内容不能为空", 2_000);
        List<AiUserInputOption> normalizedOptions = normalizeOptions(options);
        // 澄清问题始终提供自定义回答入口；确认问题仍只能使用明确选项。
        boolean requestedFreeText = Boolean.TRUE.equals(allowFreeText);
        boolean freeText = AiUserInputRequest.TYPE_CLARIFICATION.equals(type);
        if (AiUserInputRequest.TYPE_CONFIRMATION.equals(type) && requestedFreeText) {
            throw AiToolException.modelCorrectable(
                    "CONFIRMATION_FREE_TEXT_FORBIDDEN",
                    "操作确认不允许自由输入。",
                    "确认问题只能提供明确的 CONFIRM 与 REJECT 选项，不要启用自由输入。");
        }
        if (!freeText && normalizedOptions.isEmpty()) {
            throw AiToolException.modelCorrectable(
                    "USER_INPUT_OPTIONS_REQUIRED",
                    "不允许自由输入时必须提供至少一个选项。",
                    "提供 1 到 4 个带 label、value、intent 的清晰选项；澄清问题同时保留自定义回答入口。");
        }

        String normalizedToolName = emptyToNull(toolName);
        String argumentsHash = null;
        String normalizedActionSummary = trim(actionSummary, 2_000);
        if (AiUserInputRequest.TYPE_CONFIRMATION.equals(type)) {
            if (normalizedToolName == null || argumentsJson == null || argumentsJson.isBlank()) {
                throw AiToolException.modelCorrectable(
                        "CONFIRMATION_ACTION_REQUIRED",
                        "操作确认必须绑定 toolName 和 argumentsJson。",
                        "传入计划执行的准确工具名与完整参数 JSON；参数改变后必须重新确认。");
            }
            AiToolDescriptor descriptor = toolCatalog.get(normalizedToolName);
            if (descriptor.operation() == AiToolOperation.READ_ONLY || !descriptor.business()) {
                throw AiToolException.modelCorrectable(
                        "CONFIRMATION_ACTION_NOT_BUSINESS_MUTATION",
                        "确认请求只能绑定会改变平台或 Puppet 状态的业务工具。",
                        "只读工具和内部控制工具无需确认；请直接调用对应工具。");
            }
            if (!hasAffirmativeOption(normalizedOptions)
                    || !hasRejectOption(normalizedOptions)) {
                throw AiToolException.modelCorrectable(
                        "CONFIRMATION_OPTIONS_REQUIRED",
                        "操作确认必须同时提供明确的同意和拒绝选项。",
                        "例如提供“确认执行”和“取消”两个选项，且不要只依赖自由输入。");
            }
            if (normalizedActionSummary == null || normalizedActionSummary.isBlank()) {
                throw AiToolException.modelCorrectable(
                        "CONFIRMATION_RISK_DETAILS_REQUIRED",
                        "高风险确认必须说明操作风险、可能后果和回滚方式。",
                        "在 actionSummary 中写明“操作、风险、可能后果、回滚”四项内容后重新请求确认。");
            }
            argumentsHash = confirmationArgumentsHash(argumentsJson);
        }

        AiUserInputRequest existing = store.findPendingUserInputRequest(target.threadId());
        if (existing != null) {
            target.markWaiting();
            return result(existing, true);
        }

        long now = System.currentTimeMillis();
        AiUserInputRequest request = new AiUserInputRequest();
        request.setRequestId("question-" + UUID.randomUUID());
        request.setThreadId(target.threadId());
        request.setTurnId(target.turnId());
        request.setItemId(target.itemId());
        request.setRequestType(type);
        request.setPrompt(normalizedPrompt);
        request.setOptionsJson(normalizedOptions.isEmpty()
                ? null : JSON.toJSONString(normalizedOptions));
        request.setAllowFreeText(freeText);
        request.setActionSummary(normalizedActionSummary);
        request.setToolName(normalizedToolName);
        request.setArgumentsHash(argumentsHash);
        request.setRisk(normalizeRisk(risk, type));
        request.setStatus(AiUserInputRequest.STATUS_PENDING);
        request.setCreatedAt(now);
        request.setExpiresAt(now + expiresMillis(expiresInSeconds));

        AiUserInputRequest persisted = store.createUserInputRequest(request);
        target.markWaiting();
        target.emit(persisted.toMap());
        return result(persisted, persisted != request);
    }

    public String resumePrompt(String threadId,
                               String answerToQuestionId,
                               String guardedMessage) {
        if (answerToQuestionId == null || answerToQuestionId.isBlank()) {
            return guardedMessage;
        }
        AiUserInputRequest request = store.findUserInputRequest(answerToQuestionId);
        if (request == null
                || !request.getThreadId().equals(threadId)
                || !AiUserInputRequest.STATUS_ANSWERED.equals(request.getStatus())) {
            throw new IllegalStateException("关联的用户输入请求不存在或尚未回答");
        }
        StringBuilder context = new StringBuilder();
        context.append("【用户输入恢复上下文】\n");
        context.append("questionId: ").append(request.getRequestId()).append('\n');
        context.append("问题类型: ").append(request.getRequestType()).append('\n');
        context.append("原问题: ").append(request.getPrompt()).append('\n');
        if (request.getActionSummary() != null) {
            context.append("待处理动作: ").append(request.getActionSummary()).append('\n');
        }
        if (request.getToolName() != null) {
            context.append("已确认工具: ").append(request.getToolName()).append('\n');
            context.append("已确认参数哈希: ").append(request.getArgumentsHash()).append('\n');
        }
        context.append("用户回答: ").append(request.getAnswer()).append('\n');
        context.append("请基于该回答从暂停点直接恢复原任务和当前计划，不要致谢、确认收到或复述问题与回答。"
                + "若是拒绝，不要执行被拒绝的动作，应选择低风险替代方案或结束任务。\n\n");
        context.append(guardedMessage != null ? guardedMessage : request.getAnswer());
        return context.toString();
    }

    public AiUserInputRequest findPending(String threadId) {
        return store.findPendingUserInputRequest(threadId);
    }

    private RuntimeTarget requireRuntimeTarget() {
        String sessionId = AiToolContext.requireSessionId();
        String threadId = AiToolContext.getThreadId();
        if (threadId == null || threadId.isBlank()) {
            PlatformAiState state = PlatformAiStateStore.get(sessionId);
            if (state == null) throw unavailable();
            return new RuntimeTarget(
                    state.getStateId(), state.getActiveTurnId(), state.getActiveItemId(),
                    state::markWaitingForUserInput,
                    payload -> state.offerSseEvent("node", payload));
        }
        PuppetNodeSession session = PuppetNodeSessionContainer.getSession(sessionId);
        AiThread thread = session != null ? session.getAiThread(threadId) : null;
        if (thread == null) throw unavailable();
        return new RuntimeTarget(
                threadId, thread.getActiveTurnId(), thread.getActiveItemId(),
                thread::markWaitingForUserInput,
                payload -> thread.offerSseEvent("node", payload));
    }

    private AiToolException unavailable() {
        return AiToolException.userActionRequired(
                "AI_THREAD_UNAVAILABLE",
                "当前 AI 线程不存在，无法等待用户输入。",
                "不要重复调用；请说明会话已经失效。" );
    }

    private Map<String, Object> result(AiUserInputRequest request, boolean reused) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("waitingForUser", true);
        result.put("reused", reused);
        result.put("request", request.toMap());
        result.put("instruction", "问题卡片已由系统呈现。立即结束本轮；不要继续调用工具，"
                + "也不要输出任何自然语言或复述卡片内容。");
        return result;
    }

    private String normalizeType(String value) {
        String normalized = value != null
                ? value.trim().toUpperCase(Locale.ROOT) : "";
        return switch (normalized) {
            case "", "CLARIFICATION" -> AiUserInputRequest.TYPE_CLARIFICATION;
            case "CONFIRMATION" -> AiUserInputRequest.TYPE_CONFIRMATION;
            default -> throw AiToolException.modelCorrectable(
                    "INVALID_USER_INPUT_TYPE",
                    "type 仅支持 CLARIFICATION 或 CONFIRMATION。",
                    "根据场景选择意图澄清或高风险操作确认。");
        };
    }

    private List<AiUserInputOption> normalizeOptions(List<AiUserInputOption> options) {
        if (options == null || options.isEmpty()) return List.of();
        LinkedHashMap<String, AiUserInputOption> unique = new LinkedHashMap<>();
        for (AiUserInputOption option : options) {
            if (option == null) continue;
            String label = trim(option.getLabel(), 200);
            String value = trim(option.getValue(), 200);
            if (label == null || value == null) continue;
            String intent = trim(option.getIntent(), 100);
            unique.putIfAbsent(value, new AiUserInputOption(label, value, intent));
            if (unique.size() >= 4) break;
        }
        return new ArrayList<>(unique.values());
    }

    private String normalizeRisk(String value, String type) {
        String normalized = value != null
                ? value.trim().toUpperCase(Locale.ROOT) : "";
        if (normalized.isEmpty()) {
            return AiUserInputRequest.TYPE_CONFIRMATION.equals(type) ? "HIGH" : "LOW";
        }
        return switch (normalized) {
            case "LOW", "MEDIUM", "HIGH", "CRITICAL" -> normalized;
            default -> "HIGH";
        };
    }

    private boolean hasAffirmativeOption(List<AiUserInputOption> options) {
        return options.stream().anyMatch(AiUserInputService::isAffirmativeAnswer);
    }

    private boolean hasRejectOption(List<AiUserInputOption> options) {
        return options.stream().anyMatch(option -> {
            String value = String.valueOf(option.getValue()).trim().toLowerCase(Locale.ROOT);
            String label = String.valueOf(option.getLabel()).trim().toLowerCase(Locale.ROOT);
            String intent = String.valueOf(option.getIntent()).trim().toUpperCase(Locale.ROOT);
            return value.startsWith("取消") || value.startsWith("拒绝")
                    || value.startsWith("不同意") || value.startsWith("不执行")
                    || label.startsWith("取消") || label.startsWith("拒绝")
                    || "REJECT".equals(intent) || "CANCEL".equals(intent)
                    || value.equals("no") || value.equals("n") || value.equals("reject")
                    || value.equals("cancel");
        });
    }

    private static boolean isAffirmativeAnswer(AiUserInputOption option) {
        if (option == null) return false;
        String value = String.valueOf(option.getValue()).trim().toLowerCase(Locale.ROOT);
        String label = String.valueOf(option.getLabel()).trim().toLowerCase(Locale.ROOT);
        String intent = String.valueOf(option.getIntent()).trim().toUpperCase(Locale.ROOT);
        return (value.startsWith("确认") && !value.startsWith("不确认"))
                || label.startsWith("确认") || label.startsWith("同意") || label.startsWith("继续")
                || value.startsWith("同意") || value.startsWith("继续")
                || "CONFIRM".equals(intent) || "APPROVE".equals(intent)
                || value.equals("yes") || value.equals("y") || value.equals("confirm")
                || value.equals("approve") || value.equals("approved");
    }

    public static boolean isAffirmativeAnswer(String answer) {
        if (answer == null || answer.isBlank()) return false;
        String value = answer.trim().toLowerCase(Locale.ROOT);
        return (value.startsWith("确认") && !value.startsWith("不确认"))
                || value.startsWith("同意") || value.startsWith("继续")
                || value.equals("yes") || value.equals("y") || value.equals("confirm")
                || value.equals("approve") || value.equals("approved");
    }

    private long expiresMillis(Long seconds) {
        if (seconds == null || seconds <= 0L) return DEFAULT_EXPIRES_MS;
        long safeSeconds = Math.min(seconds, MAX_EXPIRES_MS / 1_000L);
        return safeSeconds * 1_000L;
    }

    private String requiredText(String value, String message, int max) {
        String normalized = trim(value, max);
        if (normalized == null) {
            throw AiToolException.modelCorrectable(
                    "MISSING_REQUIRED_ARGUMENT", message,
                    "提供一个具体、可直接回答的问题。" );
        }
        return normalized;
    }

    private String trim(String value, int max) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim();
        return normalized.length() <= max
                ? normalized : normalized.substring(0, max);
    }

    private String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** 对确认参数做 JSON 规范化后再计算哈希，避免键顺序/空白差异绕过绑定。 */
    public static String confirmationArgumentsHash(String value) {
        String canonical = canonicalArgumentsJson(value);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (Exception error) {
            throw AiToolException.systemRetryable(
                    "OPERATION_ARGUMENTS_HASH_FAILED",
                    "计算操作参数哈希失败。", error);
        }
    }

    /** 返回供后续工具调用原样复用的规范化 JSON。 */
    public static String canonicalArgumentsJson(String value) {
        Object parsed;
        try {
            parsed = JSON.parse(value);
        } catch (RuntimeException error) {
            throw AiToolException.modelCorrectable(
                    "INVALID_OPERATION_ARGUMENTS_JSON",
                    "argumentsJson 不是合法 JSON：" + compactJsonError(error),
                    "重新生成完整 JSON。命令、正则或路径中的反斜杠必须在 JSON 字符串中正确转义；"
                            + "不要复用当前损坏的 argumentsJson。 ");
        }
        return canonicalJson(parsed);
    }

    private static String compactJsonError(RuntimeException error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) return "字符串或转义不完整";
        String normalized = message.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 200 ? normalized : normalized.substring(0, 200);
    }

    @SuppressWarnings("unchecked")
    private static String canonicalJson(Object value) {
        return JSON.toJSONString(canonicalValue(value));
    }

    private static Object canonicalValue(Object value) {
        if (value instanceof Map<?, ?> || value instanceof List<?>) {
            if (value instanceof Map<?, ?> map) {
                TreeMap<String, Object> sorted = new TreeMap<>();
                map.forEach((key, item) -> sorted.put(
                        String.valueOf(key), canonicalValue(item)));
                return sorted;
            }
            return ((List<?>) value).stream()
                    .map(AiUserInputService::canonicalValue).toList();
        }
        return value;
    }

    private record RuntimeTarget(String threadId,
                                 String turnId,
                                 String itemId,
                                 Runnable markWaitingAction,
                                 java.util.function.Consumer<Map<String, Object>> emitter) {
        void markWaiting() { markWaitingAction.run(); }
        void emit(Map<String, Object> payload) { emitter.accept(payload); }
    }
}
