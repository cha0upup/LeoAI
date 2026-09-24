package org.leo.ai.channel;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ToolChoice;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.PartialResponse;
import dev.langchain4j.model.chat.response.PartialResponseContext;
import dev.langchain4j.model.chat.response.StreamingHandle;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.leo.ai.concurrent.AiBackgroundExecutor;
import org.leo.ai.service.AiErrorClassifier;
import org.leo.core.entity.AiModelCapability;
import org.leo.core.entity.AiModelConfig;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 跨 OpenAI-compatible / Responses 协议的无副作用能力探测。
 *
 * <p>只发送最小文本、JSON 格式或虚拟工具声明；不会执行工具、联网搜索或任何业务操作。
 * 仅将接口明确接受并产生对应证据的结果写回能力库，无法确定的探测结果不会覆盖原配置。
 */
@Service
public class AiModelCapabilityProbeService {

    private static final long BLOCKING_TIMEOUT_SECONDS = 35L;
    private static final long STREAMING_TIMEOUT_SECONDS = 35L;
    private final DynamicModelProvider dynamicModelProvider;
    private final AiModelConfigService configService;
    private final AiErrorClassifier errorClassifier;
    private final AiBackgroundExecutor backgroundExecutor;

    public AiModelCapabilityProbeService(DynamicModelProvider dynamicModelProvider,
                                         AiModelConfigService configService,
                                         AiErrorClassifier errorClassifier,
                                         AiBackgroundExecutor backgroundExecutor) {
        this.dynamicModelProvider = dynamicModelProvider;
        this.configService = configService;
        this.errorClassifier = errorClassifier;
        this.backgroundExecutor = backgroundExecutor;
    }

    public ProbeReport probe(AiModelConfig config) {
        if (config == null) throw new IllegalArgumentException("模型配置不能为空");
        long startedAt = System.currentTimeMillis();
        List<ProbeItem> items = new ArrayList<>();
        DynamicModelProvider.ModelRuntime runtime;
        try {
            runtime = dynamicModelProvider.buildProbeRuntime(config, false);
        } catch (Exception error) {
            items.add(failure("textGeneration", error, false));
            items.add(ProbeItem.skipped("streaming", "基础文本调用未通过，未继续探测"));
            items.add(ProbeItem.skipped("functionCalling", "基础文本调用未通过，未继续探测"));
            items.add(ProbeItem.skipped("structuredOutput", "基础文本调用未通过，未继续探测"));
            items.add(ProbeItem.skipped("reasoning", "基础文本调用未通过，未继续探测"));
            return new ProbeReport(config.getId(), config.getName(), config.getModel(), startedAt,
                    System.currentTimeMillis() - startedAt, false, null, items);
        }

        ProbeItem text = probeText(runtime);
        items.add(text);
        if (text.outcome() != Outcome.SUPPORTED) {
            items.add(ProbeItem.skipped("streaming", "基础文本调用未通过，未继续探测"));
            items.add(ProbeItem.skipped("functionCalling", "基础文本调用未通过，未继续探测"));
            items.add(ProbeItem.skipped("structuredOutput", "基础文本调用未通过，未继续探测"));
            items.add(ProbeItem.skipped("reasoning", "基础文本调用未通过，未继续探测"));
            return new ProbeReport(config.getId(), config.getName(), config.getModel(), startedAt,
                    System.currentTimeMillis() - startedAt, false, null, items);
        }

        items.add(probeStreaming(runtime));
        items.add(probeToolCalling(runtime));
        items.add(probeStructuredOutput(runtime));
        items.add(probeReasoning(config));

        Map<String, Boolean> verifiedFeatures = verifiedFeatures(items);
        AiModelCapability capability = configService.applyProbeResult(config, verifiedFeatures);
        return new ProbeReport(config.getId(), config.getName(), config.getModel(), startedAt,
                System.currentTimeMillis() - startedAt, true, capability, items);
    }

