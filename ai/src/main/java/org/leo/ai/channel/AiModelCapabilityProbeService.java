package org.leo.ai.channel;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ToolChoice;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.response.ChatModelStreamingEvent;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.CompleteResponse;
import dev.langchain4j.model.chat.response.PartialResponse;
import org.leo.ai.concurrent.AiBackgroundExecutor;
import org.leo.ai.service.AiErrorClassifier;
import org.leo.core.entity.AiModelCapability;
import org.leo.core.entity.AiModelConfig;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * 跨 OpenAI-compatible / Responses 协议的无副作用能力探测。
 *
 * <p>只发送最小文本、JSON 格式或虚拟工具声明；不会执行工具、联网搜索或任何业务操作。
 * 仅将接口明确接受并产生对应证据的结果写回能力库，无法确定的探测结果不会覆盖原配置。
 */
@Service
public class AiModelCapabilityProbeService {

    private static final long BLOCKING_TIMEOUT_SECONDS = 35L;
    private static final Duration STREAMING_TIMEOUT = Duration.ofSeconds(35);
    private static final String PROBE_INTERRUPTED = "探测被中断";
    private static final String SKIPPED_AFTER_INTERRUPT = PROBE_INTERRUPTED + "，未继续探测";
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
        if (Thread.currentThread().isInterrupted()) {
            return unappliedAfterTextProbe(config, startedAt, ProbeItem.skipped("textGeneration", SKIPPED_AFTER_INTERRUPT));
        }
        DynamicModelProvider.ModelRuntime runtime;
        try {
            runtime = dynamicModelProvider.buildProbeRuntime(config, false);
        } catch (Exception error) {
            return unappliedAfterTextProbe(config, startedAt, failure("textGeneration", error, false));
        }

        ProbeItem text = probeText(runtime);
        if (text.outcome() != Outcome.SUPPORTED) {
            return unappliedAfterTextProbe(config, startedAt, text);
        }

        List<ProbeItem> items = List.of(text,
                probeUnlessInterrupted("streaming", () -> probeStreaming(runtime.streamingModel(), STREAMING_TIMEOUT)),
                probeUnlessInterrupted("functionCalling", () -> probeToolCalling(runtime)),
                probeUnlessInterrupted("structuredOutput", () -> probeStructuredOutput(runtime)),
                probeUnlessInterrupted("reasoning", () -> probeReasoning(config)));

        boolean applied = !Thread.currentThread().isInterrupted();
        AiModelCapability capability = applied ? configService.applyProbeResult(config, verifiedFeatures(items)) : null;
        return new ProbeReport(config.getId(), config.getName(), config.getModel(), startedAt,
                System.currentTimeMillis() - startedAt, applied, capability, items);
    }

    private static ProbeReport unappliedAfterTextProbe(AiModelConfig config, long startedAt, ProbeItem text) {
        String skipReason = Thread.currentThread().isInterrupted()
                ? SKIPPED_AFTER_INTERRUPT : "基础文本调用未通过，未继续探测";
        return new ProbeReport(config.getId(), config.getName(), config.getModel(), startedAt,
                System.currentTimeMillis() - startedAt, false, null, List.of(text,
                ProbeItem.skipped("streaming", skipReason),
                ProbeItem.skipped("functionCalling", skipReason),
                ProbeItem.skipped("structuredOutput", skipReason),
                ProbeItem.skipped("reasoning", skipReason)));
    }

    private static ProbeItem probeUnlessInterrupted(String feature, Supplier<ProbeItem> probe) {
        return Thread.currentThread().isInterrupted() ? ProbeItem.skipped(feature, SKIPPED_AFTER_INTERRUPT) : probe.get();
    }

    private ProbeItem probeText(DynamicModelProvider.ModelRuntime runtime) {
        return blockingProbe("textGeneration", runtime, ChatRequest.builder()
                .messages(new UserMessage("请只回复 PROBE_OK"))
                .build(), response -> hasText(response) ? ProbeItem.supported("textGeneration", "收到有效文本响应")
                : ProbeItem.inconclusive("textGeneration", "接口返回成功，但未收到文本响应"), false);
    }

    ProbeItem probeStreaming(StreamingChatModel model, Duration timeout) {
        long startedAt = System.currentTimeMillis();
        var completed = new CompletableFuture<Boolean>();
        var subscription = new AtomicReference<Flow.Subscription>();
        try {
            model.chat(ChatRequest.builder()
                    .messages(new UserMessage("请以流式方式只回复 STREAM_OK"))
                    .build()).subscribe(new Flow.Subscriber<ChatModelStreamingEvent>() {
                private boolean receivedText;

                @Override
                public void onSubscribe(Flow.Subscription current) {
                    subscription.set(current);
                    if (completed.isCancelled()) current.cancel();
                    else current.request(Long.MAX_VALUE);
                }

                @Override
                public void onNext(ChatModelStreamingEvent event) {
                    if ((event instanceof PartialResponse partial && partial.text() != null && !partial.text().isBlank())
                            || (event instanceof CompleteResponse complete && hasText(complete.chatResponse()))) {
                        receivedText = true;
                    }
                }

                @Override
                public void onError(Throwable error) {
                    completed.completeExceptionally(error);
                }

                @Override
                public void onComplete() {
                    completed.complete(receivedText);
                }
            });
            if (completed.get(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                return ProbeItem.supported("streaming", "收到流式文本片段", System.currentTimeMillis() - startedAt);
            }
            return ProbeItem.inconclusive("streaming", "流已完成，但没有可验证的文本片段",
                    System.currentTimeMillis() - startedAt);
        } catch (TimeoutException error) {
            return ProbeItem.inconclusive("streaming", "流式响应在 " + timeout.toSeconds() + " 秒内未完成",
                    System.currentTimeMillis() - startedAt);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return ProbeItem.inconclusive("streaming", PROBE_INTERRUPTED, System.currentTimeMillis() - startedAt);
        } catch (ExecutionException error) {
            return failure("streaming", error.getCause(), true, startedAt);
        } catch (Exception error) {
            return failure("streaming", error, true, startedAt);
        } finally {
            if (completed.cancel(false)) {
                Flow.Subscription current = subscription.get();
                if (current != null) current.cancel();
            }
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
        if (auto.outcome() == Outcome.SUPPORTED || Thread.currentThread().isInterrupted()) return auto;

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
        } catch (InterruptedException error) {
            return ProbeItem.inconclusive(feature, PROBE_INTERRUPTED, System.currentTimeMillis() - startedAt);
        } catch (Exception error) {
            return failure(feature, error, unsupportedIsConclusive, startedAt);
        }
    }

    private ChatResponse callBlocking(DynamicModelProvider.ModelRuntime runtime, ChatRequest request) throws Exception {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException(PROBE_INTERRUPTED);
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
