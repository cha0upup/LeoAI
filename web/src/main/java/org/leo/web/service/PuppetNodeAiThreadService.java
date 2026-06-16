package org.leo.web.service;

import dev.langchain4j.model.chat.response.StreamingHandle;
import dev.langchain4j.service.TokenStream;
import org.leo.ai.agent.AiToolConfirmationCallback;
import org.leo.ai.agent.PuppetNodeAgent;
import org.leo.ai.channel.AiModelConfigService;
import org.leo.ai.config.AiAgentProperties;
import org.leo.ai.service.AiErrorClassifier;
import org.leo.ai.service.SessionWarmupService;
import org.leo.ai.tools.puppetnode.SubAgentDispatchTools;
import org.leo.ai.thread.AiConversationStoreService;
import com.alibaba.fastjson.JSON;
import org.leo.core.entity.AiModelConfig;
import org.leo.core.entity.AiPlan;
import org.leo.core.entity.AiPlanStatus;
import org.leo.core.entity.AiRuntimeStats;
import org.leo.core.entity.AiChatAuditEntry;
import org.leo.core.entity.AiExecutionPolicy;
import org.leo.core.entity.AiSseEvent;
import org.leo.core.entity.AiThreadRecord;
import org.leo.core.session.AiThread;
import org.leo.core.session.PuppetNodeSession;
import org.leo.core.util.session.PuppetNodeSessionWorkDirUtil;
import org.leo.web.exception.ApiException;
import org.leo.web.util.AiControllerUtil;
import org.leo.web.util.ControllerUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Puppet AI 线程生命周期和持久化服务。
 *
 * <p>控制器只负责 HTTP/SSE 编排；线程恢复、索引维护、通道快照和历史消息持久化
 * 集中在这里，避免控制器继续膨胀。
 */
@Service
public class PuppetNodeAiThreadService {

    private static final Logger logger = LoggerFactory.getLogger(PuppetNodeAiThreadService.class);
    private static final ExecutorService SSE_QUEUE_DRAIN_EXECUTOR =
            Executors.newCachedThreadPool(r -> {
                Thread thread = new Thread(r, "puppet-ai-sse-queue-drain");
                thread.setDaemon(true);
                return thread;
            });

    private final PuppetNodeAgent puppetNodeAgent;
    private final AiModelConfigService modelConfigService;
    private final AiErrorClassifier aiErrorClassifier;
    private final AiConversationStoreService conversationStore;
    private final SessionWarmupService sessionWarmupService;
    private final AiAgentProperties agentProperties;

    @Autowired
    public PuppetNodeAiThreadService(PuppetNodeAgent puppetNodeAgent,
                                     AiModelConfigService modelConfigService,
                                     AiErrorClassifier aiErrorClassifier,
                                     AiConversationStoreService conversationStore,
                                     SessionWarmupService sessionWarmupService,
                                     AiAgentProperties agentProperties) {
        this.puppetNodeAgent = puppetNodeAgent;
        this.modelConfigService = modelConfigService;
        this.aiErrorClassifier = aiErrorClassifier;
        this.conversationStore = conversationStore;
        this.sessionWarmupService = sessionWarmupService;
        this.agentProperties = agentProperties;
    }