    private ProbeItem probeText(DynamicModelProvider.ModelRuntime runtime) {
        return blockingProbe("textGeneration", runtime, ChatRequest.builder()
                .messages(new UserMessage("请只回复 PROBE_OK"))
                .build(), response -> hasText(response) ? ProbeItem.supported("textGeneration", "收到有效文本响应")
                : ProbeItem.inconclusive("textGeneration", "接口返回成功，但未收到文本响应"), false);
    }

    private ProbeItem probeStreaming(DynamicModelProvider.ModelRuntime runtime) {
        long startedAt = System.currentTimeMillis();
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<Throwable> errorRef = new AtomicReference<>();
        AtomicReference<StreamingHandle> handleRef = new AtomicReference<>();
        AtomicBoolean receivedText = new AtomicBoolean(false);
        AtomicReference<ChatResponse> responseRef = new AtomicReference<>();
        try {
            runtime.streamingModel().chat(ChatRequest.builder()
                    .messages(new UserMessage("请以流式方式只回复 STREAM_OK"))
                    .build(), new StreamingChatResponseHandler() {
                @Override
                public void onPartialResponse(PartialResponse partial, PartialResponseContext context) {
                    handleRef.compareAndSet(null, context.streamingHandle());
                    if (partial != null && partial.text() != null && !partial.text().isBlank()) {
                        receivedText.set(true);
                    }
                }

                @Override
                public void onCompleteResponse(ChatResponse response) {
                    responseRef.set(response);
                    completed.countDown();
                }

                @Override
                public void onError(Throwable error) {
                    errorRef.set(error);
                    completed.countDown();
                }
            });
            if (!completed.await(STREAMING_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                StreamingHandle handle = handleRef.get();
                if (handle != null) handle.cancel();
                return ProbeItem.inconclusive("streaming", "流式响应在 " + STREAMING_TIMEOUT_SECONDS + " 秒内未完成",
                        System.currentTimeMillis() - startedAt);
            }
            Throwable error = errorRef.get();
            if (error != null) return failure("streaming", error, true, startedAt);
            if (receivedText.get() || hasText(responseRef.get())) {
                return ProbeItem.supported("streaming", "收到流式文本片段", System.currentTimeMillis() - startedAt);
            }
            return ProbeItem.inconclusive("streaming", "流已完成，但没有可验证的文本片段",
                    System.currentTimeMillis() - startedAt);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return ProbeItem.inconclusive("streaming", "探测被中断", System.currentTimeMillis() - startedAt);
        } catch (Exception error) {
            return failure("streaming", error, true, startedAt);
        } finally {
            StreamingHandle handle = handleRef.get();
            if (completed.getCount() > 0 && handle != null) handle.cancel();
        }
    }

    private ProbeItem probeToolCalling(DynamicModelProvider.ModelRuntime runtime) {
        ToolSpecification tool = ToolSpecification.builder()
                .name("capability_probe")
                .description("用于验证工具调用能力的空操作，不会执行。")
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("value", "固定填写 probe")
                        .required("value")
                        .additionalProperties(false)
                        .build())
                .build();
        // 先模拟实际 Agent 的 AUTO 调用模式。部分 OpenAI-compatible 网关会忽略 REQUIRED，
        // 但能在 AUTO 模式下正常返回工具调用；因此不能只用 REQUIRED 作为唯一判据。
        ProbeItem auto = requestToolCall(runtime, tool, ToolChoice.AUTO,
                "请调用 capability_probe 工具，并把 value 参数固定设置为 probe。不要输出普通文本。", "AUTO");
        if (auto.outcome() == Outcome.SUPPORTED) return auto;

        ProbeItem required = requestToolCall(runtime, tool, ToolChoice.REQUIRED,
                "必须调用 capability_probe 工具，并把 value 参数固定设置为 probe。不要输出普通文本。", "REQUIRED");
        if (required.outcome() == Outcome.SUPPORTED) return required;
        if (auto.outcome() == Outcome.UNSUPPORTED && required.outcome() == Outcome.UNSUPPORTED) {
            return ProbeItem.unsupported("functionCalling",
                    "AUTO 与 REQUIRED 工具请求均被接口明确拒绝", auto.latencyMs() + required.latencyMs());
        }
        return ProbeItem.inconclusive("functionCalling",
                "未收到工具调用；AUTO：" + auto.message() + "；REQUIRED：" + required.message(),
                auto.latencyMs() + required.latencyMs());
    }

