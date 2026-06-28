package org.leo.web.service;

import dev.langchain4j.model.chat.response.StreamingHandle;
import dev.langchain4j.service.TokenStream;
import jakarta.servlet.http.HttpSession;
import org.leo.ai.agent.PlatformAgent;
import org.leo.ai.audit.AiAuditLogStore;
import org.leo.ai.channel.AiModelConfigService;
import org.leo.ai.channel.DynamicModelProvider;
import org.leo.ai.platform.PlatformAiState;
import org.leo.ai.platform.PlatformAiStateStore;
import org.leo.ai.service.AiErrorClassifier;
import org.leo.ai.thread.AiConversationStoreService;
import com.alibaba.fastjson.JSON;
import org.leo.core.entity.AiChatAuditEntry;
import org.leo.core.entity.AiModelConfig;
import org.leo.core.entity.AiRuntimeStats;
import org.leo.core.entity.AiExecutionPolicy;
import org.leo.core.entity.AiSseEvent;
import org.leo.core.entity.AiThreadRecord;
import org.leo.core.entity.User;
import org.leo.web.dto.platform.ai.PlatformAiDtos.AgentInfoResponse;
import org.leo.web.exception.ApiException;
import org.leo.web.util.AiControllerUtil;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class PlatformAiService {

    private static final Logger logger = LoggerFactory.getLogger(PlatformAiService.class);
    private static final String SESSION_ATTR_PLATFORM_AI_STATE_ID = "platformAiStateId";
    private static final ExecutorService SSE_QUEUE_DRAIN_EXECUTOR =
            Executors.newCachedThreadPool(r -> {
                Thread thread = new Thread(r, "platform-ai-sse-queue-drain");
                thread.setDaemon(true);
                return thread;
            });

    private final PlatformAgent platformAgent;
    private final AiModelConfigService modelConfigService;
    private final DynamicModelProvider dynamicModelProvider;
    private final AiErrorClassifier aiErrorClassifier;
    private final AiAuditLogStore auditLogStore;
    private final AiConversationStoreService conversationStore;

    public PlatformAiService(PlatformAgent platformAgent,
                             AiModelConfigService modelConfigService,
                             DynamicModelProvider dynamicModelProvider,
                             AiErrorClassifier aiErrorClassifier,
                             AiAuditLogStore auditLogStore,
                             AiConversationStoreService conversationStore) {
        this.platformAgent = platformAgent;
        this.modelConfigService = modelConfigService;
        this.dynamicModelProvider = dynamicModelProvider;
        this.aiErrorClassifier = aiErrorClassifier;
        this.auditLogStore = auditLogStore;
        this.conversationStore = conversationStore;
    }

    public AgentInfoResponse createAgent(HttpSession httpSession, User user, Integer configId) {
        return createAgent(httpSession, user, configId, null);
    }

    public AgentInfoResponse createAgent(HttpSession httpSession, User user, Integer configId, String mode) {
        PlatformAiState state = recreateState(httpSession);
        state.resetTurnCount();
        state.setMode(mode);
        AiModelConfig config = resolveOptionalChannel(configId);
        if (config != null) {
            state.setAiConfigId(config.getId());
        }
        // 确保线程记录持久化到 DB
        String threadId = state.getStateId();
        AiThreadRecord existing = conversationStore.findThread(threadId);
        if (existing == null) {
            conversationStore.createPlatformThread(
                    user.getUserId(),
                    httpSession.getId(),
                    threadId,
                    "平台 AI",
                    state.getCreatedAt(),
                    config);
        }
        return new AgentInfoResponse(0);
    }

    public void switchChannel(HttpSession httpSession, User user, Integer configId) {
        PlatformAiState state = requireState(httpSession, "AI 会话不存在，请先调用 createAgent");
        if (state.isExecuting()) {
            throw ApiException.badRequest("平台 AI 正在执行中，请等待完成或先停止后再切换通道");
        }
        AiModelConfig config = resolveOptionalChannel(configId);
        state.setAiConfigId(config != null ? config.getId() : null);
        conversationStore.updateConfig(state.getStateId(), config);
    }

    public Map<String, Object> switchMode(HttpSession httpSession, User user, String mode) {
        PlatformAiState state = requireState(httpSession, "AI 会话不存在，请先调用 createAgent");
        if (mode != null) {
            state.setMode(mode);
        }
        conversationStore.updateMode(state.getStateId(), state.getMode());

        LinkedHashMap<String, Object> info = new LinkedHashMap<>();
        info.put("stateId", state.getStateId());
        info.put("mode", state.getMode());
        return info;
    }

    public AiChatAuditEntry appendChatAudit(AiExecutionPolicy policy, String message) {
        AiChatAuditEntry audit = AiChatAuditEntry.platform(
                policy.getUserId(),
                policy.getUserName(),
                policy.getPrivilege(),
                message,
                false);
        auditLogStore.append(audit);
        return audit;
    }

    public void executeChat(PlatformAiState state,
                            String sessionId,
                            String userMessage,
                            String guardedMessage,
                            AiChatAuditEntry audit,
                            SseEmitter emitter,
                            long startMs) {
        AiTimelineRecorder recorder = new AiTimelineRecorder(
                (name, data) -> sendRecordedEventSafely(state, emitter, name, data));
        List<AiSseEvent> eventLog = recorder.eventLog();
        String memoryId = state.getStateId();
        String runId = null;
        state.getAiSseEventQueue().clear();
        QueueDrainHandle queueDrain = startQueueDrain(state, emitter, eventLog);

        try {
            state.markExecuting(Thread.currentThread());
            if (state.isStopRequested()) {
                throw new InterruptedException("已停止");
            }
            state.touchLastActiveAt();
            conversationStore.appendMessage(state.getStateId(), "user", userMessage);
            String messageForAgent = withPersistedHistoryContext(state, guardedMessage);
            applyThreadModel(state);
            runId = conversationStore.startRun(state.getStateId(), state.getAiConfigId(), messageForAgent, startMs);
            conversationStore.updateRuntime(sessionId, state.getStateId(), state.getLastActiveAt(), state.getRunStatus());
            sendRecordedEvent(state, emitter, "status", PlatformAiState.STATUS_RUNNING);

            final String fRunId = runId;
            AtomicReference<StreamingHandle> handleRef = new AtomicReference<>();
            TokenStream stream = platformAgent.chat(memoryId, messageForAgent);

            // 注册停止回调：必须在 stream.start() 之前，否则存在用户在 start() 返回前
            // 触发 stop 导致回调尚未注册、cancel() 永远不执行的竞态窗口。
            // stopCallback 读取 handleRef 是延迟求值（lambda 捕获引用），
            // handleRef 在第一个流式 token 到达时填充，此前调用时 handle 为 null，
            // 但 thread.interrupt() 仍会生效，工具执行前的 stopRequested 检查会拦截。
            state.setStopCallback(() -> {
                StreamingHandle handle = handleRef.get();
                if (handle != null) {
                    handle.cancel();
                }
            });

            stream
                .onPartialThinking(thinking -> {
                    if (!recorder.hasPendingThinking()) {
                        logger.info("[Thinking] 开始接收思考内容, stateId={}", state.getStateId());
                    }
                    recorder.appendThinking(thinking.text());
                })
                .onPartialResponseWithContext((partial, ctx) -> {
                    handleRef.compareAndSet(null, ctx.streamingHandle());
                    recorder.appendVisibleDelta(partial.text());
                    recorder.flushDelta();
                })
                .onPartialToolCall(partial -> {
                    recorder.onBoundary();
                    Map<String, Object> toolData = AiToolEventFactory.buildToolDeltaEventData(partial);
                    sendRecordedEventSafely(state, emitter, "tool_delta", toolData);
                })
                .beforeToolExecution(execution -> {
                    recorder.onBoundary();
                    // 工具执行前检查停止标志：若用户已点停止，直接抛出中断异常，
                    // 阻止工具方法进入执行，避免后台任务继续运行。
                    if (state.isStopRequested() || Thread.currentThread().isInterrupted()) {
                        throw new RuntimeException(new InterruptedException(
                                state.getStopReason() != null ? state.getStopReason() : "已停止"));
                    }
                    Map<String, Object> toolData = AiToolEventFactory.buildToolStartEventData(execution);
                    sendRecordedEventSafely(state, emitter, "node", toolData);
                    recorder.recordExternal("node", toolData);
                })
                .onToolExecuted(execution -> {
                    try {
                        recorder.flushThinking();
                        Map<String, Object> toolData = AiToolEventFactory.buildToolEventData(execution);
                        sendRecordedEventSafely(state, emitter, "patch", toolData);
                        recorder.recordExternal("patch", toolData);
                    } catch (Exception e) {
                        // 构建工具事件失败，忽略
                    }
                })
                .onCompleteResponse(response -> {
                    recorder.onBoundary();
                    String output = recorder.reply();
                    Object[] turnHolder = { null };
                    try {
                        int toolCallCount = countToolCallEvents(eventLog);
                        audit.complete(output, toolCallCount, System.currentTimeMillis() - startMs);
                        finishRun(fRunId, PlatformAiState.STATUS_COMPLETED, startMs, output, null, toolCallCount);
                        Map<String, Object> review = buildTurnReview(output, eventLog, System.currentTimeMillis() - startMs);
                        Map<String, Object> usage = buildUsageEvent(response);
                        Map<String, Object> turn = new LinkedHashMap<>();
                        if (!usage.isEmpty()) {
                            accumulateTokenUsage(state.getRuntimeStats(), usage);
                            turn.put("usage", usage);
                        }
                        turn.put("review", review);
                        turnHolder[0] = turn;
                        stopAndFlushQueuedEvents(state, emitter, eventLog, queueDrain);
                        persistAssistantMessage(state, output, eventLog, review, null);
                        state.markCompleted();
                        state.touchLastActiveAt();
                        conversationStore.updateRuntime(sessionId, state.getStateId(), state.getLastActiveAt(), state.getRunStatus());
                    } catch (Exception e) {
                        logger.warn("onCompleteResponse 后续处理异常: {}", e.getMessage(), e);
                        state.markCompleted();
                    } finally {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> turn = turnHolder[0] instanceof Map<?, ?> m
                                ? (Map<String, Object>) m : new LinkedHashMap<>();
                        turn.putIfAbsent("content", output);
                        sendRecordedEventSafely(state, emitter, "status", PlatformAiState.STATUS_COMPLETED);
                        sendRecordedEventSafely(state, emitter, "turn", turn);
                        AiControllerUtil.safeComplete(emitter);
                        state.clearExecuting();
                    }
                })
                .onError(error -> {
                    recorder.flushDelta();
                    stopAndFlushQueuedEvents(state, emitter, eventLog, queueDrain);
                    if (state.isStopRequested() || Thread.currentThread().isInterrupted()) {
                        String reason = state.getStopReason() != null ? state.getStopReason() : "已停止";
                        audit.fail(reason, System.currentTimeMillis() - startMs);
                        state.markCancelled();
                        finishRun(fRunId, PlatformAiState.STATUS_CANCELLED, startMs, null, reason, 0);
                        conversationStore.updateRuntime(sessionId, state.getStateId(), state.getLastActiveAt(), state.getRunStatus());
                        sendRecordedStatusSafely(state, emitter, PlatformAiState.STATUS_CANCELLED);
                        AiControllerUtil.safeSendError(emitter, reason);
                    } else {
                        AiErrorClassifier.Classification classification = aiErrorClassifier.classify(error);
                        audit.fail(classification.message(), System.currentTimeMillis() - startMs);
                        state.markFailed();
                        finishRun(fRunId, PlatformAiState.STATUS_FAILED, startMs, null, classification.message(), 0);
                        conversationStore.updateRuntime(sessionId, state.getStateId(), state.getLastActiveAt(), state.getRunStatus());
                        sendRecordedStatusSafely(state, emitter, PlatformAiState.STATUS_FAILED);
                        AiControllerUtil.safeSendError(emitter, classification);
                    }
                    state.clearExecuting();
                })
                .start();

        } catch (Exception e) {
            if (state.isStopRequested() || Thread.currentThread().isInterrupted()) {
                stopAndFlushQueuedEvents(state, emitter, eventLog, queueDrain);
                String reason = state.getStopReason() != null ? state.getStopReason() : "已停止";
                audit.fail(reason, System.currentTimeMillis() - startMs);
                state.markCancelled();
                finishRun(runId, PlatformAiState.STATUS_CANCELLED, startMs, null, reason, 0);
                conversationStore.updateRuntime(sessionId, state.getStateId(), state.getLastActiveAt(), state.getRunStatus());
                sendRecordedStatusSafely(state, emitter, PlatformAiState.STATUS_CANCELLED);
                AiControllerUtil.safeSendError(emitter, reason);
            } else {
                stopAndFlushQueuedEvents(state, emitter, eventLog, queueDrain);
                AiErrorClassifier.Classification classification = aiErrorClassifier.classify(e);
                audit.fail(classification.message(), System.currentTimeMillis() - startMs);
                state.markFailed();
                finishRun(runId, PlatformAiState.STATUS_FAILED, startMs, null, classification.message(), 0);
                conversationStore.updateRuntime(sessionId, state.getStateId(), state.getLastActiveAt(), state.getRunStatus());
                sendRecordedStatusSafely(state, emitter, PlatformAiState.STATUS_FAILED);
                AiControllerUtil.safeSendError(emitter, classification);
            }
            state.clearExecuting();
        }
    }

    // ── 线程管理 ───────────────────────────────────────────────────────────

    /**
     * 列出当前用户的所有平台 AI 线程。
     */
    public List<Map<String, Object>> listThreads(User user) {
        List<AiThreadRecord> records = conversationStore.listPlatformThreads(user.getUserId());
        if (records == null) return List.of();
        return records.stream().map(r -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("threadId", r.getThreadId());
            item.put("title", r.getTitle());
            item.put("createdAt", r.getCreatedAt());
            item.put("lastActiveAt", r.getLastActiveAt());
            item.put("messageCount", r.getMessageCount() != null ? r.getMessageCount() : 0);
            item.put("runStatus", r.getRunStatus());
            item.put("executing", false);
            item.put("configId", r.getConfigId());
            item.put("configName", r.getConfigName());
            item.put("configProtocol", r.getConfigProtocol());
            item.put("configModel", r.getConfigModel());
            return item;
        }).collect(Collectors.toList());
    }

    /**
     * 创建新线程（新 in-memory 状态 + DB 记录），设为当前活跃线程。
     */
    public Map<String, Object> createThread(HttpSession httpSession, User user, String title, Integer configId) {
        String threadId = "platform-ai-" + UUID.randomUUID();
        PlatformAiState state = PlatformAiStateStore.create(threadId);
        httpSession.setAttribute(SESSION_ATTR_PLATFORM_AI_STATE_ID, threadId);
        AiModelConfig config = resolveOptionalChannel(configId);
        if (config != null) {
            state.setAiConfigId(config.getId());
        }

        String safeTitle = (title != null && !title.isBlank()) ? title : "新对话";
        conversationStore.createPlatformThread(
                user.getUserId(),
                httpSession.getId(),
                threadId,
                safeTitle,
                state.getCreatedAt(),
                config);

        Map<String, Object> info = new LinkedHashMap<>();
        info.put("threadId", threadId);
        info.put("title", safeTitle);
        info.put("configId", state.getAiConfigId());
        return info;
    }

    /**
     * 删除指定线程（DB 记录 + in-memory 状态）。
     */
    public void deleteThread(HttpSession httpSession, String threadId) {
        if (threadId == null || threadId.isBlank()) return;
        PlatformAiState state = PlatformAiStateStore.get(threadId);
        if (state != null) {
            state.stopGeneration("线程已删除");
            PlatformAiStateStore.remove(threadId);
        }
        conversationStore.deleteThread(threadId);
        Object activeId = httpSession.getAttribute(SESSION_ATTR_PLATFORM_AI_STATE_ID);
        if (threadId.equals(activeId)) {
            httpSession.removeAttribute(SESSION_ATTR_PLATFORM_AI_STATE_ID);
        }
    }

    /**
     * 重命名指定线程。
     */
    public void renameThread(String threadId, String title) {
        if (threadId == null || threadId.isBlank()) return;
        String safeTitle = (title != null && !title.isBlank()) ? title.trim() : "未命名对话";
        conversationStore.renameThread(threadId, safeTitle);
    }

    /**
     * 激活指定线程（切换当前会话的活跃线程）。
     */
    public PlatformAiState activateThread(HttpSession httpSession, String threadId) {
        if (threadId == null || threadId.isBlank()) {
            throw ApiException.badRequest("缺少 threadId");
        }
        AiThreadRecord record = conversationStore.findThread(threadId);
        if (record == null) {
            throw ApiException.notFound("线程不存在");
        }
        PlatformAiState state = PlatformAiStateStore.get(threadId);
        if (state == null) {
            state = PlatformAiStateStore.create(threadId);
        }
        state.setAiConfigId(record.getConfigId());
        state.setMode(record.getMode());
        httpSession.setAttribute(SESSION_ATTR_PLATFORM_AI_STATE_ID, threadId);
        return state;
    }

    public Map<String, Object> events(HttpSession httpSession, Long requestedAfterSeq, Integer requestedLimit) {
        PlatformAiState state = requireState(httpSession, "AI 会话不存在");
        long afterSeq = requestedAfterSeq != null ? Math.max(0L, requestedAfterSeq) : 0L;
        int limit = requestedLimit != null ? requestedLimit : 200;

        List<Map<String, Object>> events = new ArrayList<>();
        for (AiSseEvent event : state.recentSseEventsAfter(afterSeq, limit)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("seq", event.seq());
            item.put("timestamp", event.timestamp());
            item.put("name", event.name());
            item.put("data", event.data());
            if (event.subagentInvocationId() != null) {
                item.put("subagentInvocationId", event.subagentInvocationId());
            }
            events.add(item);
        }

        HashMap<String, Object> data = new HashMap<>();
        data.put("events", events);
        data.put("lastSeq", state.getLastSseEventSeq());
        data.put("runStatus", state.getRunStatus());
        data.putAll(runtimeSnapshot(state, 0L));
        return data;
    }

    public Map<String, Object> messages(HttpSession httpSession, Integer requestedOffset, Integer requestedLimit) {
        PlatformAiState state = requireState(httpSession, "AI 会话不存在");
        int offset = requestedOffset != null ? Math.max(0, requestedOffset) : 0;
        int limit = requestedLimit != null ? requestedLimit : 50;
        HashMap<String, Object> data = new HashMap<>();
        data.put("messages", conversationStore.listMessages(state.getStateId(), offset, limit));
        data.put("total", conversationStore.countMessages(state.getStateId()));
        data.put("offset", offset);
        data.put("limit", limit);
        return data;
    }

    private Map<String, Object> runtimeSnapshot(PlatformAiState state, long startMs) {
        HashMap<String, Object> payload = new HashMap<>();
        payload.put("status", state.getRunStatus());
        payload.put("executing", state.isExecuting());
        payload.put("elapsedMs", startMs > 0 ? Math.max(0L, System.currentTimeMillis() - startMs) : 0L);
        payload.put("lastSeq", state.getLastSseEventSeq());
        payload.put("stopReason", state.getStopReason());
        return payload;
    }

    private void sendRecordedStatusSafely(PlatformAiState state, SseEmitter emitter, String status) {
        state.recordSseEvent("status", status);
        AiControllerUtil.safeSendStatus(emitter, status);
    }

    private void sendRecordedEvent(PlatformAiState state, SseEmitter emitter, String name, Object data) throws Exception {
        AiSseEvent event = state.recordSseEvent(name, data, extractSubagentInvocationId(data));
        sendExistingEvent(emitter, event);
    }

    private void sendExistingEvent(SseEmitter emitter, AiSseEvent event) throws Exception {
        SseEmitter.SseEventBuilder builder = SseEmitter.event()
                .name(event.name());
        if (event.data() instanceof String s) {
            builder.data(s, org.springframework.http.MediaType.TEXT_PLAIN);
        } else {
            builder.data(event.data() != null ? event.data() : "");
        }
        if (event.seq() > 0) {
            builder.id(String.valueOf(event.seq()));
        }
        synchronized (emitter) {
            emitter.send(builder);
        }
    }

    private QueueDrainHandle startQueueDrain(PlatformAiState state,
                                             SseEmitter emitter,
                                             List<AiSseEvent> eventLog) {
        AtomicBoolean stopped = new AtomicBoolean(false);
        Future<?> future = SSE_QUEUE_DRAIN_EXECUTOR.submit(() -> {
            // 每 5s 没有真实事件就补一个 heartbeat，让前端 watchdog 知道连接还活着。
            // 前端阈值 15s，5s 间隔留有冗余。
            final long HEARTBEAT_INTERVAL_MS = 5_000L;
            long lastSentAt = System.currentTimeMillis();
            try {
                while (!stopped.get() || !state.getAiSseEventQueue().isEmpty()) {
                    AiSseEvent event = state.getAiSseEventQueue().poll(200, TimeUnit.MILLISECONDS);
                    if (event != null) {
                        eventLog.add(event);
                        sendExistingEvent(emitter, event);
                        lastSentAt = System.currentTimeMillis();
                        continue;
                    }
                    if (!stopped.get()
                            && System.currentTimeMillis() - lastSentAt >= HEARTBEAT_INTERVAL_MS) {
                        Map<String, Object> hb = new LinkedHashMap<>();
                        hb.put("ts", System.currentTimeMillis());
                        hb.put("status", state.getRunStatus());
                        try {
                            sendHeartbeat(emitter, hb);
                            lastSentAt = System.currentTimeMillis();
                        } catch (Exception hbErr) {
                            stopped.set(true);
                            logger.debug("Platform AI 心跳发送失败，停止 drain: {}", hbErr.getMessage());
                            break;
                        }
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                stopped.set(true);
                logger.debug("Platform AI SSE 队列下发停止: {}", e.getMessage());
            }
        });
        return new QueueDrainHandle(stopped, future);
    }

    /** 旁路下发 heartbeat 事件（不入 eventLog、不分配 seq、不进持久化）。 */
    private void sendHeartbeat(SseEmitter emitter, Map<String, Object> payload) throws Exception {
        SseEmitter.SseEventBuilder builder = SseEmitter.event()
                .name("heartbeat")
                .data(payload != null ? payload : new LinkedHashMap<>());
        synchronized (emitter) {
            emitter.send(builder);
        }
    }

    private void stopAndFlushQueuedEvents(PlatformAiState state,
                                          SseEmitter emitter,
                                          List<AiSseEvent> eventLog,
                                          QueueDrainHandle queueDrain) {
        if (queueDrain != null) {
            queueDrain.stopped().set(true);
            try {
                queueDrain.future().get(1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception ignored) {
                // 仍继续同步 flush 队列中尚未发送的事件。
            }
        }
        AiSseEvent event;
        boolean sendAvailable = true;
        while ((event = state.getAiSseEventQueue().poll()) != null) {
            eventLog.add(event);
            if (!sendAvailable) continue;
            try {
                sendExistingEvent(emitter, event);
            } catch (Exception e) {
                sendAvailable = false;
                logger.debug("Platform AI SSE 队列 flush 停止: {}", e.getMessage());
            }
        }
    }

    private record QueueDrainHandle(AtomicBoolean stopped, Future<?> future) {}

    private String extractSubagentInvocationId(Object data) {
        if (data instanceof Map<?, ?> map) {
            Object id = map.get("parentSubagentInvocationId");
            if (id == null) id = map.get("subagentInvocationId");
            if (id != null) {
                String text = String.valueOf(id);
                return text.isBlank() ? null : text;
            }
        }
        return null;
    }

    private void sendRecordedEventSafely(PlatformAiState state, SseEmitter emitter, String name, Object data) {
        try {
            sendRecordedEvent(state, emitter, name, data);
        } catch (Exception e) {
            // 客户端已断开连接，忽略
        }
    }

    private Map<String, Object> buildUsageEvent(dev.langchain4j.model.chat.response.ChatResponse response) {
        Map<String, Object> usage = new LinkedHashMap<>();
        if (response == null) return usage;
        if (response.id() != null) usage.put("id", response.id());
        if (response.modelName() != null) usage.put("model", response.modelName());
        if (response.finishReason() != null) usage.put("finishReason", response.finishReason().name().toLowerCase());
        dev.langchain4j.model.output.TokenUsage tokenUsage = response.tokenUsage();
        if (tokenUsage != null) {
            usage.put("inputTokens", tokenUsage.inputTokenCount());
            usage.put("outputTokens", tokenUsage.outputTokenCount());
            usage.put("totalTokens", tokenUsage.totalTokenCount());
            if (tokenUsage instanceof dev.langchain4j.model.openai.OpenAiTokenUsage openaiUsage) {
                var inputDetails = openaiUsage.inputTokensDetails();
                if (inputDetails != null && inputDetails.cachedTokens() != null) {
                    usage.put("cachedInputTokens", inputDetails.cachedTokens());
                }
                var outputDetails = openaiUsage.outputTokensDetails();
                if (outputDetails != null && outputDetails.reasoningTokens() != null) {
                    usage.put("reasoningTokens", outputDetails.reasoningTokens());
                }
            }
        }
        usage.put("timestamp", System.currentTimeMillis());
        return usage;
    }

    private void accumulateTokenUsage(AiRuntimeStats stats, Map<String, Object> usage) {
        long input = toLong(usage.get("inputTokens"));
        long output = toLong(usage.get("outputTokens"));
        long total = toLong(usage.get("totalTokens"));
        long cachedInput = toLong(usage.get("cachedInputTokens"));
        long reasoning = toLong(usage.get("reasoningTokens"));

        stats.accumulateTokenUsage(input, output, total, cachedInput, reasoning);

        Map<String, Object> cumulative = new LinkedHashMap<>();
        cumulative.put("inputTokens", stats.getCumulativeInputTokens());
        cumulative.put("outputTokens", stats.getCumulativeOutputTokens());
        cumulative.put("totalTokens", stats.getCumulativeTotalTokens());
        cumulative.put("cachedInputTokens", stats.getCumulativeCachedInputTokens());
        cumulative.put("reasoningTokens", stats.getCumulativeReasoningTokens());
        cumulative.put("turnCount", stats.getTurnCount());
        usage.put("cumulative", cumulative);
    }

    private static long toLong(Object value) {
        if (value instanceof Number num) return num.longValue();
        return 0L;
    }

    private void sendEventSafely(SseEmitter emitter, String eventName, String data) {
        try {
            emitter.send(SseEmitter.event().name(eventName).data(data != null ? data : ""));
        } catch (Exception e) {
            // 客户端已断开连接，忽略
        }
    }

    private void finishRun(String runId, String status, long startMs, String output,
                           String errorMessage, int toolCallCount) {
        if (runId != null) {
            conversationStore.finishRun(runId, status, startMs, output, errorMessage, toolCallCount);
        }
    }

    private void persistAssistantMessage(PlatformAiState state, String content,
                                         List<AiSseEvent> eventLog,
                                         Map<String, Object> review) {
        persistAssistantMessage(state, content, eventLog, review, null);
    }

    private void persistAssistantMessage(PlatformAiState state, String content,
                                         List<AiSseEvent> eventLog,
                                         Map<String, Object> review,
                                         Object parsedPlan) {
        List<Object> nodes = new ArrayList<>();
        for (int i = 0; i < eventLog.size(); i++) {
            AiSseEvent event = eventLog.get(i);
            String name = event.name();
            String kind = kindOf(event.data());
            // 收集所有节点相关事件到统一列表，排除 node(kind=tool) 起始事件，
            // 只保留 patch(kind=tool) 完成事件，避免历史恢复时同一工具调用出现重复节点。
            if ("thinking".equals(name)
                    || ("node".equals(name) && ("thinking".equals(kind)
                            || "text".equals(kind)
                            || "plan".equals(kind) || "subtask".equals(kind)))
                    || ("patch".equals(name) && "tool".equals(kind))) {
                long seq = event.seq() > 0 ? event.seq() : (i + 1L);
                nodes.add(withEventSeq(event, seq));
            }
        }
        Object plan = parsedPlan != null ? parsedPlan : state.getCurrentPlan();
        conversationStore.appendMessage(state.getStateId(), "assistant", content,
                nodes, review, plan);
    }

    /**
     * 将事件 payload 转换为 Map 并注入 {@code seq}，使历史接口能复原与直播一致的事件顺序。
     * 失败时回退到原始 payload，保证持久化流程不被破坏。
     */
    private static Object withEventSeq(AiSseEvent event, long seq) {
        Object payload = event.data();
        if (payload == null) return null;
        try {
            String json = JSON.toJSONString(payload);
            @SuppressWarnings("unchecked")
            Map<String, Object> map = JSON.parseObject(json, Map.class);
            if (map == null) return payload;
            map.putIfAbsent("seq", seq);
            return map;
        } catch (Exception e) {
            return payload;
        }
    }

    private Map<String, Object> buildTurnReview(String output, List<AiSseEvent> eventLog, long durationMs) {
        LinkedHashMap<String, Object> review = new LinkedHashMap<>();
        int toolCount = 0;
        int successCount = 0;
        int failureCount = 0;
        List<String> tools = new ArrayList<>();
        for (AiSseEvent event : eventLog) {
            if (!("patch".equals(event.name()) && "tool".equals(kindOf(event.data())))) continue;
            toolCount++;
            Object data = event.data();
            if (data instanceof Map<?, ?> map) {
                Object success = map.get("success");
                if (Boolean.FALSE.equals(success)) {
                    failureCount++;
                } else {
                    successCount++;
                }
                Object toolName = map.get("toolName");
                if (toolName instanceof String name && !name.isBlank() && !tools.contains(name)) {
                    tools.add(name);
                }
            } else {
                successCount++;
            }
        }
        review.put("durationMs", Math.max(0L, durationMs));
        review.put("toolCount", toolCount);
        review.put("successCount", successCount);
        review.put("failureCount", failureCount);
        review.put("tools", tools);
        review.put("conclusionPreview", truncate(output != null ? output.trim() : "", 500));
        review.put("createdAt", System.currentTimeMillis());
        return review;
    }

    private String withPersistedHistoryContext(PlatformAiState state, String guardedMessage) {
        if (state.getLastSseEventSeq() > 0) return guardedMessage;
        int total = conversationStore.countMessages(state.getStateId());
        if (total <= 1) return guardedMessage;
        List<Map<String, Object>> messages = conversationStore.recentMessages(state.getStateId(), 16);
        StringBuilder history = new StringBuilder();
        int totalChars = 0;
        for (Map<String, Object> msg : messages) {
            String role = msg.get("role") != null ? String.valueOf(msg.get("role")) : "";
            if (!"user".equals(role) && !"assistant".equals(role)) continue;
            String content = msg.get("content") != null ? String.valueOf(msg.get("content")).trim() : "";
            if (content.isEmpty()) continue;
            content = truncate(content, 1200);
            String label = "user".equals(role) ? "用户" : "助手";
            String block = label + ":\n" + content + "\n\n";
            if (totalChars + block.length() > 8000) break;
            history.append(block);
            totalChars += block.length();
        }
        if (history.isEmpty()) return guardedMessage;
        return """
                【历史对话摘录】
                以下内容来自当前平台 AI 线程的数据库持久化记录。模型运行态刚重建时，请把这些摘录作为延续上下文。

                %s
                【当前请求】
                %s
                """.formatted(history.toString().trim(), guardedMessage);
    }

    public PlatformAiState getState(HttpSession httpSession) {
        Object stateId = httpSession.getAttribute(SESSION_ATTR_PLATFORM_AI_STATE_ID);
        if (!(stateId instanceof String id) || id.isBlank()) {
            return null;
        }
        return PlatformAiStateStore.get(id);
    }

    public PlatformAiState requireState(HttpSession httpSession, String message) {
        PlatformAiState state = getState(httpSession);
        if (state == null) {
            throw ApiException.notFound(message);
        }
        return state;
    }

    private AiModelConfig resolveChannel(Integer configId) {
        try {
            AiModelConfig config = modelConfigService.resolve(configId);
            if (config == null) {
                if (configId != null) {
                    throw ApiException.notFound("AI 模型不存在或已删除，configId: " + configId);
                }
                throw ApiException.notFound("未配置激活的 AI 模型，请先在设置中添加并激活一条");
            }
            return config;
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw ApiException.notFound(e.getMessage());
        }
    }

    private AiModelConfig resolveOptionalChannel(Integer configId) {
        if (configId == null) return null;
        return resolveChannel(configId);
    }

    private void applyThreadModel(PlatformAiState state) {
        AiModelConfig config = resolveChannel(state != null ? state.getAiConfigId() : null);
        dynamicModelProvider.refreshFromConfig(config);
        if (state != null) {
            state.setAiConfigId(config.getId());
        }
    }

    private PlatformAiState recreateState(HttpSession httpSession) {
        Object existing = httpSession.getAttribute(SESSION_ATTR_PLATFORM_AI_STATE_ID);
        if (existing instanceof String stateId && !stateId.isBlank()) {
            PlatformAiState existingState = PlatformAiStateStore.get(stateId);
            if (existingState != null) {
                existingState.stopGeneration("平台 AI 会话已重建");
            }
            PlatformAiStateStore.remove(stateId);
        }
        String stateId = "platform-ai-" + UUID.randomUUID();
        PlatformAiState state = PlatformAiStateStore.create(stateId);
        httpSession.setAttribute(SESSION_ATTR_PLATFORM_AI_STATE_ID, stateId);
        return state;
    }

    /** 从 Map payload 安全提取 {@code kind} 字段。 */
    private static String kindOf(Object data) {
        if (data instanceof Map<?, ?> map) {
            Object kind = map.get("kind");
            if (kind instanceof String s) return s;
        }
        return null;
    }

    /** 统计 eventLog 中工具调用次数。 */
    private static int countToolCallEvents(List<AiSseEvent> eventLog) {
        int count = 0;
        for (AiSseEvent event : eventLog) {
            if ("patch".equals(event.name()) && "tool".equals(kindOf(event.data()))) {
                count++;
            }
        }
        return count;
    }

    private static String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() > max ? value.substring(0, max) + "\n...(已截断)" : value;
    }

}
