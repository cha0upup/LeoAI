package org.leo.ai.service;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class AiErrorClassifier {

    public static final String CATEGORY_AUTH = "auth";
    public static final String CATEGORY_MODEL_NOT_FOUND = "model_not_found";
    public static final String CATEGORY_UNSUPPORTED_PARAMETER = "unsupported_parameter";
    public static final String CATEGORY_THINKING_MODE = "thinking_mode";
    public static final String CATEGORY_TOOL_CALLING = "tool_calling";
    public static final String CATEGORY_CONTEXT_LIMIT = "context_limit";
    public static final String CATEGORY_RATE_LIMIT = "rate_limit";
    public static final String CATEGORY_TIMEOUT = "timeout";
    public static final String CATEGORY_NETWORK = "network";
    public static final String CATEGORY_MALFORMED_RESPONSE = "malformed_response";
    public static final String CATEGORY_UNKNOWN = "unknown";

    public Classification classify(Throwable error) {
        return classify(rootMessage(error));
    }

    public Classification classify(String message) {
        String text = message == null ? "" : message.trim();
        String lower = text.toLowerCase(Locale.ROOT);
        String category;
        if (isThinkingPassbackError(lower)) {
            category = CATEGORY_THINKING_MODE;
        } else if (lower.contains("chatcompletion$choice.message() is null")
                || lower.contains("chatcompletionmessage.role()")
                || (lower.contains("chatcompletion") && lower.contains("message() is null"))) {
            category = CATEGORY_MALFORMED_RESPONSE;
        } else if (lower.contains("tool") && (lower.contains("call") || lower.contains("function"))
                && (lower.contains("unsupported") || lower.contains("invalid") || lower.contains("format"))) {
            category = CATEGORY_TOOL_CALLING;
        } else if (lower.contains("401") || lower.contains("unauthorized") || lower.contains("forbidden")
                || lower.contains("api key") || lower.contains("apikey") || lower.contains("authentication")) {
            category = CATEGORY_AUTH;
        } else if (lower.contains("model") && (lower.contains("not found") || lower.contains("does not exist")
                || lower.contains("不存在") || lower.contains("not exist"))) {
            category = CATEGORY_MODEL_NOT_FOUND;
        } else if (lower.contains("param incorrect") || lower.contains("unsupported")
                || lower.contains("invalid request") || lower.contains("bad request")
                || lower.contains("unknown parameter") || lower.contains("unrecognized")) {
            category = CATEGORY_UNSUPPORTED_PARAMETER;
        } else if (lower.contains("context") || lower.contains("maximum context")
                || lower.contains("too many tokens") || lower.contains("token limit")) {
            category = CATEGORY_CONTEXT_LIMIT;
        } else if (lower.contains("429") || lower.contains("rate limit") || lower.contains("too many requests")) {
            category = CATEGORY_RATE_LIMIT;
        } else if (lower.contains("timeout") || lower.contains("timed out")) {
            category = CATEGORY_TIMEOUT;
        } else if (lower.contains("connection refused") || lower.contains("unknownhost")
                || lower.contains("dns") || lower.contains("no route")
                || lower.contains("connection reset") || lower.contains("connectexception")) {
            category = CATEGORY_NETWORK;
        } else {
            category = CATEGORY_UNKNOWN;
        }
        return new Classification(category, readableMessage(category, text), text, actions(category));
    }

    public Classification classifyCategory(String category, String rawMessage) {
        String normalized = category == null || category.isBlank() ? CATEGORY_UNKNOWN : category;
        String text = rawMessage == null ? "" : rawMessage.trim();
        return new Classification(normalized, readableMessage(normalized, text), text, actions(normalized));
    }

    /**
     * Detects errors caused by reasoning_content not being passed back in tool-calling turns.
     *
     * <p>典型错误（OpenAI 兼容厂商 reasoning 模型如 DeepSeek-reasoner / Qwen-thinking）：
     * {@code "The `reasoning_content` in the thinking mode must be passed back to the API."}
     */
    private static boolean isThinkingPassbackError(String lower) {
        if (lower.contains("reasoning_content") && lower.contains("thinking")) {
            return true;
        }
        return lower.contains("passed back") && lower.contains("thinking");
    }

    private static String rootMessage(Throwable t) {
        if (t == null) {
            return "";
        }
        Throwable current = t;
        String message = null;
        while (current != null) {
            if (current.getMessage() != null && !current.getMessage().isBlank()) {
                message = current.getMessage();
            }
            current = current.getCause();
        }
        return message != null ? message : t.getClass().getSimpleName();
    }

    private static String readableMessage(String category, String rawMessage) {
        return switch (category) {
            case CATEGORY_THINKING_MODE -> "当前接口不接受 Thinking 工具调用历史，请关闭 Thinking 或切换模型";
            case CATEGORY_TOOL_CALLING -> "当前接口的工具调用格式不可用，请切换到支持 tool calling 的通道";
            case CATEGORY_AUTH -> "认证失败，请检查 API Key 或网关鉴权配置";
            case CATEGORY_MODEL_NOT_FOUND -> "模型不可用，请检查模型名称";
            case CATEGORY_UNSUPPORTED_PARAMETER -> "接口不支持当前请求参数，请检查 Thinking、Extra Body 或协议路径";
            case CATEGORY_CONTEXT_LIMIT -> "上下文或输出 token 超出模型限制";
            case CATEGORY_RATE_LIMIT -> "接口限流，请稍后重试或切换通道";
            case CATEGORY_TIMEOUT -> "请求超时，请检查网络、Base URL 或网关状态";
            case CATEGORY_NETWORK -> "网络连接失败，请检查 Base URL、代理或出网限制";
            case CATEGORY_MALFORMED_RESPONSE -> "模型返回了不完整的响应，请重试，或调整当前模型/推理配置";
            default -> rawMessage != null && !rawMessage.isBlank() ? rawMessage : "AI 调用失败";
        };
    }

    private static List<Action> actions(String category) {
        return switch (category) {
            case CATEGORY_THINKING_MODE -> List.of(
                    new Action("review_protocol_options", "检查 Thinking 和 Extra Body"),
                    new Action("switch_channel", "切换到不启用 Thinking 的通道"));
            case CATEGORY_TOOL_CALLING -> List.of(
                    new Action("switch_tool_channel", "切换支持工具调用的通道"),
                    new Action("probe_channel", "重新探测通道能力"));
            case CATEGORY_AUTH -> List.of(
                    new Action("edit_api_key", "检查并更新 API Key"),
                    new Action("probe_channel", "重新测试连接"));
            case CATEGORY_MODEL_NOT_FOUND -> List.of(
                    new Action("edit_model", "检查模型名称"),
                    new Action("switch_channel", "切换可用通道"));
            case CATEGORY_UNSUPPORTED_PARAMETER -> List.of(
                    new Action("review_protocol_options", "检查 Thinking、Extra Body 和请求路径"),
                    new Action("probe_channel", "重新探测通道能力"));
            case CATEGORY_CONTEXT_LIMIT -> List.of(
                    new Action("new_thread", "新建对话减少上下文"),
                    new Action("lower_tokens", "降低输出 Token 上限"));
            case CATEGORY_RATE_LIMIT -> List.of(
                    new Action("retry_later", "稍后重试"),
                    new Action("switch_channel", "切换备用通道"));
            case CATEGORY_TIMEOUT, CATEGORY_NETWORK -> List.of(
                    new Action("check_network", "检查 Base URL、代理和出网限制"),
                    new Action("probe_channel", "重新测试连接"));
            case CATEGORY_MALFORMED_RESPONSE -> List.of(
                    new Action("retry", "重试本次请求"),
                    new Action("review_model", "检查模型和推理配置"));
            default -> List.of(new Action("retry", "重试"));
        };
    }

    public record Action(String code, String label) {
    }

    public record Classification(String category, String message, String rawMessage, List<Action> actions) {
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("category", category);
            map.put("message", message);
            map.put("actions", actions);
            if (rawMessage != null && !rawMessage.isBlank()) {
                map.put("rawMessage", rawMessage);
            }
            return map;
        }
    }
}
