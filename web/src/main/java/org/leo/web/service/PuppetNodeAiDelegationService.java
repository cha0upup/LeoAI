package org.leo.web.service;

import org.leo.ai.runtime.AiTurnCommand;
import org.leo.ai.runtime.AiTurnCoordinator;
import org.leo.ai.runtime.AiTurnOrchestrator;
import org.leo.ai.runtime.AiTurnTrace;
import org.leo.ai.runtime.AiTurnTransaction;
import org.leo.ai.thread.AiConversationStoreService;
import org.leo.core.entity.AiChatAuditEntry;
import org.leo.core.entity.AiModelConfig;
import org.leo.core.entity.AiSseEvent;
import org.leo.core.ai.AiRuntimeState;
import org.leo.core.session.AiThread;
import org.leo.core.session.PuppetNodeSession;
import org.leo.web.exception.ApiException;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;

/** 执行平台 AI 派发给 Puppet AI 的同步隔离 Turn。 */
@Service
public class PuppetNodeAiDelegationService {

    private final AiConversationStoreService conversationStore;
    private final PuppetNodeAiAgentRegistry agentRegistry;
    private final PuppetNodeAiDelegationPresenter delegationPresenter;
    private final AiTurnCoordinator turnCoordinator;
    private final AiTurnOrchestrator turnOrchestrator;
    private final AiExecutionLeaseService executionLeaseService;
    private final AiModelChannelResolver channelResolver;

    public PuppetNodeAiDelegationService(AiConversationStoreService conversationStore,
                                         PuppetNodeAiAgentRegistry agentRegistry,
                                         PuppetNodeAiDelegationPresenter delegationPresenter,
                                         AiTurnCoordinator turnCoordinator,
                                         AiTurnOrchestrator turnOrchestrator,
                                         AiExecutionLeaseService executionLeaseService,
                                         AiModelChannelResolver channelResolver) {
        this.conversationStore = conversationStore;
        this.agentRegistry = agentRegistry;
        this.delegationPresenter = delegationPresenter;
        this.turnCoordinator = turnCoordinator;
        this.turnOrchestrator = turnOrchestrator;
        this.executionLeaseService = executionLeaseService;
        this.channelResolver = channelResolver;
    }

    public Map<String, Object> execute(PuppetNodeSession session,
                                       AiThread thread,
                                       String userMessage,
                                       String messageForAgent,
                                       AiChatAuditEntry audit,
                                       String subagentInvocationId,
                                       Consumer<AiSseEvent> eventSink,
                                       AiRuntimeState parent) {
        if (session == null || thread == null || parent == null) {
            throw ApiException.badRequest("Puppet AI 会话、线程或父任务不存在");
        }
        if (!turnCoordinator.tryClaim(thread)) {
            throw ApiException.badRequest("目标 Puppet AI 线程正在执行中");
        }
        String leaseToken;
        try {
            leaseToken = executionLeaseService.tryAcquireToken(
                    thread.getThreadId(), () -> thread.stop("执行租约已转移"));
        } catch (RuntimeException error) {
            turnCoordinator.releaseClaim(thread);
            throw error;
        }
        if (leaseToken == null) {
            turnCoordinator.releaseClaim(thread);
            throw ApiException.badRequest("目标 Puppet AI 线程正在其他实例执行中");
        }
        thread.bindActiveLeaseToken(leaseToken);
        AiTurnCoordinator.Execution turn = turnCoordinator.attach(thread);
        long startMs = System.currentTimeMillis();
        String threadId = thread.getThreadId();
        String memoryId = session.getSessionId() + ":" + threadId;
        AiTurnTrace trace = AiTurnTrace.start(
                "delegation", threadId, startMs);
        PuppetNodeAiDelegationPresenter.Session presentation = null;
        CompletableFuture<AiTurnOrchestrator.TerminalResult> completion = null;
        boolean interrupted = false;
        try (var cancellation = parent.onStop(thread::stop)) {
            presentation = delegationPresenter.open(
                        new PuppetNodeAiDelegationPresenter.Context(
                                session, thread, audit, startMs,
                                trace,
                                subagentInvocationId, eventSink));
            thread.touchLastActiveAt();
            requireActive(turn);
            PuppetNodeAiAgentRegistry.Runtime agentRuntime = resolveAgent(session, thread);
            trace.checkpoint(AiTurnTrace.Checkpoint.AGENT_RESOLVED);
            presentation.emitWarning(agentRuntime.failoverMessage());
            requireActive(turn);
            AiConversationStoreService.PersistedTurn persistedTurn =
                    conversationStore.beginTurn(
                    null, null, null, threadId,
                    agentRuntime.effectiveConfigId(), messageForAgent,
                    userMessage, null, startMs, agentRuntime.runtimeJson(),
                    trace, thread.getActiveLeaseToken());
            thread.bindActiveTurnId(persistedTurn.turnId());
            thread.bindActiveItemId(persistedTurn.assistantMessageId());
            thread.bindActiveRunId(persistedTurn.runId());

            completion = turnOrchestrator.execute(
                    new AiTurnOrchestrator.Request(
                            new AiTurnCommand(
                                    threadId, memoryId, turn,
                                    () -> agentRuntime.agent().chat(
                                            memoryId, messageForAgent),
                                    () -> agentRuntime.agent().chat(
                                            memoryId, AiTurnCommand.RECOVERY_MESSAGE)),
                            new AiTurnTransaction.Context(
                                    persistedTurn, agentRuntime.effectiveConfigId(),
                                    agentRuntime.agent(), memoryId, audit, startMs,
                                    trace),
                            presentation.eventLog(),
                            thread::getCurrentPlan,
                            null),
                    presentation);
            completion.get();
            return presentation.await();
        } catch (Throwable error) {
            interrupted = error instanceof InterruptedException;
            if (turn.isCancellation(error)) {
                if (!turn.isFinished()) thread.stop(turn.cancellationReason());
                turn.cancel(turn.cancellationReason());
                awaitCancellationCleanup(completion, error);
            }
            if (presentation != null) {
                throw presentation.executionFailure(turn, error);
            }
            turnCoordinator.failAndRelease(thread);
            throw error instanceof RuntimeException runtime
                    ? runtime : new IllegalStateException(error);
        } finally {
            try {
                executionLeaseService.release(threadId);
            } finally {
                thread.bindActiveLeaseToken(null);
                if (interrupted) Thread.currentThread().interrupt();
            }
        }
    }

    /** 租约覆盖全部终态写入；调用方中断不能提前释放仍在收口的任务。 */
    private void awaitCancellationCleanup(
            CompletableFuture<AiTurnOrchestrator.TerminalResult> completion, Throwable error) {
        if (completion == null) return;
        try {
            completion.join();
        } catch (CompletionException failure) {
            if (failure != error) error.addSuppressed(failure);
        }
    }

    private void requireActive(AiTurnCoordinator.Execution turn) throws InterruptedException {
        if (turn.isCancellationRequested() || Thread.currentThread().isInterrupted()) {
            throw new InterruptedException(turn.cancellationReason());
        }
    }

    private PuppetNodeAiAgentRegistry.Runtime resolveAgent(
            PuppetNodeSession session, AiThread thread) {
        AiModelConfig requested = channelResolver.require(thread.getAiConfigId());
        if (thread.getAiConfigId() == null) thread.setAiConfigId(requested.getId());
        return agentRegistry.resolve(session, thread, requested, null);
    }

}
