package org.leo.ai.thread;

import org.leo.ai.channel.DynamicModelProvider;
import org.leo.ai.runtime.AiTurnTrace;
import org.leo.core.entity.AiModelConfig;
import org.leo.core.entity.AiMessageRecord;
import org.leo.core.entity.AiRunRecord;
import org.leo.core.entity.AiThreadRecord;
import org.leo.core.entity.AiTurnRecord;
import org.leo.core.ai.AiRunStatus;
import org.leo.core.entity.AiUserInputRequest;
import org.leo.core.session.AiThread;
import org.leo.core.ai.AiEventStreamRuntime;
import org.leo.core.entity.AiSseEvent;
import org.leo.core.entity.AiThreadLeaseRecord;
import org.leo.dao.mapper.AiConversationMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AiConversationStoreService {

    public static final String SCOPE_PUPPET = "puppet";
    public static final String SCOPE_PLATFORM = "platform";
    public static final String MESSAGE_PENDING = "pending";
    public static final String MESSAGE_COMMITTED = "committed";
    public static final String MESSAGE_DISCARDED = "discarded";
    public static final String ERROR_CANCELLED = "cancelled";
    public static final String ERROR_PERSISTENCE = "persistence";

    private final AiConversationMapper mapper;
    private final AiEventJournalRepository eventJournal;
    private final AiExecutionLeaseRepository executionLease;
    private final AiTurnTerminalRepository turnTerminal;
    private final AiProtocolTurnRepository protocolTurn;
    private final AiUserInputRepository userInput;
    private final AiSubagentInvocationRepository subagentInvocations;
    private final AiMessageRepository messages;
    private final AiContextCheckpointRepository checkpoints;

    public AiConversationStoreService(AiConversationMapper mapper,
                                      AiEventJournalRepository eventJournal,
                                      AiExecutionLeaseRepository executionLease,
                                      AiTurnTerminalRepository turnTerminal,
                                      AiProtocolTurnRepository protocolTurn,
                                      AiUserInputRepository userInput,
                                      AiSubagentInvocationRepository subagentInvocations,
                                      AiMessageRepository messages,
                                      AiContextCheckpointRepository checkpoints) {
        this.mapper = mapper;
        this.eventJournal = eventJournal;
        this.executionLease = executionLease;
        this.turnTerminal = turnTerminal;
        this.protocolTurn = protocolTurn;
        this.userInput = userInput;
        this.subagentInvocations = subagentInvocations;
        this.messages = messages;
        this.checkpoints = checkpoints;
    }

    public List<AiThreadRecord> listPuppetThreads(String userId, String puppetId) {
        if (isBlank(puppetId)) {
            return List.of();
        }
        return mapper.listThreads(SCOPE_PUPPET, userId, puppetId);
    }

    public List<AiThreadRecord> listPlatformThreads(String userId) {
        if (isBlank(userId)) {
            return List.of();
        }
        return mapper.listPlatformThreads(userId);
    }

    public AiThreadRecord findThread(String threadId) {
        if (isBlank(threadId)) {
            return null;
        }
        return mapper.findThread(threadId);
    }

    public void createPuppetThread(String userId, String puppetId, String sessionId,
                                   AiThread thread, AiModelConfig config) {
        AiThreadRecord row = new AiThreadRecord();
        row.setThreadId(thread.getThreadId());
        row.setScope(SCOPE_PUPPET);
        row.setUserId(userId);
        row.setPuppetId(puppetId);
        row.setSessionId(sessionId);
        row.setTitle(thread.getTitle());
        applyConfig(row, config);
        row.setCreatedAt(thread.getCreatedAt());
        row.setLastActiveAt(thread.getLastActiveAt());
        row.setMessageCount(0);
        row.setRunStatus(AiRunStatus.IDLE);
        row.setParentThreadId(thread.getParentThreadId());
        mapper.insertThread(row);
    }

    public void createPlatformThread(String userId, String sessionId, String threadId,
                                     String title, long createdAt, AiModelConfig config) {
        AiThreadRecord row = new AiThreadRecord();
        row.setThreadId(threadId);
        row.setScope(SCOPE_PLATFORM);
        row.setUserId(userId);
        row.setSessionId(sessionId);
        row.setTitle(isBlank(title) ? "平台 AI" : title);
        applyConfig(row, config);
        row.setCreatedAt(createdAt > 0 ? createdAt : System.currentTimeMillis());
        row.setLastActiveAt(row.getCreatedAt());
        row.setMessageCount(0);
        row.setRunStatus(AiRunStatus.IDLE);
        mapper.insertThread(row);
    }

    public void renameThread(String threadId, String title) {
        mapper.renameThread(threadId, title, System.currentTimeMillis());
    }

    public void updateRuntime(String sessionId, AiThread thread) {
        updateRuntime(sessionId, thread, null);
    }

    public void updateRuntime(String sessionId, AiThread thread, String leaseToken) {
        if (thread == null) return;
        updateRuntime(sessionId, thread.getThreadId(), thread.getLastActiveAt(),
                thread.getRunStatus(), leaseToken);
    }

    public void updateRuntime(String sessionId, String threadId, long lastActiveAt, String runStatus) {
        updateRuntime(sessionId, threadId, lastActiveAt, runStatus, null);
    }

    public void updateRuntime(String sessionId, String threadId, long lastActiveAt,
                              String runStatus, String leaseToken) {
        long writeAt = System.currentTimeMillis();
        long activeAt = lastActiveAt > 0 ? lastActiveAt : writeAt;
        if (isBlank(leaseToken)) {
            AiThreadRecord row = new AiThreadRecord();
            row.setThreadId(threadId);
            row.setSessionId(sessionId);
            row.setLastActiveAt(activeAt);
            row.setRunStatus(runStatus);
            mapper.updateThreadRuntime(row);
            return;
        }
        requireFencedWrite(mapper.updateThreadRuntimeFenced(
                threadId, sessionId, activeAt, runStatus, leaseToken, writeAt),
                "线程运行状态", threadId);
    }

    public void updateConfig(String threadId, AiModelConfig config) {
        AiThreadRecord row = new AiThreadRecord();
        row.setThreadId(threadId);
        row.setLastActiveAt(System.currentTimeMillis());
        applyConfig(row, config);
        mapper.updateThreadConfig(row);
    }

    public ConversationCheckpoint findContextCheckpoint(String threadId) {
        return checkpoints.find(threadId);
    }

    public void updateContextCheckpoint(String threadId,
                                        String contextSummary,
                                        long boundarySequence,
                                        String boundaryHash,
                                        int version) {
        checkpoints.update(threadId, contextSummary, boundarySequence, boundaryHash, version);
    }

    public void clearContextCheckpoint(String threadId) {
        checkpoints.clear(threadId);
    }

    public void deleteThread(String threadId) {
        mapper.deleteThread(threadId);
    }

    // ── 持久化 Turn 控制协议 ────────────────────────────────────────────────

    public AiTurnRecord findProtocolTurnByClientId(
            String threadId, String clientUserMessageId) {
        if (isBlank(threadId) || isBlank(clientUserMessageId)) return null;
        return protocolTurn.findByClientId(threadId, clientUserMessageId);
    }

    public AiTurnRecord findProtocolTurn(String turnId) {
        return protocolTurn.find(turnId);
    }

    public AiTurnRecord findNextQueuedProtocolTurn(String threadId) {
        if (isBlank(threadId)) return null;
        return protocolTurn.findNextQueued(threadId);
    }

    public List<AiTurnRecord> listInProgressProtocolTurns(String threadId) {
        return protocolTurn.listInProgress(threadId);
    }

    public List<String> listDispatchableProtocolThreadIds() {
        return protocolTurn.listDispatchableThreadIds();
    }

    /**
     * 原子接收用户命令。Turn 和两条可展示消息先于任何运行时对象持久化，
     * 因此刷新页面后 queued/running/cancelling 都可仅从数据库恢复。
     */
    @Transactional
    public boolean reserveProtocolTurn(AiTurnRecord turn,
                                       String userContent,
                                       Object attachments) {
        return reserveProtocolTurn(turn, userContent, attachments, null);
    }

    @Transactional
    public boolean reserveProtocolTurn(AiTurnRecord turn,
                                       String userContent,
                                       Object attachments,
                                       String answerToQuestionId) {
        if (turn == null || mapper.insertProtocolTurn(turn) != 1) return false;
        messages.append(
                turn.getUserItemId(), turn.getThreadId(), turn.getTurnId(),
                null, MESSAGE_PENDING, "user", userContent,
                null, null, null, attachments);
        messages.append(
                turn.getAssistantItemId(), turn.getThreadId(), turn.getTurnId(),
                null, MESSAGE_PENDING, "assistant", "",
                null, null, null, null);
        if (!isBlank(answerToQuestionId)) {
            AiUserInputRequest request = userInput.find(answerToQuestionId);
            String normalizedAnswer = userContent != null ? userContent.trim() : "";
            if (request == null || !turn.getThreadId().equals(request.getThreadId())
                    || !AiUserInputRequest.STATUS_PENDING.equals(request.getStatus())) {
                throw new IllegalStateException("待回答问题不存在或已处理");
            }
            if (normalizedAnswer.isBlank()) {
                throw new IllegalStateException("用户回答不能为空");
            }
            if (!Boolean.TRUE.equals(request.getAllowFreeText())
                    && !request.optionValues().contains(normalizedAnswer)) {
                throw new IllegalStateException("回答必须从问题提供的选项中选择");
            }
            if (!userInput.answer(answerToQuestionId, turn.getThreadId(), normalizedAnswer)) {
                throw new IllegalStateException(
                        "待回答问题不存在、已处理或已过期");
            }
        }
        return true;
    }

    // ── Agent 等待用户输入 ────────────────────────────────────────────────

    public AiUserInputRequest findUserInputRequest(String requestId) {
        return userInput.find(requestId);
    }

    public AiUserInputRequest findPendingUserInputRequest(String threadId) {
        return userInput.findPending(threadId);
    }

    public AiUserInputRequest createUserInputRequest(AiUserInputRequest request) {
        return userInput.create(request);
    }

    /** 原子消费一次高风险操作确认，避免同一确认被并发工具调用复用。 */
    @Transactional
    public boolean consumeConfirmation(String requestId, String threadId,
                                       String toolName, String argumentsHash,
                                       long consumedAt) {
        return userInput.consumeConfirmation(
                requestId, threadId, toolName, argumentsHash, consumedAt);
    }

    public boolean claimProtocolTurnStart(String turnId, long startedAt) {
        return protocolTurn.claimStart(turnId, startedAt);
    }

    @Transactional
    public AiTurnRecord requestProtocolTurnInterrupt(
            String threadId, String turnId) {
        mapper.requestProtocolTurnInterrupt(threadId, turnId);
        AiTurnRecord current = findProtocolTurn(turnId);
        if (current != null && "queued".equals(current.getDispatchStatus())) {
            AiTurnRecord completed = completeProtocolTurn(
                    turnId, "interrupted", null, System.currentTimeMillis());
            mapper.updateTurnMessageStatus(
                    threadId, turnId, MESSAGE_DISCARDED);
            Map<String, Object> turn = new LinkedHashMap<>();
            turn.put("id", turnId);
            turn.put("threadId", threadId);
            turn.put("status", "interrupted");
            turn.put("interruptRequested", true);
            appendEvent(threadId, new AiSseEvent(
                    mapper.findLastEventSeq(threadId) + 1L,
                    System.currentTimeMillis(),
                    "turn/completed",
                    Map.of("turn", turn),
                    null,
                    turnId,
                    current.getAssistantItemId(),
                    null));
            return completed;
        }
        return current;
    }

    public boolean hasInterruptRequestedTurn(String threadId) {
        return !isBlank(threadId)
                && mapper.countInterruptRequestedTurns(threadId) > 0;
    }

    public AiTurnRecord completeProtocolTurn(
            String turnId, String protocolStatus, String errorMessage, long completedAt) {
        return completeProtocolTurn(
                turnId, protocolStatus, errorMessage, completedAt, null);
    }

    public AiTurnRecord completeProtocolTurn(
            String turnId, String protocolStatus, String errorMessage,
            long completedAt, String leaseToken) {
        return protocolTurn.complete(
                turnId, protocolStatus, errorMessage, completedAt, leaseToken);
    }

    public AiTurnRecord requeueProtocolTurn(String turnId) {
        return protocolTurn.requeue(turnId);
    }

    // ── 可重放事件日志 ───────────────────────────────────────────────────────

    /**
     * 将运行时绑定到数据库事件日志。绑定时先恢复线程级最新序号，保证进程重启后
     * 新事件仍沿用同一个单调游标。
     */
    public void attachEventJournal(String threadId, AiEventStreamRuntime runtime) {
        eventJournal.attach(threadId, runtime, this::appendEvent);
    }

    public void appendEvent(String threadId, AiSseEvent event) {
        appendEvent(threadId, event, null);
    }

    public void appendEvent(String threadId, AiSseEvent event, String leaseToken) {
        eventJournal.append(threadId, event, leaseToken);
    }

    public List<AiSseEvent> listEventsAfter(String threadId, long afterSeq, int limit) {
        return eventJournal.listAfter(threadId, afterSeq, limit);
    }

    public long findLastEventSeq(String threadId) {
        return eventJournal.lastSequence(threadId);
    }

    public boolean hasTurnCompletedEvent(String threadId, String turnId) {
        return eventJournal.hasTurnCompleted(threadId, turnId);
    }

    public long findLatestTurnStartSeq(String threadId) {
        return eventJournal.latestTurnStartSequence(threadId);
    }

    public boolean hasLatestTurnCompletedEvent(String threadId) {
        return eventJournal.hasLatestTurnCompleted(threadId);
    }

    // ── 跨实例执行租约与孤儿 Run 收口 ───────────────────────────────────────

    public boolean acquireThreadLease(AiThreadLeaseRecord lease) {
        return executionLease.acquire(lease);
    }

    public boolean renewThreadLease(AiThreadLeaseRecord lease) {
        return executionLease.renew(lease);
    }

    public boolean releaseThreadLease(AiThreadLeaseRecord lease) {
        return executionLease.release(lease);
    }

    public List<AiThreadLeaseRecord> listExpiredThreadLeases(long now) {
        return executionLease.listExpired(now);
    }

    public List<String> listThreadsWithStuckRunningTurns(long now) {
        return executionLease.listStuckThreads(now);
    }

    public boolean claimExpiredThreadLease(AiThreadLeaseRecord expired,
                                           AiThreadLeaseRecord recovery) {
        return executionLease.claimExpired(expired, recovery);
    }

    /**
     * 将失去执行实例的 running Run 原子收口，并写入可重放的 turn/completed。
     */
    @Transactional
    public List<AiSseEvent> recoverOrphanedRuns(String threadId, long finishedAt) {
        return executionLease.recoverOrphanedRuns(threadId, finishedAt);
    }

    // ── 子 Agent 派发记录 ───────────────────────────────────────────────────

    /** 写入一条 pending 派发记录，调用方需要先填好 invocationId / parentThreadId / task。 */
    public void insertSubagentInvocation(org.leo.core.entity.AiSubagentInvocation row) {
        subagentInvocations.insert(row);
    }

    /** 更新派发状态：running / completed / failed / cancelled，以及 summary、childThreadId。 */
    public void updateSubagentInvocation(org.leo.core.entity.AiSubagentInvocation row) {
        subagentInvocations.update(row);
    }

    public List<org.leo.core.entity.AiSubagentInvocation> listSubagentInvocations(String parentThreadId) {
        return subagentInvocations.listByParentThread(parentThreadId);
    }


    /** 原子创建 Turn，并关联预留 Item、运行记录、追踪信息和执行租约。 */
    @Transactional
    public PersistedTurn beginTurn(String requestedTurnId,
                                   String requestedUserItemId,
                                   String requestedAssistantItemId,
                                   String threadId,
                                   Integer configId,
                                   String input,
                                   String userContent,
                                   Object attachments,
                                   long startedAt,
                                   String runtimeJson,
                                   AiTurnTrace trace,
                                   String leaseToken) {
        if (trace == null) {
            throw new IllegalArgumentException("trace 不能为空");
        }
        String turnId = requestedTurnId != null && !requestedTurnId.isBlank()
                ? requestedTurnId.trim() : UUID.randomUUID().toString();
        String runId = UUID.randomUUID().toString();
        trace.bind(turnId, runId);

        AiTurnRecord reservedTurn = mapper.findTurnById(turnId);
        if (reservedTurn == null) {
            AiTurnRecord turn = new AiTurnRecord();
            turn.setTurnId(turnId);
            turn.setThreadId(threadId);
            turn.setStatus(MESSAGE_PENDING);
            turn.setCreatedAt(startedAt);
            mapper.insertTurn(turn);
        }

        AiRunRecord run = new AiRunRecord();
        run.setRunId(runId);
        run.setThreadId(threadId);
        run.setTurnId(turnId);
        run.setStatus(AiRunStatus.RUNNING);
        run.setStartedAt(startedAt);
        run.setConfigId(configId);
        run.setInput(input);
        run.setRuntimeJson(runtimeJson);
        run.setTraceId(trace.traceId());
        run.setTraceJson(trace.toJson());
        run.setLeaseToken(emptyToNull(leaseToken));
        if (isBlank(leaseToken)) {
            if (mapper.insertRun(run) != 1) {
                throw new IllegalStateException("Run 创建失败: " + runId);
            }
        } else {
            requireFencedWrite(mapper.insertRunFenced(
                            run, System.currentTimeMillis()),
                    "Run 创建", runId);
        }

        String userMessageId;
        String assistantMessageId;
        if (reservedTurn != null
                && !isBlank(reservedTurn.getUserItemId())
                && !isBlank(reservedTurn.getAssistantItemId())) {
            int attached = mapper.attachRunToTurnMessages(
                    threadId, turnId, runId);
            if (attached != 2) {
                throw new IllegalStateException(
                        "Turn 预留消息不完整: " + turnId);
            }
            userMessageId = reservedTurn.getUserItemId();
            assistantMessageId = reservedTurn.getAssistantItemId();
        } else {
            userMessageId = messages.append(
                    requestedUserItemId,
                    threadId, turnId, runId, MESSAGE_PENDING,
                    "user", userContent, null, null, null, attachments);
            assistantMessageId = messages.append(
                    requestedAssistantItemId,
                    threadId, turnId, runId, MESSAGE_PENDING,
                    "assistant", "", null, null, null, null);
        }
        return new PersistedTurn(
                turnId, runId, threadId, userMessageId, assistantMessageId,
                startedAt, emptyToNull(leaseToken));
    }

    /** 持久化 Presenter 完成后的最终阶段轨迹；失败不应改变已经确定的 Turn 终态。 */
    public void updateRunTrace(PersistedTurn turn, AiTurnTrace trace) {
        if (turn == null || trace == null) return;
        if (isBlank(turn.leaseToken())) {
            AiRunRecord row = new AiRunRecord();
            row.setRunId(turn.runId());
            row.setTraceJson(trace.toJson());
            mapper.updateRunTrace(row);
            return;
        }
        requireFencedWrite(mapper.updateRunTraceFenced(
                        turn.runId(), trace.toJson(), turn.leaseToken(),
                        System.currentTimeMillis()),
                "Run Trace", turn.runId());
    }

    /**
     * 原子提交成功 Turn：写入 assistant 消息、提交用户消息并结束 Run。
     */
    @Transactional
    public void completeTurn(PersistedTurn turn, String output,
                             List<Object> nodes, Map<String, Object> review,
                             Object planSnapshot, int toolCallCount) {
        turnTerminal.complete(turn, output, nodes, review, planSnapshot, toolCallCount);
    }

    /**
     * 丢弃未完成 Turn 的上下文消息，同时保留记录供历史界面和审计追溯。
     */
    @Transactional
    public void discardTurn(PersistedTurn turn, String runStatus, String errorCategory,
                            String errorMessage, String rawErrorMessage, int toolCallCount) {
        discardTurn(turn, runStatus, errorCategory, errorMessage,
                rawErrorMessage, toolCallCount, "", List.of(), null);
    }

    /**
     * 中断/失败时保存已经产生的 assistant Item，状态仍为 discarded。
     * 当内容包含实际执行进度时，后续上下文查询会连同原始 user Item
     * 一起载入，供“继续”任务衔接。
     */
    @Transactional
    public void discardTurn(PersistedTurn turn,
                            String runStatus,
                            String errorCategory,
                            String errorMessage,
                            String rawErrorMessage,
                            int toolCallCount,
                            String partialOutput,
                            List<Object> partialNodes,
                            Object planSnapshot) {
        turnTerminal.discard(turn, runStatus, errorCategory, errorMessage,
                rawErrorMessage, toolCallCount, partialOutput, partialNodes,
                planSnapshot);
    }

    public List<Map<String, Object>> listMessages(String threadId, int offset, int limit) {
        int safeOffset = Math.max(0, offset);
        int safeLimit = limit < 0 ? Integer.MAX_VALUE : Math.max(1, Math.min(limit, 200));
        return messages.list(threadId, safeOffset, safeLimit);
    }

    public List<ConversationMessage> committedMessages(String threadId, int limit) {
        return mapper.recentMessages(threadId, Math.max(1, Math.min(limit, 200))).stream()
                .map(record -> new ConversationMessage(
                        record.getMessageSeq(), record.getRole(), record.getContent()))
                .toList();
    }

    /**
     * 返回可用于模型记忆的持久化消息。除了成功消息，也包含有实际执行进度的
     * discarded Turn，使用户在异常中止后输入“继续”时可以从已有结果衔接。
     */
    public List<ConversationMessage> contextMessages(String threadId, int limit) {
        return mapper.recentContextMessages(
                        threadId, Math.max(1, Math.min(limit, 200))).stream()
                .map(record -> new ConversationMessage(
                        record.getMessageSeq(), record.getRole(), record.getContent()))
                .toList();
    }

    public ConversationMessage committedMessage(String threadId, long messageSequence) {
        AiMessageRecord record = mapper.findCommittedMessageBySequence(threadId, messageSequence);
        return record == null ? null : new ConversationMessage(
                record.getMessageSeq(), record.getRole(), record.getContent());
    }

    public ConversationMessage contextMessage(String threadId, long messageSequence) {
        AiMessageRecord record = mapper.findContextMessageBySequence(
                threadId, messageSequence);
        return record == null ? null : new ConversationMessage(
                record.getMessageSeq(), record.getRole(), record.getContent());
    }

    public int countMessages(String threadId) {
        return mapper.countMessages(threadId);
    }

    /**
     * 查找指定线程最近一条带有 plan 快照的消息中的 plan_json，
     * 用于线程重启后恢复计划状态。
     *
     * @return plan JSON 字符串，找不到则返回 null
     */
    public String findLatestPlanJson(String threadId) {
        if (threadId == null || threadId.isBlank()) return null;
        List<AiMessageRecord> recent = mapper.recentContextMessages(threadId, 50);
        if (recent == null) return null;
        for (AiMessageRecord record : recent) {
            String planJson = record.getPlanJson();
            if (planJson != null && !planJson.isBlank() && !"null".equals(planJson)) {
                return planJson;
            }
        }
        return null;
    }

    private static void requireFencedWrite(int updated, String target, String id) {
        if (updated != 1) {
            throw new IllegalStateException(
                    "执行租约已失效，拒绝写入过期" + target + ": " + id);
        }
    }

    private static void applyConfig(AiThreadRecord row, AiModelConfig config) {
        if (config == null) {
            return;
        }
        row.setConfigId(config.getId());
        row.setConfigName(config.getName());
        row.setConfigProtocol(DynamicModelProvider.resolveProtocol(config));
        row.setConfigModel(config.getModel());
        row.setConfigBaseUrl(config.getBaseUrl());
        row.setConfigCompletionsPath(config.getCompletionsPath());
        row.setConfigMaxOutputTokens(config.getMaxOutputTokens());
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String emptyToNull(String value) {
        return isBlank(value) ? null : value;
    }

    public record PersistedTurn(String turnId, String runId, String threadId,
                                String userMessageId, String assistantMessageId,
                                long startedAt, String leaseToken) {
    }

    public record ConversationMessage(Long sequence, String role, String content) {}

    public record ConversationCheckpoint(String summary,
                                         long boundarySequence,
                                         String boundaryHash,
                                         int version) {
    }
}