    private ProbeItem requestToolCall(DynamicModelProvider.ModelRuntime runtime, ToolSpecification tool,
                                      ToolChoice choice, String prompt, String modeLabel) {
        ChatRequest request = ChatRequest.builder()
                .messages(new UserMessage(prompt))
                .toolSpecifications(tool)
                .toolChoice(choice)
                .build();
        return blockingProbe("functionCalling", runtime, request, response -> {
            AiMessage message = response != null ? response.aiMessage() : null;
            if (message != null && message.hasToolExecutionRequests()) {
                boolean matched = message.toolExecutionRequests().stream()
                        .map(ToolExecutionRequest::name)
                        .anyMatch("capability_probe"::equals);
                if (matched) return ProbeItem.supported("functionCalling", modeLabel + " 模式收到虚拟工具调用请求");
            }
            return ProbeItem.inconclusive("functionCalling", modeLabel + " 模式接口返回成功，但未收到工具调用");
        }, true);
    }

    private ProbeItem probeStructuredOutput(DynamicModelProvider.ModelRuntime runtime) {
        ChatRequest request = ChatRequest.builder()
                .messages(new UserMessage("仅返回 JSON 对象：{\"probe\":\"ok\"}"))
                .responseFormat(ResponseFormat.JSON)
                .build();
        return blockingProbe("structuredOutput", runtime, request, response -> {
            String text = response != null && response.aiMessage() != null ? response.aiMessage().text() : null;
            if (text == null || text.isBlank()) {
                return ProbeItem.inconclusive("structuredOutput", "接口返回成功，但没有 JSON 文本");
            }
            try {
                Object parsed = JSON.parse(text.trim());
                if (parsed instanceof JSONObject object && "ok".equalsIgnoreCase(object.getString("probe"))) {
                    return ProbeItem.supported("structuredOutput", "响应符合 JSON 输出模式");
                }
                return ProbeItem.inconclusive("structuredOutput", "响应可解析为 JSON，但内容不符合探测约定");
            } catch (Exception ignored) {
                return ProbeItem.inconclusive("structuredOutput", "接口未返回有效 JSON");
            }
        }, true);
    }

    private ProbeItem probeReasoning(AiModelConfig config) {
        try {
            DynamicModelProvider.ModelRuntime reasoningRuntime = dynamicModelProvider.buildProbeRuntime(config, true);
            ChatRequest request = ChatRequest.builder()
                    .messages(new UserMessage("计算 17 * 19，并给出结果。"))
                    .build();
            return blockingProbe("reasoning", reasoningRuntime, request, response -> {
                AiMessage message = response != null ? response.aiMessage() : null;
                if (message != null && message.thinking() != null && !message.thinking().isBlank()) {
                    return ProbeItem.supported("reasoning", "收到 reasoning/thinking 内容");
                }
                return ProbeItem.inconclusive("reasoning", "接口接受了推理请求，但未返回可验证的 thinking 内容");
            }, true);
        } catch (Exception error) {
            return failure("reasoning", error, true);
        }
    }

    private ProbeItem blockingProbe(String feature, DynamicModelProvider.ModelRuntime runtime,
                                    ChatRequest request, ResponseJudge judge, boolean unsupportedIsConclusive) {
        long startedAt = System.currentTimeMillis();
        try {
            ChatResponse response = callBlocking(runtime, request);
            return judge.judge(response).withLatency(System.currentTimeMillis() - startedAt);
        } catch (Exception error) {
            return failure(feature, error, unsupportedIsConclusive, startedAt);
        }
    }