    public void executeChat(PuppetNodeSession session,
                            AiThread thread,
                            String threadId,
                            String messageForAgent,
                            AiChatAuditEntry audit,
                            SseEmitter emitter,
                            long startMs) {
        thread.markExecuting(Thread.currentThread());
        String memoryId = session.getSessionId() + ":" + threadId;
        String runId = null;
        AiTimelineRecorder recorder = new AiTimelineRecorder(
                (name, data) -> sendRecordedEventSafely(thread, emitter, name, data));
        List<AiSseEvent> eventLog = recorder.eventLog();
        thread.getSseEventQueue().clear();
        QueueDrainHandle queueDrain = startQueueDrain(thread, emitter, eventLog);

        try {
            if (thread.isStopRequested()) {
                throw new InterruptedException("已停止");
            }
            runId = conversationStore.startRun(thread, messageForAgent, startMs);
            conversationStore.updateRuntime(session.getSessionId(), thread);
            sendRecordedEvent(thread, emitter, "status", AiThread.STATUS_RUNNING);
            SubAgentDispatchTools.setEventEmitter(session.getSessionId(), threadId,
                    (eventName, payload) -> sendRecordedEventSafely(thread, emitter, eventName, payload));

            final String fRunId = runId;
            AtomicReference<StreamingHandle> handleRef = new AtomicReference<>();
            TokenStream stream = puppetNodeAgent.chat(memoryId, messageForAgent);

            stream
                .onPartialThinking(thinking -> {
                    if (!recorder.hasPendingThinking()) {
                        logger.info("[Thinking] 开始接收思考内容, memoryId={}", memoryId);
                    }
                    recorder.appendThinking(thinking.text());
                })
                .onPartialResponseWithContext((partial, ctx) -> {
                    handleRef.compareAndSet(null, ctx.streamingHandle());
                    // 收到正文 token 意味着思考阶段结束，flush thinking buffer
                    recorder.appendVisibleDelta(partial.text());
                    recorder.flushDelta();
                })
                .onPartialToolCall(partial -> {
                    recorder.onBoundary();
                    Map<String, Object> toolData = AiToolEventFactory.buildToolDeltaEventData(partial);
                    sendRecordedEventSafely(thread, emitter, "tool_delta", toolData);
                })
                .beforeToolExecution(execution -> {
                    recorder.onBoundary();
                    Map<String, Object> toolData = AiToolEventFactory.buildToolStartEventData(execution);
                    sendRecordedEventSafely(thread, emitter, "node", toolData);
                    recorder.recordExternal("node", toolData);
                    // 高影响工具确认拦截（检查会话授权、计划预批准，不满足时阻塞等待用户确认）
                    new AiToolConfirmationCallback(thread).accept(execution);
                })
                .onToolExecuted(execution -> {
                    try {
                        // 工具执行完毕意味着一轮思考结束，flush thinking buffer
                        recorder.flushThinking();
                        Map<String, Object> toolData = AiToolEventFactory.buildToolEventData(execution);
                        sendRecordedEventSafely(thread, emitter, "patch", toolData);
                        recorder.recordExternal("patch", toolData);
                    } catch (Exception e) {
                        logger.warn("构建工具事件失败: {}", e.getMessage());
                    }
                })
                .onCompleteResponse(response -> {
                    // 最终响应前 flush 残余 thinking + 闭合最后一段可见文本
                    recorder.onBoundary();
                    String output = recorder.reply();
                    // turnHolder[0] 在 try 块中构建，在 finally 中发送（跨块共享）
                    Object[] turnHolder = { null };
                    try {
                        int toolCallCount = countToolCallEvents(eventLog);
                        audit.complete(output, toolCallCount, System.currentTimeMillis() - startMs);
                        finishRun(fRunId, AiThread.STATUS_COMPLETED, startMs, output, null, toolCallCount);

                        Map<String, Object> review = buildTurnReview(output, eventLog, System.currentTimeMillis() - startMs);
                        Map<String, Object> usage = buildUsageEvent(response);
                        Map<String, Object> turn = new LinkedHashMap<>();
                        if (!usage.isEmpty()) {
                            // 累计 token 用量到线程级统计
                            accumulateTokenUsage(thread, usage);
                            turn.put("usage", usage);
                        }
                        turn.put("review", review);
                        turnHolder[0] = turn;

                        stopAndFlushQueuedEvents(thread, emitter, eventLog, queueDrain);
                        persistAssistantMessage(session, threadId, output, eventLog, review, thread.getCurrentPlan());
                        thread.markCompleted();
                        conversationStore.updateRuntime(session.getSessionId(), thread);
                    } catch (Exception e) {
                        logger.warn("onCompleteResponse 后续处理异常: {}", e.getMessage(), e);
                        thread.markCompleted();
                    } finally {
                        // turn 事件合并 content + usage + review，必须最后发送（前端收到后关闭流）
                        @SuppressWarnings("unchecked")
                        Map<String, Object> turn = turnHolder[0] instanceof Map<?, ?> m
                                ? (Map<String, Object>) m : new LinkedHashMap<>();
                        turn.putIfAbsent("content", output);
                        sendRecordedEventSafely(thread, emitter, "status", AiThread.STATUS_COMPLETED);
                        sendRecordedEventSafely(thread, emitter, "turn", turn);
                        AiControllerUtil.safeComplete(emitter);
                        thread.clearExecuting();
                        SubAgentDispatchTools.clearEventEmitter(session.getSessionId(), threadId);
                    }
                })
                .onError(error -> {
                    try {
                        recorder.flushDelta();
                        stopAndFlushQueuedEvents(thread, emitter, eventLog, queueDrain);
                        if (thread.isStopRequested()) {
                            thread.markCancelled();
                            String reason = thread.getStopReason() != null ? thread.getStopReason() : "已停止";
                            audit.fail(reason, System.currentTimeMillis() - startMs);
                            finishRun(fRunId, AiThread.STATUS_CANCELLED, startMs, null, reason, 0);
                            conversationStore.updateRuntime(session.getSessionId(), thread);
                            sendRecordedStatusSafely(thread, emitter, AiThread.STATUS_CANCELLED);
                            AiControllerUtil.safeSendError(emitter, reason);
                        } else {
                            AiErrorClassifier.Classification classification = aiErrorClassifier.classify(error);
                            String errMsg = classification.message();
                            thread.markFailed();
                            audit.fail(errMsg, System.currentTimeMillis() - startMs);
                            thread.cancelAllPendingConfirmations();
                            finishRun(fRunId, AiThread.STATUS_FAILED, startMs, null, errMsg, 0);
                            conversationStore.updateRuntime(session.getSessionId(), thread);
                            sendRecordedStatusSafely(thread, emitter, AiThread.STATUS_FAILED);
                            AiControllerUtil.safeSendError(emitter, classification);
                        }
                    } catch (Exception e) {
                        logger.warn("onError 处理异常: {}", e.getMessage(), e);
                    } finally {
                        AiControllerUtil.safeComplete(emitter);
                        thread.clearExecuting();
                        SubAgentDispatchTools.clearEventEmitter(session.getSessionId(), threadId);
                    }
                })
                .start();

            // 注册停止回调
            thread.setStopCallback(() -> {
                StreamingHandle handle = handleRef.get();
                if (handle != null) {
                    handle.cancel();
                }
            });

        } catch (Exception e) {
            if (thread.isStopRequested()) {
                stopAndFlushQueuedEvents(thread, emitter, eventLog, queueDrain);
                thread.markCancelled();
                String reason = thread.getStopReason() != null ? thread.getStopReason() : "已停止";
                audit.fail(reason, System.currentTimeMillis() - startMs);
                finishRun(runId, AiThread.STATUS_CANCELLED, startMs, null, reason, 0);
                conversationStore.updateRuntime(session.getSessionId(), thread);
                sendRecordedStatusSafely(thread, emitter, AiThread.STATUS_CANCELLED);
                AiControllerUtil.safeSendError(emitter, reason);
            } else {
                stopAndFlushQueuedEvents(thread, emitter, eventLog, queueDrain);
                AiErrorClassifier.Classification classification = aiErrorClassifier.classify(e);
                String errMsg = classification.message();
                failThread(thread, audit, errMsg, startMs);
                finishRun(runId, AiThread.STATUS_FAILED, startMs, null, errMsg, 0);
                conversationStore.updateRuntime(session.getSessionId(), thread);
                sendRecordedStatusSafely(thread, emitter, AiThread.STATUS_FAILED);
                AiControllerUtil.safeSendError(emitter, classification);
            }
            thread.clearExecuting();
            SubAgentDispatchTools.clearEventEmitter(session.getSessionId(), threadId);
        }
    }

