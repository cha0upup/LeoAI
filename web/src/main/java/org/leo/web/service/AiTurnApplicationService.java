package org.leo.web.service;

import org.leo.ai.audit.AiAuditLogStore;
import org.leo.ai.platform.PlatformAiState;
import org.leo.ai.platform.PlatformAiStateStore;
import org.leo.ai.runtime.AiTurnOrchestrator;
import org.leo.ai.service.AiUserInputService;
import org.leo.ai.thread.AiConversationStoreService;
import org.leo.core.entity.AiChatAuditEntry;
import org.leo.core.entity.AiExecutionPolicy;
import org.leo.core.entity.AiThreadRecord;
import org.leo.core.session.AiThread;
import org.leo.core.ai.AiRunStatus;
import org.leo.core.ai.AiRuntimeState;
import org.leo.core.session.AgentNodeSession;
import org.leo.core.session.AgentNodeSessionContainer;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/** 将持久化 Turn 命令适配到 Platform/Agent 运行时。 */
@Service
public class AiTurnApplicationService {

    private final AiTurnProtocolService protocol;
    private final AiConversationStoreService store;
    private final PlatformAiThreadService platformThreads;
    private final PlatformAiTurnService platformTurns;
    private final NodeAiThreadService agentThreads;
    private final NodeAiTurnService agentTurns;
    private final AiAuditLogStore auditLogStore;
    private final AiUserInputService userInputService;

    public AiTurnApplicationService(
            AiTurnProtocolService protocol,
            AiConversationStoreService store,
            PlatformAiThreadService platformThreads,
            PlatformAiTurnService platformTurns,
            NodeAiThreadService agentThreads,
            NodeAiTurnService agentTurns,
            AiAuditLogStore auditLogStore,
            AiUserInputService userInputService) {
        this.protocol = protocol;
        this.store = store;
        this.platformThreads = platformThreads;
        this.platformTurns = platformTurns;
        this.agentThreads = agentThreads;
        this.agentTurns = agentTurns;
        this.auditLogStore = auditLogStore;
        this.userInputService = userInputService;
    }

    /**
     * @return true 表示该 Turn 已终态，可继续领取下一条；false 表示已重新排队。
     */
    public CompletableFuture<Boolean> execute(
            AiTurnProtocolService.TurnSnapshot turn) {
        AiTurnCommandPayload command;
        try {
            command = AiTurnCommandPayload.fromJson(turn.commandJson());
        } catch (RuntimeException error) {
            return failBeforeExecution(turn, error.getMessage());
        }
        if (!Objects.equals(turn.commandScope(), command.getScope())) {
            return failBeforeExecution(turn, "Turn 命令作用域不一致");
        }
        if (AiTurnCommandPayload.SCOPE_PLATFORM.equals(turn.commandScope())) {
            return executePlatform(turn, command);
        }
        if (AiTurnCommandPayload.SCOPE_AGENT.equals(turn.commandScope())) {
            return executeAgent(turn, command);
        }
        return failBeforeExecution(turn, "未知 Turn 命令作用域");
    }

    private CompletableFuture<Boolean> executePlatform(
            AiTurnProtocolService.TurnSnapshot turn,
            AiTurnCommandPayload command) {
        AiThreadRecord persisted = store.findThread(turn.threadId());
        if (persisted == null) {
            return failBeforeExecution(turn, "平台 AI 线程不存在");
        }
        PlatformAiState existingState = PlatformAiStateStore.get(turn.threadId());
        PlatformAiState state = existingState != null
                ? existingState : PlatformAiStateStore.create(turn.threadId());
        state.setAiConfigId(persisted.getConfigId());
        store.attachEventJournal(turn.threadId(), state);
        String executionLeaseToken = null;

        try {
            bind(state, turn);
            if (command.getConfigId() != null) {
                platformThreads.switchChannel(state, command.getConfigId());
            }
            AiExecutionPolicy policy = command.executionPolicy();
            state.setExecutionPolicy(policy);
            if (!platformTurns.tryClaimExecution(state)) {
                protocol.requeue(turn.id());
                return CompletableFuture.completedFuture(false);
            }
            final String leaseToken = state.getActiveLeaseToken();
            executionLeaseToken = leaseToken;
            long startMs = System.currentTimeMillis();
            AiChatAuditEntry audit =
                    platformTurns.appendChatAudit(policy, command.getUserMessage());
            state.offerSseEvent("turn/started", Map.of("turn", turn.toMap()));
            String messageForAgent = userInputService.resumePrompt(
                    turn.threadId(), command.getAnswerToQuestionId(),
                    command.getGuardedMessage());
            return completeAfterExecution(turn, state, leaseToken,
                    platformTurns.executeChat(state,
                            AiTurnExecutionRequest.from(turn, command, messageForAgent, audit, startMs)),
                    () -> platformTurns.failDetachedExecution(state),
                    () -> platformTurns.releaseExecutionLease(state));
        } catch (Throwable error) {
            failTurn(turn, state, rootMessage(error), executionLeaseToken,
                    () -> platformTurns.failDetachedExecution(state),
                    () -> platformTurns.releaseExecutionLease(state));
            return CompletableFuture.completedFuture(true);
        }
    }