    private ChatResponse callBlocking(DynamicModelProvider.ModelRuntime runtime, ChatRequest request) throws Exception {
        Future<ChatResponse> future = backgroundExecutor.submitProbe(
                () -> runtime.chatModel().chat(request));
        try {
            return future.get(BLOCKING_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw error;
        } finally {
            if (!future.isDone()) future.cancel(true);
        }
    }

    private ProbeItem failure(String feature, Exception error, boolean unsupportedIsConclusive) {
        return failure(feature, (Throwable) error, unsupportedIsConclusive, System.currentTimeMillis());
    }

    private ProbeItem failure(String feature, Throwable error, boolean unsupportedIsConclusive, long startedAt) {
        AiErrorClassifier.Classification classification = errorClassifier.classify(error);
        long latency = Math.max(0L, System.currentTimeMillis() - startedAt);
        boolean unsupported = AiErrorClassifier.CATEGORY_UNSUPPORTED_PARAMETER.equals(classification.category())
                || AiErrorClassifier.CATEGORY_TOOL_CALLING.equals(classification.category())
                || AiErrorClassifier.CATEGORY_THINKING_MODE.equals(classification.category());
        if (unsupportedIsConclusive && unsupported) {
            return ProbeItem.unsupported(feature, classification.message(), latency);
        }
        return ProbeItem.inconclusive(feature, classification.message(), latency);
    }

    private static boolean hasText(ChatResponse response) {
        return response != null && response.aiMessage() != null
                && response.aiMessage().text() != null && !response.aiMessage().text().isBlank();
    }

    private static Map<String, Boolean> verifiedFeatures(List<ProbeItem> items) {
        Map<String, Boolean> result = new LinkedHashMap<>();
        for (ProbeItem item : items) {
            if (item.outcome() == Outcome.SUPPORTED) result.put(item.feature(), true);
            if (item.outcome() == Outcome.UNSUPPORTED) result.put(item.feature(), false);
        }
        return result;
    }

    @FunctionalInterface
    private interface ResponseJudge {
        ProbeItem judge(ChatResponse response);
    }

    public enum Outcome {
        SUPPORTED("supported"),
        UNSUPPORTED("unsupported"),
        INCONCLUSIVE("inconclusive"),
        SKIPPED("skipped");

        private final String code;

        Outcome(String code) {
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    public record ProbeItem(String feature, Outcome outcome, String message, long latencyMs) {
        private static ProbeItem supported(String feature, String message) {
            return supported(feature, message, 0L);
        }

        private static ProbeItem supported(String feature, String message, long latencyMs) {
            return new ProbeItem(feature, Outcome.SUPPORTED, message, latencyMs);
        }

        private static ProbeItem unsupported(String feature, String message, long latencyMs) {
            return new ProbeItem(feature, Outcome.UNSUPPORTED, message, latencyMs);
        }

        private static ProbeItem inconclusive(String feature, String message) {
            return inconclusive(feature, message, 0L);
        }

        private static ProbeItem inconclusive(String feature, String message, long latencyMs) {
            return new ProbeItem(feature, Outcome.INCONCLUSIVE, message, latencyMs);
        }

        private static ProbeItem skipped(String feature, String message) {
            return new ProbeItem(feature, Outcome.SKIPPED, message, 0L);
        }

        private ProbeItem withLatency(long latencyMs) {
            return new ProbeItem(feature, outcome, message, Math.max(0L, latencyMs));
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("feature", feature);
            map.put("status", outcome.code());
            map.put("message", message);
            map.put("latencyMs", latencyMs);
            return map;
        }
    }

    public record ProbeReport(Integer configId, String configName, String model, long startedAt,
                              long durationMs, boolean applied, AiModelCapability capability,
                              List<ProbeItem> items) {
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("configId", configId);
            map.put("configName", configName);
            map.put("model", model);
            map.put("startedAt", startedAt);
            map.put("durationMs", durationMs);
            map.put("applied", applied);
            map.put("items", items.stream().map(ProbeItem::toMap).toList());
            if (capability != null) {
                map.put("capabilityModelName", capability.getModelName());
                map.put("capabilitySource", capability.getSource());
            }
            return map;
        }
    }
}
