package org.leo.web.service;

import org.leo.ai.audit.AiAuditLogStore;
import org.leo.ai.platform.PlatformAiState;
import org.leo.ai.runtime.AiTurnCoordinator;
import org.leo.ai.runtime.AiTurnCommand;
import org.leo.ai.runtime.AiTurnOutcome;
import org.leo.ai.runtime.AiTurnOrchestrator;
import org.leo.ai.runtime.AiTurnTrace;
import org.leo.ai.runtime.AiTurnTransaction;
import org.leo.ai.thread.AiConversationStoreService;
import org.leo.core.entity.AiChatAuditEntry;
import org.leo.core.entity.AiExecutionPolicy;
import org.leo.core.entity.AiModelConfig;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;

@Service
public class PlatformAiTurnService {

    private static final Logger logger = LoggerFactory.getLogger(PlatformAiTurnService.class);
    private final AiAuditLogStore auditLogStore;
    private final AiConversationStoreService conversationStore;
    private final PlatformAiAgentRegistry agentRegistry;
    private final AiSseTurnPresenter sseTurnPresenter;
    private final AiTurnCoordinator turnCoordinator;
    private final AiTurnOrchestrator turnOrchestrator;
    private final AiExecutionClaimService executionClaimService;
    private final AiModelChannelResolver channelResolver;

    public PlatformAiTurnService(AiAuditLogStore auditLogStore,
                                 AiConversationStoreService conversationStore,
                                 PlatformAiAgentRegistry agentRegistry,
                                 AiSseTurnPresenter sseTurnPresenter,
                                 AiTurnCoordinator turnCoordinator,
                                 AiTurnOrchestrator turnOrchestrator,
                                 AiExecutionClaimService executionClaimService,
                                 AiModelChannelResolver channelResolver) {
        this.auditLogStore = auditLogStore;
        this.conversationStore = conversationStore;
        this.agentRegistry = agentRegistry;
        this.sseTurnPresenter = sseTurnPresenter;
        this.turnCoordinator = turnCoordinator;
        this.turnOrchestrator = turnOrchestrator;
        this.executionClaimService = executionClaimService;
        this.channelResolver = channelResolver;
    }

    public AiChatAuditEntry appendChatAudit(AiExecutionPolicy policy, String message) {
        AiChatAuditEntry audit = AiChatAuditEntry.platform(
                policy.getUserId(),
                policy.getUserName(),
                policy.getPrivilege(),
                message);
        auditLogStore.append(audit);
        return audit;
    }

    public boolean tryClaimExecution(PlatformAiState state) {
        return executionClaimService.tryClaim(
                state, state.getStateId(),
                () -> state.stopGeneration("执行租约已转移"),
                error -> logger.warn("获取平台 AI 执行租约失败, threadId={}: {}",
                        state.getStateId(), error.getMessage()));
    }

    public void failDetachedExecution(PlatformAiState state) {
        turnCoordinator.failAndRelease(state);
    }

    public void releaseExecutionLease(PlatformAiState state) {
        executionClaimService.release(state, state != null ? state.getStateId() : null);
    }

    public CompletableFuture<AiTurnOrchestrator.TerminalResult> executeChat(
            PlatformAiState state, AiTurnExecutionRequest request) {
        AiTurnCoordinator.Execution turn = turnCoordinator.attach(state);
        String memoryId = state.getStateId();
        AiTurnTrace trace = AiTurnTrace.start(
                "platform", state.getStateId(), request.startMs());
        AiSseTurnPresenter.Session presentation = sseTurnPresenter.open(
                new AiSseTurnPresenter.Context(
                        "Platform AI", state, turn, null, request.audit(), request.startMs(),
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
        if (presentation == null) {
            return CompletableFuture.completedFuture(
                    new AiTurnOrchestrator.TerminalResult(
                            AiTurnOutcome.FAILED,
                            "SSE 启动失败"));
        }

        try {
            if (turn.isCancellationRequested()) {
                throw new InterruptedException("已停止");
            }
            state.touchLastActiveAt();
            String messageForAgent = request.messageForAgent();
            PlatformAiAgentRegistry.Runtime agentRuntime = threadAgent(state, request.reasoningEffort());
            trace.checkpoint(AiTurnTrace.Checkpoint.AGENT_RESOLVED);
            presentation.emitWarning(agentRuntime.failoverMessage());
            AiConversationStoreService.PersistedTurn persistedTurn =
                    conversationStore.beginTurn(
                    request.turnId(), request.userItemId(), request.assistantItemId(), state.getStateId(),
                    agentRuntime.effectiveConfigId(), messageForAgent,
                    request.userMessage(), request.attachments(), request.startMs(), agentRuntime.runtimeJson(),
                    trace, state.getActiveLeaseToken());
            state.bindActiveItemId(persistedTurn.assistantMessageId());
            state.bindActiveRunId(persistedTurn.runId());
            return turnOrchestrator.execute(
                    new AiTurnOrchestrator.Request(
                            new AiTurnCommand(
                                    state.getStateId(), memoryId, turn,
                                    () -> agentRuntime.agent().chat(
                                            memoryId, messageForAgent),
                                    () -> agentRuntime.agent().chat(
                                            memoryId, AiTurnCommand.RECOVERY_MESSAGE)),
                            new AiTurnTransaction.Context(
                                    persistedTurn, agentRuntime.effectiveConfigId(),
                                    agentRuntime.agent(), memoryId, request.audit(), request.startMs(),
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

    private PlatformAiAgentRegistry.Runtime threadAgent(
            PlatformAiState state, String reasoningEffort) {
        AiModelConfig requested = channelResolver.require(
                state != null ? state.getAiConfigId() : null);
        if (state != null && state.getAiConfigId() == null) {
            state.setAiConfigId(requested.getId());
        }
        return agentRegistry.resolve(state, requested, reasoningEffort);
    }

}
