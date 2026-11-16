package org.leo.web.application.ai;

import org.leo.ai.platform.PlatformAiState;
import org.leo.ai.runtime.AiTurnCommand;
import org.leo.ai.runtime.AiTurnCoordinator;
import org.leo.ai.runtime.AiTurnOrchestrator;
import org.leo.ai.runtime.AiTurnOutcome;
import org.leo.ai.runtime.AiTurnTrace;
import org.leo.ai.runtime.AiTurnTransaction;
import org.leo.ai.thread.AiConversationStoreService;
import org.leo.ai.thread.PersistedTurn;
import org.leo.core.entity.AiModelConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;

/** 执行平台 AI 的一个对话 Turn。 */
@Service
public class PlatformAiTurnService {

    private static final Logger logger = LoggerFactory.getLogger(PlatformAiTurnService.class);
    private final AiConversationStoreService conversationStore;
    private final PlatformAiAgentRegistry agentRegistry;
    private final AiTurnPresenter turnPresenter;
    private final AiTurnCoordinator turnCoordinator;
    private final AiTurnOrchestrator turnOrchestrator;
    private final AiModelChannelResolver channelResolver;

    public PlatformAiTurnService(AiConversationStoreService conversationStore,
                                 PlatformAiAgentRegistry agentRegistry,
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
            PlatformAiState state, AiTurnExecutionRequest request) {
        AiTurnCoordinator.Execution turn = turnCoordinator.attach(state);
        String memoryId = state.getStateId();
        AiTurnTrace trace = AiTurnTrace.start(
                "platform", state.getStateId(), request.startMs());
        AiTurnPresenter.Session presentation = turnPresenter.open(
                new AiTurnPresenter.Context(
                        "Platform AI", state, turn, request.audit(), request.startMs(),
                        trace,
                        () -> conversationStore.updateRuntime(
                                request.sessionId(), state.getStateId(),
                                state.getLastActiveAt(), state.getRunStatus(),
                                state.getActiveLeaseToken()),
                        null,
                        state::touchLastActiveAt,
                        () -> logger.info(
                                "[Thinking] 开始接收思考内容, stateId={}",
                                state.getStateId())));
        try {
            if (turn.isCancellationRequested()) {
                throw new InterruptedException("已停止");
            }
            state.touchLastActiveAt();
            String messageForAgent = request.messageForAgent();
            PlatformAiAgentRegistry.AiRuntime aiRuntime = resolveAgent(state, request.reasoningEffort());
            trace.checkpoint(AiTurnTrace.Checkpoint.AGENT_RESOLVED);
            presentation.emitWarning(aiRuntime.failoverMessage());
            PersistedTurn persistedTurn =
                    conversationStore.beginTurn(
                    request.turnId(), request.userItemId(), request.assistantItemId(), state.getStateId(),
                    aiRuntime.effectiveConfigId(), messageForAgent,
                    request.userMessage(), request.attachments(), request.startMs(), aiRuntime.runtimeJson(),
                    trace, state.getActiveLeaseToken());
            state.bindActiveItemId(persistedTurn.assistantMessageId());
            state.bindActiveRunId(persistedTurn.runId());
            return turnOrchestrator.execute(
                    new AiTurnOrchestrator.Request(
                            new AiTurnCommand(
                                    state.getStateId(), memoryId, turn,
                                    () -> aiRuntime.aiAgent().chat(
                                            memoryId, messageForAgent),
                                    () -> aiRuntime.aiAgent().chat(
                                            memoryId, AiTurnCommand.RECOVERY_MESSAGE)),
                            new AiTurnTransaction.Context(
                                    persistedTurn, aiRuntime.effectiveConfigId(),
                                    aiRuntime.aiAgent(), memoryId, request.audit(), request.startMs(),
                                    trace),
                            presentation.eventLog(),
                            state::getCurrentPlan,
                            state.getRuntimeStats()),
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

    private PlatformAiAgentRegistry.AiRuntime resolveAgent(
            PlatformAiState state, String reasoningEffort) {
        AiModelConfig requested = channelResolver.require(state.getAiConfigId());
        if (state.getAiConfigId() == null) state.setAiConfigId(requested.getId());
        return agentRegistry.resolve(state, requested, reasoningEffort);
    }

}
