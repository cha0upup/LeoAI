package org.leo.web.service;

import org.leo.ai.audit.AiAuditLogStore;
import org.leo.ai.platform.PlatformAiState;
import org.leo.ai.platform.PlatformAiStateStore;
import org.leo.ai.service.AiUserInputService;
import org.leo.ai.thread.AiConversationStoreService;
import org.leo.core.entity.AiChatAuditEntry;
import org.leo.core.entity.AiExecutionPolicy;
import org.leo.core.entity.AiThreadRecord;
import org.leo.core.session.AiThread;
import org.leo.core.ai.AiRunStatus;
import org.leo.core.session.PuppetNodeSession;
import org.leo.core.session.PuppetNodeSessionContainer;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/** 将持久化 Turn 命令适配到 Platform/Puppet 运行时。 */
@Service
public class AiTurnApplicationService {

    private final AiTurnProtocolService protocol;
    private final AiConversationStoreService store;
    private final PlatformAiThreadService platformThreads;
    private final PlatformAiTurnService platformTurns;
    private final PuppetNodeAiThreadService puppetThreads;
    private final PuppetNodeAiTurnService puppetTurns;
    private final AiAuditLogStore auditLogStore;
    private final AiUserInputService userInputService;

    public AiTurnApplicationService(
            AiTurnProtocolService protocol,
            AiConversationStoreService store,
            PlatformAiThreadService platformThreads,
            PlatformAiTurnService platformTurns,
            PuppetNodeAiThreadService puppetThreads,
            PuppetNodeAiTurnService puppetTurns,
            AiAuditLogStore auditLogStore,
            AiUserInputService userInputService) {
        this.protocol = protocol;
        this.store = store;
        this.platformThreads = platformThreads;
        this.platformTurns = platformTurns;
        this.puppetThreads = puppetThreads;
        this.puppetTurns = puppetTurns;
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
            protocol.failStart(turn.id(), error.getMessage());
            return CompletableFuture.completedFuture(true);
        }
        if (!Objects.equals(turn.commandScope(), command.getScope())) {
            protocol.failStart(turn.id(), "Turn 命令作用域不一致");
            return CompletableFuture.completedFuture(true);
        }
        if (AiTurnCommandPayload.SCOPE_PLATFORM.equals(turn.commandScope())) {
            return executePlatform(turn, command);
        }
        if (AiTurnCommandPayload.SCOPE_PUPPET.equals(turn.commandScope())) {
            return executePuppet(turn, command);
        }
        protocol.failStart(turn.id(), "未知 Turn 命令作用域");
        return CompletableFuture.completedFuture(true);
    }

    private CompletableFuture<Boolean> executePlatform(
            AiTurnProtocolService.TurnSnapshot turn,
            AiTurnCommandPayload command) {
        AiThreadRecord persisted = store.findThread(turn.threadId());
        if (persisted == null) {
            protocol.failStart(turn.id(), "平台 AI 线程不存在");
            return CompletableFuture.completedFuture(true);
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
            return platformTurns.executeChat(
                            state, command.getSessionId(), command.getUserMessage(),
                            messageForAgent, audit, null, startMs,
                            command.getReasoningEffort(), command.getAttachments(),
                            turn.id(), turn.userItemId(), turn.assistantItemId())
                    .handle((terminal, error) -> {
                        if (error != null) {
                            failPlatform(
                                    turn, state, rootMessage(error), leaseToken);
                        } else {
                            completePlatform(
                                    turn, state,
                                    state.isWaitingForUserInput()
                                            ? AiRunStatus.WAITING_FOR_USER
                                            : terminal.runtimeStatus(),
                                    terminal.errorMessage(), leaseToken);
                        }
                        return true;
                    });
        } catch (Throwable error) {
            failPlatform(
                    turn, state, rootMessage(error), executionLeaseToken);
            return CompletableFuture.completedFuture(true);
        }
    }

    private CompletableFuture<Boolean> executePuppet(
            AiTurnProtocolService.TurnSnapshot turn,
            AiTurnCommandPayload command) {
        PuppetNodeSession session =
                PuppetNodeSessionContainer.getSession(command.getSessionId());
        if (session == null) {
            protocol.requeue(turn.id());
            return CompletableFuture.completedFuture(false);
        }
        PuppetNodeAiThreadService.ThreadResolution resolution =
                puppetThreads.ensureThreadReady(session, turn.threadId(), null);
        AiThread thread = resolution.thread();
        if (thread == null || resolution.errorMessage() != null) {
            protocol.failStart(turn.id(), resolution.errorMessage() != null
                    ? resolution.errorMessage() : "Puppet AI 线程不存在");
            return CompletableFuture.completedFuture(true);
        }

        String executionLeaseToken = null;
        try {
            bind(thread, turn);
            if (command.getConfigId() != null) {
                puppetThreads.switchChannel(
                        session, turn.threadId(), command.getConfigId());
            }
            AiExecutionPolicy policy = command.executionPolicy();
            thread.setExecutionPolicy(policy);
            if (!puppetTurns.tryClaimExecution(thread)) {
                protocol.requeue(turn.id());
                return CompletableFuture.completedFuture(false);
            }
            final String leaseToken = thread.getActiveLeaseToken();
            executionLeaseToken = leaseToken;
            long startMs = System.currentTimeMillis();
            AiChatAuditEntry audit = AiChatAuditEntry.puppet(
                    session.getSessionId(), policy.getUserId(),
                    policy.getUserName(), policy.getPrivilege(),
                    command.getUserMessage());
            auditLogStore.append(audit);
            thread.offerSseEvent("turn/started", Map.of("turn", turn.toMap()));
            String messageForAgent = userInputService.resumePrompt(
                    turn.threadId(), command.getAnswerToQuestionId(),
                    command.getGuardedMessage());
            return puppetTurns.executeChat(
                            session, thread, turn.threadId(),
                            messageForAgent, audit, null, startMs,
                            command.getReasoningEffort(), command.getUserMessage(),
                            command.getAttachments(), turn.id(),
                            turn.userItemId(), turn.assistantItemId())
                    .handle((terminal, error) -> {
                        if (error != null) {
                            failPuppet(
                                    turn, thread, rootMessage(error), leaseToken);
                        } else {
                            completePuppet(
                                    turn, thread,
                                    thread.isWaitingForUserInput()
                                            ? AiRunStatus.WAITING_FOR_USER
                                            : terminal.runtimeStatus(),
                                    terminal.errorMessage(), leaseToken);
                        }
                        return true;
                    });
        } catch (Throwable error) {
            failPuppet(
                    turn, thread, rootMessage(error), executionLeaseToken);
            return CompletableFuture.completedFuture(true);
        }
    }

    private void completePlatform(
            AiTurnProtocolService.TurnSnapshot turn,
            PlatformAiState state,
            String runtimeStatus,
            String errorMessage,
            String leaseToken) {
        try {
            AiTurnProtocolService.TurnSnapshot completed =
                    protocol.completeFromRuntime(
                            turn.id(), runtimeStatus, errorMessage,
                            leaseToken);
            state.offerSseEvent(
                    "turn/completed", Map.of("turn", completed.toMap()));
        } finally {
            platformTurns.releaseExecutionLease(state);
        }
    }

    private void completePuppet(
            AiTurnProtocolService.TurnSnapshot turn,
            AiThread thread,
            String runtimeStatus,
            String errorMessage,
            String leaseToken) {
        try {
            AiTurnProtocolService.TurnSnapshot completed =
                    protocol.completeFromRuntime(
                            turn.id(), runtimeStatus, errorMessage,
                            leaseToken);
            thread.offerSseEvent(
                    "turn/completed", Map.of("turn", completed.toMap()));
        } finally {
            puppetTurns.releaseExecutionLease(thread);
        }
    }

    private void failPlatform(
            AiTurnProtocolService.TurnSnapshot turn,
            PlatformAiState state,
            String message,
            String leaseToken) {
        boolean owns = turn.id().equals(state.getActiveTurnId());
        if (owns) platformTurns.failDetachedExecution(state);
        try {
            AiTurnProtocolService.TurnSnapshot failed =
                    protocol.failStart(turn.id(), message, leaseToken);
            if (owns) {
                state.offerSseEvent(
                        "turn/completed", Map.of("turn", failed.toMap()));
            }
        } finally {
            if (owns) platformTurns.releaseExecutionLease(state);
        }
    }

    private void failPuppet(
            AiTurnProtocolService.TurnSnapshot turn,
            AiThread thread,
            String message,
            String leaseToken) {
        boolean owns = turn.id().equals(thread.getActiveTurnId());
        if (owns) puppetTurns.failDetachedExecution(thread);
        try {
            AiTurnProtocolService.TurnSnapshot failed =
                    protocol.failStart(turn.id(), message, leaseToken);
            if (owns) {
                thread.offerSseEvent(
                        "turn/completed", Map.of("turn", failed.toMap()));
            }
        } finally {
            if (owns) puppetTurns.releaseExecutionLease(thread);
        }
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        String message = current.getMessage();
        return message != null && !message.isBlank()
                ? message : current.getClass().getSimpleName();
    }

    private void bind(PlatformAiState state,
                      AiTurnProtocolService.TurnSnapshot turn) {
        state.bindActiveTurnId(turn.id());
        state.bindActiveItemId(null);
        state.bindActiveRunId(null);
    }

    private void bind(AiThread thread,
                      AiTurnProtocolService.TurnSnapshot turn) {
        thread.bindActiveTurnId(turn.id());
        thread.bindActiveItemId(null);
        thread.bindActiveRunId(null);
    }
}
