package org.leo.web.service;

import org.leo.ai.runtime.AiTurnCommand;
import org.leo.ai.runtime.AiTurnCoordinator;
import org.leo.ai.runtime.AiTurnOutcome;
import org.leo.ai.runtime.AiTurnOrchestrator;
import org.leo.ai.runtime.AiTurnTrace;
import org.leo.ai.runtime.AiTurnTransaction;
import org.leo.ai.thread.AiConversationStoreService;
import org.leo.core.entity.AiModelConfig;
import org.leo.core.entity.AiPlan;
import org.leo.core.entity.AiPlanStatus;
import org.leo.core.session.AiThread;
import org.leo.core.session.PuppetNodeSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** 执行 Puppet 节点 AI 的一个主对话 Turn。 */
@Service
public class PuppetNodeAiTurnService {

    private static final Logger logger = LoggerFactory.getLogger(PuppetNodeAiTurnService.class);
    private final AiConversationStoreService conversationStore;
    private final PuppetNodeAiAgentRegistry agentRegistry;
    private final AiSseTurnPresenter sseTurnPresenter;
    private final AiTurnCoordinator turnCoordinator;
    private final AiTurnOrchestrator turnOrchestrator;
    private final AiExecutionClaimService executionClaimService;
    private final AiModelChannelResolver channelResolver;

    public PuppetNodeAiTurnService(AiConversationStoreService conversationStore,
                                   PuppetNodeAiAgentRegistry agentRegistry,
                                   AiSseTurnPresenter sseTurnPresenter,
                                   AiTurnCoordinator turnCoordinator,
                                   AiTurnOrchestrator turnOrchestrator,
                                   AiExecutionClaimService executionClaimService,
                                   AiModelChannelResolver channelResolver) {
        this.conversationStore = conversationStore;
        this.agentRegistry = agentRegistry;
        this.sseTurnPresenter = sseTurnPresenter;
        this.turnCoordinator = turnCoordinator;
        this.turnOrchestrator = turnOrchestrator;
        this.executionClaimService = executionClaimService;
        this.channelResolver = channelResolver;
    }

    public boolean tryClaimExecution(AiThread thread) {
        return executionClaimService.tryClaim(
                thread, thread.getThreadId(),
                () -> thread.stop("执行租约已转移"),
                error -> logger.warn("获取 Puppet AI 执行租约失败, threadId={}: {}",
                        thread.getThreadId(), error.getMessage()));
    }

    public void failDetachedExecution(AiThread thread) {
        turnCoordinator.failAndRelease(thread);
    }

    public void releaseExecutionLease(AiThread thread) {
        executionClaimService.release(thread, thread != null ? thread.getThreadId() : null);
    }

    public CompletableFuture<AiTurnOrchestrator.TerminalResult> executeChat(
            PuppetNodeSession session, AiThread thread, AiTurnExecutionRequest request) {
        String threadId = thread.getThreadId();
        AiTurnCoordinator.Execution turn = turnCoordinator.attach(thread);
        String memoryId = session.getSessionId() + ":" + threadId;
        AiTurnTrace trace = AiTurnTrace.start(
                "puppet", threadId, request.startMs());
        AiSseTurnPresenter.Session presentation = sseTurnPresenter.open(
                new AiSseTurnPresenter.Context(
                        "Puppet AI", thread, turn, null, request.audit(), request.startMs(),
                        trace,
                        () -> conversationStore.updateRuntime(
                                session.getSessionId(), thread,
                                thread.getActiveLeaseToken()),
                        () -> emitCurrentPlanAtTurnStart(thread),
                        null,
                        () -> logger.info(
                                "[Thinking] 开始接收思考内容, memoryId={}",
                                memoryId)));
        if (presentation == null) {
            return CompletableFuture.completedFuture(
                    new AiTurnOrchestrator.TerminalResult(
                            AiTurnOutcome.FAILED,
                            "SSE 启动失败"));
        }

        try {
            if (turn.isCancellationRequested()) throw new InterruptedException("已停止");
            PuppetNodeAiAgentRegistry.Runtime agentRuntime =
                    resolveAgent(session, thread, request.reasoningEffort());
            trace.checkpoint(AiTurnTrace.Checkpoint.AGENT_RESOLVED);
            presentation.emitWarning(agentRuntime.failoverMessage());
            AiConversationStoreService.PersistedTurn persistedTurn =
                    conversationStore.beginTurn(
                    request.turnId(), request.userItemId(), request.assistantItemId(), threadId,
                    agentRuntime.effectiveConfigId(), request.messageForAgent(),
                    request.userMessage(), request.attachments(), request.startMs(), agentRuntime.runtimeJson(),
                    trace, thread.getActiveLeaseToken());
            thread.bindActiveItemId(persistedTurn.assistantMessageId());
            thread.bindActiveRunId(persistedTurn.runId());
            return turnOrchestrator.execute(
                    new AiTurnOrchestrator.Request(
                            new AiTurnCommand(
                                    threadId, memoryId, turn,
                                    () -> agentRuntime.agent().chat(
                                            memoryId, request.messageForAgent()),
                                    () -> agentRuntime.agent().chat(
                                            memoryId, AiTurnCommand.RECOVERY_MESSAGE)),
                            new AiTurnTransaction.Context(
                                    persistedTurn, agentRuntime.effectiveConfigId(),
                                    agentRuntime.agent(), memoryId, request.audit(), request.startMs(),
                                    trace),
                            presentation.eventLog(),
                            thread::getCurrentPlan,
                            thread.getRuntimeStats()),
                    presentation);
        } catch (Throwable error) {
            presentation.finishPreparationFailure(error);
            return CompletableFuture.completedFuture(
                    new AiTurnOrchestrator.TerminalResult(
                            turn.isCancellation(error)
                                    ? AiTurnOutcome.CANCELLED
                                    : AiTurnOutcome.FAILED,
                            error.getMessage()));
        }
    }

    private PuppetNodeAiAgentRegistry.Runtime resolveAgent(
            PuppetNodeSession session, AiThread thread, String reasoningEffort) {
        AiModelConfig requested = channelResolver.require(
                thread != null ? thread.getAiConfigId() : null);
        if (thread != null && thread.getAiConfigId() == null) {
            thread.setAiConfigId(requested.getId());
        }
        return agentRegistry.resolve(session, thread, requested, reasoningEffort);
    }

    private void emitCurrentPlanAtTurnStart(AiThread thread) {
        AiPlan plan = thread.getCurrentPlan();
        if (plan == null || plan.getStatus() != AiPlanStatus.IN_PROGRESS) return;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("kind", "plan");
        payload.put("planId", plan.getPlanId());
        payload.put("title", plan.getTitle());
        payload.put("goal", plan.getGoal());
        payload.put("status", plan.getStatus().name());
        payload.put("steps", plan.getSteps());
        if (plan.getFinalSummary() != null) {
            payload.put("finalSummary", plan.getFinalSummary());
        }
        thread.offerSseEvent("node", payload);
    }
}