    private CompletableFuture<Boolean> executeAgent(
            AiTurnProtocolService.TurnSnapshot turn,
            AiTurnCommandPayload command) {
        AgentNodeSession session =
                AgentNodeSessionContainer.getSession(command.getSessionId());
        if (session == null) {
            // 临时 Session 在进程重启/销毁后不会恢复原 ID，持续重排只会留下悬挂任务。
            return failBeforeExecution(turn, "节点会话已失效，请重新连接节点后重新提交任务");
        }
        NodeAiThreadService.ThreadResolution resolution;
        try {
            resolution = agentThreads.ensureThreadReady(session, turn.threadId(), null);
        } catch (RuntimeException error) {
            return failBeforeExecution(turn, rootMessage(error));
        }
        AiThread thread = resolution.thread();
        if (thread == null || resolution.errorMessage() != null) {
            return failBeforeExecution(turn, resolution.errorMessage() != null
                    ? resolution.errorMessage() : "Node AI 线程不存在");
        }

        String executionLeaseToken = null;
        try {
            bind(thread, turn);
            if (command.getConfigId() != null) {
                agentThreads.switchChannel(
                        session, turn.threadId(), command.getConfigId());
            }
            AiExecutionPolicy policy = command.executionPolicy();
            thread.setExecutionPolicy(policy);
            if (!agentTurns.tryClaimExecution(thread)) {
                protocol.requeue(turn.id());
                return CompletableFuture.completedFuture(false);
            }
            final String leaseToken = thread.getActiveLeaseToken();
            executionLeaseToken = leaseToken;
            long startMs = System.currentTimeMillis();
            AiChatAuditEntry audit = AiChatAuditEntry.agent(
                    session.getSessionId(), policy.getUserId(),
                    policy.getUserName(), policy.getPrivilege(),
                    command.getUserMessage());
            auditLogStore.append(audit);
            thread.offerSseEvent("turn/started", Map.of("turn", turn.toMap()));
            String messageForAgent = userInputService.resumePrompt(
                    turn.threadId(), command.getAnswerToQuestionId(),
                    command.getGuardedMessage());
            return completeAfterExecution(turn, thread, leaseToken,
                    agentTurns.executeChat(session, thread,
                            AiTurnExecutionRequest.from(turn, command, messageForAgent, audit, startMs)),
                    () -> agentTurns.failDetachedExecution(thread),
                    () -> agentTurns.releaseExecutionLease(thread));
        } catch (Throwable error) {
            failTurn(turn, thread, rootMessage(error), executionLeaseToken,
                    () -> agentTurns.failDetachedExecution(thread),
                    () -> agentTurns.releaseExecutionLease(thread));
            return CompletableFuture.completedFuture(true);
        }
    }

    private CompletableFuture<Boolean> failBeforeExecution(
            AiTurnProtocolService.TurnSnapshot turn, String message) {
        protocol.failStart(turn.id(), message, null);
        return CompletableFuture.completedFuture(true);
    }

    /** 两类运行时共用终态处理，租约保持到异步执行和持久化均结束。 */
    private CompletableFuture<Boolean> completeAfterExecution(
            AiTurnProtocolService.TurnSnapshot turn, AiRuntimeState runtime, String leaseToken,
            CompletableFuture<AiTurnOrchestrator.TerminalResult> completion,
            Runnable failExecution, Runnable releaseLease) {
        return completion.handle((terminal, error) -> {
            if (error != null) {
                failTurn(turn, runtime, rootMessage(error), leaseToken, failExecution, releaseLease);
            } else {
                String status = runtime.isWaitingForUserInput()
                        ? AiRunStatus.WAITING_FOR_USER : terminal.runtimeStatus();
                completeTurn(turn, runtime, status, terminal.errorMessage(), leaseToken, releaseLease);
            }
            return true;
        });
    }

    private void completeTurn(AiTurnProtocolService.TurnSnapshot turn,
                              AiRuntimeState runtime, String runtimeStatus,
                              String errorMessage, String leaseToken, Runnable releaseLease) {
        try {
            AiTurnProtocolService.TurnSnapshot completed = protocol.completeFromRuntime(
                    turn.id(), runtimeStatus, errorMessage, leaseToken);
            runtime.offerSseEvent("turn/completed", Map.of("turn", completed.toMap()));
        } finally {
            releaseLease.run();
        }
    }

    private void failTurn(AiTurnProtocolService.TurnSnapshot turn,
                          AiRuntimeState runtime, String message, String leaseToken,
                          Runnable failExecution, Runnable releaseLease) {
        boolean owns = turn.id().equals(runtime.getActiveTurnId());
        if (owns) failExecution.run();
        try {
            AiTurnProtocolService.TurnSnapshot failed = protocol.failStart(turn.id(), message, leaseToken);
            // 无租约的启动失败已由存储层原子写入终态事件。
            if (owns && leaseToken != null) {
                runtime.offerSseEvent("turn/completed", Map.of("turn", failed.toMap()));
            }
        } finally {
            if (owns) releaseLease.run();
        }
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        String message = current.getMessage();
        return message != null && !message.isBlank()
                ? message : current.getClass().getSimpleName();
    }

    private void bind(AiRuntimeState runtime, AiTurnProtocolService.TurnSnapshot turn) {
        runtime.bindActiveTurnId(turn.id());
        runtime.bindActiveItemId(null);
        runtime.bindActiveRunId(null);
    }
}