    private void failThread(AiThread thread, AiChatAuditEntry audit, String message, long startMs) {
        thread.markFailed();
        audit.fail(message, System.currentTimeMillis() - startMs);
        thread.cancelAllPendingConfirmations();
    }

    private void finishRun(String runId, String status, long startMs, String output,
                           String errorMessage, int toolCallCount) {
        if (runId != null) {
            conversationStore.finishRun(runId, status, startMs, output, errorMessage, toolCallCount);
        }
    }

    private void sendRecordedEvent(AiThread thread, SseEmitter emitter, String name, Object data) throws Exception {
        AiSseEvent event = thread.recordSseEvent(name, data, extractSubagentInvocationId(data));
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

    private QueueDrainHandle startQueueDrain(AiThread thread,
                                             SseEmitter emitter,
                                             List<AiSseEvent> eventLog) {
        AtomicBoolean stopped = new AtomicBoolean(false);
        Future<?> future = SSE_QUEUE_DRAIN_EXECUTOR.submit(() -> {
            // 每 5s 没有真实事件就补一个 heartbeat，让前端 watchdog 知道连接还活着。
            // 前端 watchdog 阈值为 15s，5s 间隔留有冗余。
            final long HEARTBEAT_INTERVAL_MS = 5_000L;
            long lastSentAt = System.currentTimeMillis();
            try {
                while (!stopped.get() || !thread.getSseEventQueue().isEmpty()) {
                    AiSseEvent event = thread.getSseEventQueue().poll(200, TimeUnit.MILLISECONDS);
                    if (event != null) {
                        eventLog.add(event);
                        sendExistingEvent(emitter, event);
                        lastSentAt = System.currentTimeMillis();
                        continue;
                    }
                    // 队列空闲：检查是否需要发心跳
                    if (!stopped.get()
                            && System.currentTimeMillis() - lastSentAt >= HEARTBEAT_INTERVAL_MS) {
                        Map<String, Object> hb = new LinkedHashMap<>();
                        hb.put("ts", System.currentTimeMillis());
                        hb.put("status", thread.getRunStatus());
                        hb.put("pendingConfirmations", thread.getPendingConfirmationCount());
                        // 心跳事件不进 eventLog（不希望被当成业务事件持久化），
                        // 直接旁路下发。recordSseEvent 也不调用，seq 设为 0 避免污染序号。
                        try {
                            sendHeartbeat(emitter, hb);
                            lastSentAt = System.currentTimeMillis();
                        } catch (Exception hbErr) {
                            // 心跳失败说明客户端已断开，停止 drain 循环
                            stopped.set(true);
                            logger.debug("Puppet AI 心跳发送失败，停止 drain: {}", hbErr.getMessage());
                            break;
                        }
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                stopped.set(true);
                logger.debug("Puppet AI SSE 队列下发停止: {}", e.getMessage());
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

    private void stopAndFlushQueuedEvents(AiThread thread,
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
        while ((event = thread.getSseEventQueue().poll()) != null) {
            eventLog.add(event);
            if (!sendAvailable) continue;
            try {
                sendExistingEvent(emitter, event);
            } catch (Exception e) {
                sendAvailable = false;
                logger.debug("Puppet AI SSE 队列 flush 停止: {}", e.getMessage());
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

    private void sendRecordedEventSafely(AiThread thread, SseEmitter emitter, String name, Object data) {
        try {
            sendRecordedEvent(thread, emitter, name, data);
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

    /**
     * 将本轮 token 用量累加到线程级统计，并在 usage 事件中附加累计数据。
     */
    private void accumulateTokenUsage(AiThread thread, Map<String, Object> usage) {
        long input = toLong(usage.get("inputTokens"));
        long output = toLong(usage.get("outputTokens"));
        long total = toLong(usage.get("totalTokens"));
        long cachedInput = toLong(usage.get("cachedInputTokens"));
        long reasoning = toLong(usage.get("reasoningTokens"));

        AiRuntimeStats stats = thread.getRuntimeStats();
        stats.accumulateTokenUsage(input, output, total, cachedInput, reasoning);

        // 附加累计数据到 usage 事件
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

    private void sendRecordedStatusSafely(AiThread thread, SseEmitter emitter, String status) {
        thread.recordSseEvent("status", status);
        AiControllerUtil.safeSendStatus(emitter, status);
    }

    private Map<String, Object> runtimeSnapshot(AiThread thread, long startMs) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", thread.getRunStatus());
        if (startMs > 0) {
            payload.put("elapsedMs", Math.max(0L, System.currentTimeMillis() - startMs));
        }
        payload.put("pendingConfirmations", thread.getPendingConfirmationCount());
        payload.put("confirmationExpiresAt", thread.getNextConfirmationExpiresAt());
        payload.put("stopReason", thread.getStopReason());
        payload.put("lastSeq", thread.getLastSseEventSeq());
        payload.put("executing", thread.isExecuting());
        return payload;
    }

    public ThreadResolution ensureThreadReady(PuppetNodeSession session, String threadId, Integer configId) {
        AiThread thread = session.getAiThread(threadId);
        boolean restored = false;
        AiThreadRecord persisted = findPersistedThread(session, threadId);
        if (thread == null) {
            thread = restorePersistedThread(session, threadId, persisted);
            restored = thread != null;
        }
        Integer resolvedConfigId = resolveConfigId(configId, thread, persisted);
        AiModelConfig resolvedChannel;
        try {
            resolvedChannel = resolveOptionalChannel(resolvedConfigId);
        } catch (ApiException | IllegalArgumentException | IllegalStateException e) {
            boolean hasCheckpoint = thread != null && hasThreadCheckpoint(session, threadId);
            return new ThreadResolution(thread, restored, hasCheckpoint, e.getMessage());
        }
        if (resolvedChannel != null) {
            resolvedConfigId = resolvedChannel.getId();
        }
        String configError = validateConfigId(resolvedConfigId);
        boolean hasCheckpoint = thread != null && hasThreadCheckpoint(session, threadId);
        if (configError != null) {
            return new ThreadResolution(thread, restored, hasCheckpoint, configError);
        }
        if (thread != null && thread.getAiConfigId() == null) {
            thread.setAiConfigId(resolvedConfigId);
        }
        // 异步预热：确保 basicInfo、OS 平台、环境变量缓存就绪
        sessionWarmupService.warmupAsync(session.getSessionId());
        // 自动授予 session 级全量权限，消除确认等待对并行工具执行的阻塞
        if (thread != null && agentProperties.getInterceptor().isAutoGrantSession()) {
            thread.grantSessionAll();
        }
        return new ThreadResolution(thread, restored, hasCheckpoint, null);
    }

    public AiThread requireThread(PuppetNodeSession session, String threadId) {
        AiThread thread = session.getAiThread(threadId);
        if (thread == null) {
            throw ApiException.notFound("线程不存在，threadId: " + threadId);
        }
        return thread;
    }

    public Map<String, Object> listThreads(PuppetNodeSession session) {
        String userId = session.getCreateByUser();
        String puppetId = PuppetNodeSessionWorkDirUtil.resolvePuppetId(session);

        List<AiThread> memThreads = session.listAiThreads();
        List<Map<String, Object>> result = new ArrayList<>();
        Map<String, AiThread> memById = new LinkedHashMap<>();
        for (AiThread thread : memThreads) memById.put(thread.getThreadId(), thread);
        if (puppetId != null) {
            for (AiThreadRecord record : conversationStore.listPuppetThreads(userId, puppetId)) {
                AiThread thread = memById.remove(record.getThreadId());
                Map<String, Object> item = thread != null
                        ? threadToMap(thread, safeMessageCount(record.getMessageCount()))
                        : threadRecordToMap(record);
                item.put("configName", record.getConfigName());
                item.put("configProtocol", record.getConfigProtocol());
                item.put("configModel", record.getConfigModel());
                item.put("hasCheckpoint", hasThreadCheckpoint(session, record.getThreadId()));
                result.add(item);
            }
        }
        for (AiThread thread : memById.values()) {
            Map<String, Object> item = threadToMap(thread, conversationStore.countMessages(thread.getThreadId()));
            if (puppetId != null) {
                item.put("hasCheckpoint", hasThreadCheckpoint(session, thread.getThreadId()));
            }
            result.add(item);
        }

        if (puppetId == null) {
            for (AiThread thread : memThreads) {
                if (result.stream().noneMatch(item -> thread.getThreadId().equals(item.get("threadId")))) {
                    result.add(threadToMap(thread, 0));
                }
            }
        }

        result.sort((a, b) -> Long.compare(
                ControllerUtil.toLong(b.get("lastActiveAt")), ControllerUtil.toLong(a.get("lastActiveAt"))));

        HashMap<String, Object> data = new HashMap<>();
        data.put("threads", result);
        data.put("activeThreadId", session.getActiveThreadId());
        return data;
    }

    public Map<String, Object> createThread(PuppetNodeSession session, String requestedTitle, Integer configId) {
        return createThread(session, requestedTitle, configId, null);
    }

    public Map<String, Object> createThread(PuppetNodeSession session, String requestedTitle, Integer configId,
                                            String mode) {
        String threadId = UUID.randomUUID().toString();
        String title = requestedTitle;
        if (title == null || title.isBlank()) {
            title = "对话 " + (session.listAiThreads().size() + 1);
        }

        AiModelConfig config = resolveOptionalChannel(configId);
        Integer resolvedConfigId = config != null ? config.getId() : null;

        AiThread thread = session.createAiThread(threadId, title);
        thread.setAiConfigId(resolvedConfigId);
        thread.setMode(mode);

        // 异步预热：预填 basicInfo、OS 平台、环境变量缓存
        sessionWarmupService.warmupAsync(session.getSessionId());
        // 自动授予 session 级全量权限，消除确认等待对并行工具执行的阻塞
        if (agentProperties.getInterceptor().isAutoGrantSession()) {
            thread.grantSessionAll();
        }

        String userId = session.getCreateByUser();
        String puppetId = PuppetNodeSessionWorkDirUtil.resolvePuppetId(session);
        if (puppetId != null) {
            conversationStore.createPuppetThread(userId, puppetId, session.getSessionId(), thread, config);
        }

        HashMap<String, Object> info = new HashMap<>();
        info.put("threadId", threadId);
        info.put("title", title);
        info.put("configId", resolvedConfigId);
        info.put("mode", thread.getMode());
        info.put("reconSummaryLoaded", session.hasReconSummary());
        info.put("grantedTypesCount", thread.getSessionGrantedTypes().size());
        info.put("grantedAll", thread.isSessionGrantedAll());
        return info;
    }

    public void deleteThread(PuppetNodeSession session, String threadId) {
        session.removeAiThread(threadId);

        String puppetId = PuppetNodeSessionWorkDirUtil.resolvePuppetId(session);
        if (puppetId != null) {
            conversationStore.deleteThread(threadId);
            PuppetNodeSessionWorkDirUtil.deleteAiThreadCheckpoints(session.getCreateByUser(), puppetId, threadId);
        }
    }

    public void renameThread(PuppetNodeSession session, String threadId, String title) {
        AiThread thread = session.getAiThread(threadId);
        if (thread != null) {
            thread.setTitle(title);
        }
        conversationStore.renameThread(threadId, title);
    }

    public Map<String, Object> threadMessages(PuppetNodeSession session, String threadId,
                                              Integer requestedOffset, Integer requestedLimit) {
        int offset = requestedOffset != null ? requestedOffset : 0;
        int limit = requestedLimit != null ? requestedLimit : 50;
        List<Map<String, Object>> messages = conversationStore.listMessages(threadId, offset, limit);
        int total = conversationStore.countMessages(threadId);

        HashMap<String, Object> data = new HashMap<>();
        data.put("messages", messages);
        data.put("total", total);
        data.put("offset", offset);
        data.put("limit", limit);
        return data;
    }

    public Map<String, Object> threadEvents(PuppetNodeSession session, String threadId,
                                            Long requestedAfterSeq, Integer requestedLimit) {
        AiThread thread = requireThread(session, threadId);
        long afterSeq = requestedAfterSeq != null ? Math.max(0L, requestedAfterSeq) : 0L;
        int limit = requestedLimit != null ? requestedLimit : 200;

        List<Map<String, Object>> events = new ArrayList<>();
        for (AiSseEvent event : thread.recentSseEventsAfter(afterSeq, limit)) {
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
        data.put("lastSeq", thread.getLastSseEventSeq());
        data.put("runStatus", thread.getRunStatus());
        data.putAll(runtimeSnapshot(thread, 0L));
        return data;
    }

    public Map<String, Object> resetThread(PuppetNodeSession session, String threadId, Integer requestedConfigId) {
        AiThread thread = session.getAiThread(threadId);
        AiThreadRecord persisted = findPersistedThread(session, threadId);
        if (thread == null) {
            thread = restorePersistedThread(session, threadId, persisted);
        }
        if (thread == null) {
            throw ApiException.notFound("线程不存在，threadId: " + threadId);
        }

        AiModelConfig config = resolveOptionalChannel(resolveConfigId(requestedConfigId, thread, persisted));
        Integer resolvedConfigId = config != null ? config.getId() : null;

        thread.stop();
        thread.clearSseEvents();
        thread.resetRuntimeStats();
        thread.setExecutionPolicy(AiExecutionPolicy.defaultPolicy());
        thread.resetTurnCount();
        thread.resetSessionGrants();
        thread.setAiConfigId(resolvedConfigId);

        updateThreadMeta(session, thread);
        updateThreadConfig(session, thread, config);

        HashMap<String, Object> info = new HashMap<>();
        info.put("reconSummaryLoaded", session.hasReconSummary());
        info.put("grantedTypesCount", thread.getSessionGrantedTypes().size());
        info.put("grantedAll", thread.isSessionGrantedAll());
        return info;
    }

    public void switchChannel(PuppetNodeSession session, String threadId, Integer requestedConfigId) {
        AiThread thread = session.getAiThread(threadId);
        AiThreadRecord persisted = findPersistedThread(session, threadId);
        if (thread == null) {
            thread = restorePersistedThread(session, threadId, persisted);
        }
        if (thread == null) {
            throw ApiException.notFound("线程不存在，threadId: " + threadId);
        }

        AiModelConfig config = resolveOptionalChannel(resolveConfigId(requestedConfigId, thread, persisted));
        Integer configId = config != null ? config.getId() : null;
        thread.setAiConfigId(configId);
        updateThreadConfig(session, thread, config);
    }

    public Map<String, Object> switchMode(PuppetNodeSession session, String threadId, String mode) {
        AiThread thread = session.getAiThread(threadId);
        AiThreadRecord persisted = findPersistedThread(session, threadId);
        if (thread == null) {
            thread = restorePersistedThread(session, threadId, persisted);
        }
        if (thread == null) {
            throw ApiException.notFound("线程不存在，threadId: " + threadId);
        }

        if (mode != null) {
            thread.setMode(mode);
        }
        conversationStore.updateMode(threadId, thread.getMode());

        HashMap<String, Object> info = new HashMap<>();
        info.put("threadId", threadId);
        info.put("mode", thread.getMode());
        return info;
    }

    public String withPersistedHistoryContext(PuppetNodeSession session, String threadId, String guardedMessage) {
        int total = conversationStore.countMessages(threadId);
        if (total <= 0) return guardedMessage;

        List<Map<String, Object>> messages = conversationStore.recentMessages(threadId, 16);

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
                以下内容来自当前 AI 线程的数据库持久化记录。该线程刚恢复运行态，模型短期记忆为空；请把这些摘录作为延续上下文，避免重复询问已完成事项。

                %s
                【当前请求】
                %s
                """.formatted(history.toString().trim(), guardedMessage);
    }

    public void persistMessage(PuppetNodeSession session, String threadId, String role, String content) {
        try {
            conversationStore.appendMessage(threadId, role, content);
        } catch (Exception e) {
            logger.warn("持久化消息失败, threadId={}: {}", threadId, e.getMessage());
        }
    }

    public void persistAssistantMessage(PuppetNodeSession session, String threadId,
                                        String content, List<AiSseEvent> eventLog) {
        persistAssistantMessage(session, threadId, content, eventLog, null, null);
    }

    public void persistAssistantMessage(PuppetNodeSession session, String threadId,
                                        String content, List<AiSseEvent> eventLog,
                                        Map<String, Object> review) {
        persistAssistantMessage(session, threadId, content, eventLog, review, null);
    }

    public void persistAssistantMessage(PuppetNodeSession session, String threadId,
                                        String content, List<AiSseEvent> eventLog,
                                        Map<String, Object> review,
                                        Object planSnapshot) {
        try {
            List<Object> nodes = new ArrayList<>();

            for (int i = 0; i < eventLog.size(); i++) {
                AiSseEvent event = eventLog.get(i);
                String name = event.name();
                String kind = kindOf(event.data());
                // 收集所有节点相关事件（thinking / text / plan / subtask）到统一列表，
                // 排除 node(kind=tool) 起始事件，只保留 patch(kind=tool) 完成事件，
                // 按 eventLog 位置注入 seq，确保历史恢复时时间线顺序与直播一致。
                if ("thinking".equals(name)
                        || ("node".equals(name) && ("thinking".equals(kind)
                                || "text".equals(kind)
                                || "plan".equals(kind) || "subtask".equals(kind)))
                        || ("patch".equals(name) && "tool".equals(kind))) {
                    long seq = event.seq() > 0 ? event.seq() : (i + 1L);
                    nodes.add(withEventSeq(event, seq));
                }
            }

            conversationStore.appendMessage(threadId, "assistant", content,
                    nodes, review, planSnapshot);
        } catch (Exception e) {
            logger.warn("持久化 assistant 消息失败, threadId={}: {}", threadId, e.getMessage());
        }
    }

    /**
     * 将事件 payload 转换为 Map 并注入 {@code seq}，使历史记录能复原与直播一致的事件顺序。
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
            // payload 自带的 seq 优先（极少见，仅在测试中），否则注入持久化时统一计算的 seq
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

    public void updateThreadMeta(PuppetNodeSession session, AiThread thread) {
        try {
            conversationStore.updateRuntime(session.getSessionId(), thread);
        } catch (Exception e) {
            logger.warn("更新线程运行状态失败, threadId={}: {}", thread.getThreadId(), e.getMessage());
        }
    }

    private AiThread restorePersistedThread(PuppetNodeSession session, String threadId, AiThreadRecord record) {
        if (session == null || threadId == null || threadId.isBlank()) return null;
        String puppetId = PuppetNodeSessionWorkDirUtil.resolvePuppetId(session);
        String userId = session.getCreateByUser();
        if (puppetId == null || record == null) return null;
        if (!puppetId.equals(record.getPuppetId())) return null;
        if (record.getUserId() != null && !record.getUserId().equals(userId)) return null;

        String title = record.getTitle() != null && !record.getTitle().isBlank()
                ? record.getTitle() : "历史对话";
        AiThread thread = session.restoreAiThread(threadId, title,
                record.getCreatedAt() != null ? record.getCreatedAt() : 0L,
                record.getLastActiveAt() != null ? record.getLastActiveAt() : 0L);
        thread.setAiConfigId(record.getConfigId());
        thread.setMode(record.getMode());
        thread.setParentThreadId(record.getParentThreadId());
        restoreLatestPlan(thread, threadId);
        logger.debug("已从数据库恢复 AI 线程, sessionId={}, threadId={}, messages={}",
                session.getSessionId(), threadId, safeMessageCount(record.getMessageCount()));
        return thread;
    }

    /**
     * 从最近的助手消息中读取 plan 快照并恢复到线程的 planHistory，
     * 让重启后用户预批准过的步骤继续生效，不需要重新派发计划。
     *
     * <p>幂等性：若线程已有 currentPlan（例如并发恢复或同会话二次激活），直接返回，不重复 append。
     *
     * <p>陈旧性过滤：只还原 {@code PLANNING} / {@code IN_PROGRESS} 状态的计划。
     * 已 {@code COMPLETED} / {@code FAILED} 的计划如果还原成 currentPlan，
     * 会让 AI 误以为有"已完成的当前任务"，触发错误的 updatePlanStep/completePlan 调用。
     */
    private void restoreLatestPlan(AiThread thread, String threadId) {
        if (thread.getCurrentPlan() != null) return; // 幂等：已恢复过或本会话已新建
        try {
            String planJson = conversationStore.findLatestPlanJson(threadId);
            if (planJson == null) return;
            AiPlan plan = JSON.parseObject(planJson, AiPlan.class);
            if (plan == null) return;
            AiPlanStatus status = plan.getStatus();
            if (status != AiPlanStatus.PLANNING && status != AiPlanStatus.IN_PROGRESS) {
                logger.debug("跳过线程 {} 的陈旧计划恢复（状态 {} 已终结）", threadId, status);
                return;
            }
            thread.addPlan(plan);
            logger.debug("已恢复线程 {} 的活跃计划快照（状态 {}，共 {} 步）",
                    threadId, status, plan.getSteps() == null ? 0 : plan.getSteps().size());
        } catch (Exception e) {
            logger.warn("恢复线程 {} 的计划快照失败：{}", threadId, e.getMessage());
        }
    }

    private AiThreadRecord findPersistedThread(PuppetNodeSession session, String threadId) {
        if (session == null || threadId == null || threadId.isBlank()) return null;
        return conversationStore.findThread(threadId);
    }

    private Integer resolveConfigId(Integer requestedConfigId, AiThread thread, AiThreadRecord record) {
        if (requestedConfigId != null) return requestedConfigId;
        if (thread != null && thread.getAiConfigId() != null) return thread.getAiConfigId();
        return record != null ? record.getConfigId() : null;
    }

    private boolean hasThreadCheckpoint(PuppetNodeSession session, String threadId) {
        String puppetId = PuppetNodeSessionWorkDirUtil.resolvePuppetId(session);
        return puppetId != null && PuppetNodeSessionWorkDirUtil.hasAiThreadCheckpoint(
                session.getCreateByUser(), puppetId, threadId);
    }

    private AiModelConfig resolveChannel(Integer configId) {
        try {
            AiModelConfig resolved = modelConfigService.resolve(configId);
            if (resolved == null) {
                if (configId != null) {
                    throw ApiException.notFound("AI 模型不存在或已删除，configId: " + configId);
                }
                throw ApiException.notFound("未配置激活的 AI 模型，请先在设置中添加并激活一条");
            }
            return resolved;
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw ApiException.notFound(e.getMessage());
        }
    }

    private AiModelConfig resolveOptionalChannel(Integer configId) {
        if (configId == null) return null;
        return resolveChannel(configId);
    }

    private String validateConfigId(Integer configId) {
        // 新架构下不再做能力探测，仅校验存在性
        if (configId == null) return null;
        try {
            AiModelConfig config = modelConfigService.findById(configId);
            if (config != null) {
                return null;
            }
            return "AI 通道不存在或已删除，configId: " + configId + "，请切换 AI 通道后重试";
        } catch (Exception e) {
            return "AI 通道校验失败，configId: " + configId + "，请检查配置后重试";
        }
    }

    private Map<String, Object> threadToMap(AiThread thread, int messageCount) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("threadId", thread.getThreadId());
        item.put("title", thread.getTitle());
        item.put("createdAt", thread.getCreatedAt());
        item.put("lastActiveAt", thread.getLastActiveAt());
        item.put("messageCount", messageCount);
        item.put("configId", thread.getAiConfigId());
        item.put("runStatus", thread.getRunStatus());
        item.put("executing", thread.isExecuting());
        item.put("pendingConfirmations", thread.getPendingConfirmationCount());
        item.put("mode", thread.getMode());
        item.put("parentThreadId", thread.getParentThreadId());
        item.put("inMemory", true);
        return item;
    }

    private Map<String, Object> threadRecordToMap(AiThreadRecord record) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("threadId", record.getThreadId());
        item.put("title", record.getTitle());
        item.put("createdAt", record.getCreatedAt());
        item.put("lastActiveAt", record.getLastActiveAt());
        item.put("messageCount", safeMessageCount(record.getMessageCount()));
        item.put("configId", record.getConfigId());
        item.put("configName", record.getConfigName());
        item.put("configProtocol", record.getConfigProtocol());
        item.put("configModel", record.getConfigModel());
        item.put("runStatus", record.getRunStatus() != null ? record.getRunStatus() : AiThread.STATUS_IDLE);
        item.put("executing", false);
        item.put("pendingConfirmations", 0);
        item.put("mode", record.getMode() != null ? record.getMode() : AiThread.MODE_AUTO);
        item.put("parentThreadId", record.getParentThreadId());
        item.put("inMemory", false);
        return item;
    }

    private void updateThreadConfig(PuppetNodeSession session, AiThread thread, AiModelConfig config) {
        try {
            String puppetId = PuppetNodeSessionWorkDirUtil.resolvePuppetId(session);
            if (puppetId == null) return;
            conversationStore.updateConfig(thread.getThreadId(), config);
        } catch (Exception e) {
            logger.warn("更新线程通道配置失败, threadId={}: {}", thread.getThreadId(), e.getMessage());
        }
    }

    private static int safeMessageCount(Integer count) {
        return count != null ? count : 0;
    }

    /** 从 Map payload 安全提取 {@code kind} 字段。 */
    private static String kindOf(Object data) {
        if (data instanceof Map<?, ?> map) {
            Object kind = map.get("kind");
            if (kind instanceof String s) return s;
        }
        return null;
    }

    /** 统计 eventLog 中主 Agent 的工具调用次数。 */
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

    public record ThreadResolution(AiThread thread, boolean restoredFromPersistence,
                                   boolean hasPersistentCheckpoint, String errorMessage) {
    }
}
