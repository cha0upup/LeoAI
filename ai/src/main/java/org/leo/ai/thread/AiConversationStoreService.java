package org.leo.ai.thread;

import org.leo.ai.channel.DynamicModelProvider;
import org.leo.ai.runtime.AiTurnTrace;
import org.leo.core.entity.AiModelConfig;
import org.leo.core.entity.AiRunRecord;
import org.leo.core.entity.AiThreadRecord;
import org.leo.core.entity.AiTurnRecord;
import org.leo.core.ai.AiRunStatus;
import org.leo.core.entity.AiUserInputRequest;
import org.leo.core.session.AiThread;
import org.leo.core.ai.AiEventStreamRuntime;
import org.leo.core.entity.AiSseEvent;
import org.leo.dao.mapper.AiConversationMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AiConversationStoreService {

    public static final String SCOPE_AGENT = "agent";
    public static final String SCOPE_PLATFORM = "platform";
    public static final String MESSAGE_PENDING = "pending";
    public static final String MESSAGE_COMMITTED = "committed";
    public static final String MESSAGE_DISCARDED = "discarded";
    public static final String ERROR_CANCELLED = "cancelled";
    public static final String ERROR_PERSISTENCE = "persistence";

    private final AiConversationMapper mapper;
    private final AiEventJournalRepository eventJournal;
    private final AiTurnTerminalRepository turnTerminal;
    private final AiProtocolTurnRepository protocolTurn;
    private final AiUserInputRepository userInput;
    private final AiMessageRepository messages;

    public AiConversationStoreService(AiConversationMapper mapper,
                                      AiEventJournalRepository eventJournal,
                                      AiTurnTerminalRepository turnTerminal,
                                      AiProtocolTurnRepository protocolTurn,
                                      AiUserInputRepository userInput,
                                      AiMessageRepository messages) {
        this.mapper = mapper;
        this.eventJournal = eventJournal;
        this.turnTerminal = turnTerminal;
        this.protocolTurn = protocolTurn;
        this.userInput = userInput;
        this.messages = messages;
    }

    public List<AiThreadRecord> listAgentThreads(String userId, String agentId, String hostId) {
        if (isBlank(userId) || isBlank(agentId) || isBlank(hostId)) {
            return List.of();
        }
        return mapper.listThreads(SCOPE_AGENT, userId, agentId, hostId);
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

    public void createAgentThread(String userId, String agentId, String hostId, String sessionId,
                                   AiThread thread, AiModelConfig config) {
        if (isBlank(userId) || isBlank(agentId) || isBlank(hostId)) {
            throw new IllegalArgumentException("AI 线程缺少用户、Agent 或 HostId");
        }
        AiThreadRecord row = new AiThreadRecord();
        row.setThreadId(thread.getThreadId());
        row.setScope(SCOPE_AGENT);
        row.setUserId(userId);
        row.setAgentId(agentId);
        row.setHostId(hostId);
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

    public void updateRuntime(String sessionId, AiThread thread, String leaseToken) {
        if (thread == null) return;
        updateRuntime(sessionId, thread.getThreadId(), thread.getLastActiveAt(),
                thread.getRunStatus(), leaseToken);
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

    public void deleteThread(String threadId) {
        mapper.deleteThread(threadId);
    }

    // ── 持久化 Turn 控制协议 ────────────────────────────────────────────────

    /**
     * 原子接收用户命令。Turn 和两条可展示消息先于任何运行时对象持久化，
     * 因此刷新页面后 queued/running/cancelling 都可仅从数据库恢复。
     */
    @Transactional
    public boolean reserveProtocolTurn(AiTurnRecord turn,
                                       String userContent,
                                       Object attachments,
                                       String answerToQuestionId) {
        if (turn == null || mapper.insertProtocolTurn(turn) != 1) return false;
        messages.appendPending(
                turn.getUserItemId(), turn.getThreadId(), turn.getTurnId(),
                null, "user", userContent, attachments);
        messages.appendPending(
                turn.getAssistantItemId(), turn.getThreadId(), turn.getTurnId(),
                null, "assistant", "", null);
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

    @Transactional
    public AiTurnRecord requestProtocolTurnInterrupt(
            String threadId, String turnId) {
        mapper.requestProtocolTurnInterrupt(threadId, turnId);
        AiTurnRecord current = protocolTurn.find(turnId);
        if (current != null && "queued".equals(current.getDispatchStatus())) {
            AiTurnRecord completed = protocolTurn.complete(
                    turnId, "interrupted", null, System.currentTimeMillis(), null);
            mapper.updateTurnMessageStatus(
                    threadId, turnId, MESSAGE_DISCARDED);
            Map<String, Object> turn = new LinkedHashMap<>();
            turn.put("id", turnId);
            turn.put("threadId", threadId);
            turn.put("status", "interrupted");
            turn.put("interruptRequested", true);
            eventJournal.append(threadId, new AiSseEvent(
                    eventJournal.lastSequence(threadId) + 1L,
                    System.currentTimeMillis(),
                    "turn/completed",
                    Map.of("turn", turn),
                    null,
                    turnId,
                    current.getAssistantItemId(),
                    null), null);
            return completed;
        }
        return current;
    }

    /** 启动前失败也产生可重放终态，避免没有 Run 的订阅永久等待。 */
    @Transactional
    public AiTurnRecord failProtocolTurnBeforeRun(String turnId, String message, long completedAt) {
        AiTurnRecord current = protocolTurn.find(turnId);
        if (current == null) return null;
        if (mapper.failProtocolTurnBeforeRun(turnId, message, completedAt) == 1) {
            String threadId = current.getThreadId();
            mapper.updateTurnMessageStatus(threadId, turnId, MESSAGE_DISCARDED);
            mapper.failOrphanedThread(threadId, completedAt);
            Map<String, Object> turn = new LinkedHashMap<>();
            turn.put("id", turnId);
            turn.put("threadId", threadId);
            turn.put("status", "failed");
            turn.put("error", Map.of("message", message != null ? message : "任务启动失败"));
            eventJournal.append(threadId, new AiSseEvent(
                    eventJournal.lastSequence(threadId) + 1L, completedAt,
                    "turn/completed", Map.of("turn", turn), null,
                    turnId, current.getAssistantItemId(), null), null);
        }
        return protocolTurn.find(turnId);
    }

    // ── 可重放事件日志 ───────────────────────────────────────────────────────

    /**
     * 将运行时绑定到数据库事件日志。绑定时先恢复线程级最新序号，保证进程重启后
     * 新事件仍沿用同一个单调游标。
     */
    public void attachEventJournal(String threadId, AiEventStreamRuntime runtime) {
        eventJournal.attach(threadId, runtime);
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
            userMessageId = messages.appendPending(
                    requestedUserItemId,
                    threadId, turnId, runId, "user", userContent, attachments);
            assistantMessageId = messages.appendPending(
                    requestedAssistantItemId,
                    threadId, turnId, runId, "assistant", "", null);
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
     * 中断/失败时保存已经产生的 assistant Item，状态仍为 discarded。
     * 可见正文与内部 recoveryContext 分别保存；存在实际执行进度时，
     * 模型上下文会连同原始 user Item 一起载入，供“继续”任务衔接。
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
                            Object planSnapshot,
                            String recoveryContext) {
        turnTerminal.discard(turn, runStatus, errorCategory, errorMessage,
                rawErrorMessage, toolCallCount, partialOutput, partialNodes,
                planSnapshot, recoveryContext);
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

}
