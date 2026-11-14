package org.leo.web.application.ai;

import org.leo.ai.runtime.AiTurnCommand;
import org.leo.ai.runtime.AiTurnCoordinator;
import org.leo.ai.runtime.AiTurnOrchestrator;
import org.leo.ai.runtime.AiTurnOutcome;
import org.leo.ai.runtime.AiTurnTrace;
import org.leo.ai.runtime.AiTurnTransaction;
import org.leo.ai.service.AiPlanCoordinator;
import org.leo.ai.thread.AiConversationStoreService;
import org.leo.core.entity.AiModelConfig;
import org.leo.core.entity.AiPlan;
import org.leo.core.entity.AiPlanStatus;
import org.leo.core.session.AgentNodeSession;
import org.leo.core.session.AiThread;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;

/** 执行节点 AI 的一个主对话 Turn。 */
@Service
public class NodeAiTurnService {

    private static final Logger logger = LoggerFactory.getLogger(NodeAiTurnService.class);
    private final AiConversationStoreService conversationStore;
    private final NodeAiAgentRegistry agentRegistry;
    private final AiTurnPresenter turnPresenter;
    private final AiTurnCoordinator turnCoordinator;
    private final AiTurnOrchestrator turnOrchestrator;
    private final AiModelChannelResolver channelResolver;

    public NodeAiTurnService(AiConversationStoreService conversationStore,
                             NodeAiAgentRegistry agentRegistry,
                             AiTurnPresenter turnPresenter,
                             AiTurnCoordinator turnCoordinator,
                             AiTurnOrchestrator turnOrchestrator,
                             AiModelChannelResolver channelResolver) {
        this.conversationStore = conversationStore;
        this.agentRegistry = agentRegistry;
        this.turnPresenter = turnPresenter;
        this.turnCoordinator = turnCoordinator;
        this.turnOrchestrator = turnOrchestrator;
        this.channelResolver = channelResolver;
    }

    public CompletableFuture<AiTurnOrchestrator.TerminalResult> executeChat(
            AgentNodeSession session, AiThread thread, AiTurnExecutionRequest request) {
        String threadId = thread.getThreadId();
        AiTurnCoordinator.Execution turn = turnCoordinator.attach(thread);
        String memoryId = session.getSessionId() + ":" + threadId;
        AiTurnTrace trace = AiTurnTrace.start(
                "agent", threadId, request.startMs());
        AiTurnPresenter.Session presentation = turnPresenter.open(
                new AiTurnPresenter.Context(
                        "Node AI", thread, turn, request.audit(), request.startMs(),
                        trace,
                        () -> conversationStore.updateRuntime(
                                session.getSessionId(), thread,
                                thread.getActiveLeaseToken()),
                        () -> emitCurrentPlanAtTurnStart(thread),
                        null,
                        () -> logger.info(
                                "[Thinking] 开始接收思考内容, memoryId={}",
                                memoryId)));
        try {
            if (turn.isCancellationRequested()) throw new InterruptedException("已停止");
            NodeAiAgentRegistry.AiRuntime aiRuntime =
                    resolveAgent(session, thread, request.reasoningEffort());
            trace.checkpoint(AiTurnTrace.Checkpoint.AGENT_RESOLVED);
            presentation.emitWarning(aiRuntime.failoverMessage());
            AiConversationStoreService.PersistedTurn persistedTurn =
                    conversationStore.beginTurn(
                    request.turnId(), request.userItemId(), request.assistantItemId(), threadId,
                    aiRuntime.effectiveConfigId(), request.messageForAgent(),
                    request.userMessage(), request.attachments(), request.startMs(), aiRuntime.runtimeJson(),
                    trace, thread.getActiveLeaseToken());
            thread.bindActiveItemId(persistedTurn.assistantMessageId());
            thread.bindActiveRunId(persistedTurn.runId());
            return turnOrchestrator.execute(
                    new AiTurnOrchestrator.Request(
                            new AiTurnCommand(
                                    threadId, memoryId, turn,
                                    () -> aiRuntime.aiAgent().chat(
                                            memoryId, request.messageForAgent()),
                                    () -> aiRuntime.aiAgent().chat(
                                            memoryId, AiTurnCommand.RECOVERY_MESSAGE)),
                            new AiTurnTransaction.Context(
                                    persistedTurn, aiRuntime.effectiveConfigId(),
                                    aiRuntime.aiAgent(), memoryId, request.audit(), request.startMs(),
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

    private NodeAiAgentRegistry.AiRuntime resolveAgent(
            AgentNodeSession session, AiThread thread, String reasoningEffort) {
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
        AiPlanCoordinator.emitPlanSnapshot(thread, plan, true);
    }
}
