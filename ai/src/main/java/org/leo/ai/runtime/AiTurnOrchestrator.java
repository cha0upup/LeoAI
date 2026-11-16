package org.leo.ai.runtime;

import org.leo.ai.thread.AiConversationStoreService;
import org.leo.core.entity.AiPlan;
import org.leo.core.entity.AiPlanStatus;
import org.leo.core.entity.AiRuntimeStats;
import org.leo.core.entity.AiSseEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * 单轮 AI 执行的应用层编排器。
 *
 * <p>统一模型执行、Turn 持久化终态、启动失败和终态补偿。场景层只通过
 * {@link Lifecycle} 适配事件传输、运行时快照和最终响应。
 */
@Component
public class AiTurnOrchestrator {

    private static final Logger logger = LoggerFactory.getLogger(AiTurnOrchestrator.class);
    private final AiTurnExecutionEngine executionEngine;
    private final AiTurnTransaction turnTransaction;
    private final AiTurnTelemetryRegistry telemetryRegistry;

    public AiTurnOrchestrator(AiTurnExecutionEngine executionEngine,
                              AiTurnTransaction turnTransaction,
                              AiTurnTelemetryRegistry telemetryRegistry) {
        this.executionEngine = executionEngine;
        this.turnTransaction = turnTransaction;
        this.telemetryRegistry = telemetryRegistry;
    }

    public CompletableFuture<TerminalResult> execute(
            Request request, Lifecycle lifecycle) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(lifecycle, "lifecycle");
        CompletableFuture<TerminalResult> completion = new CompletableFuture<>();

        AiTurnTransaction.Session transaction =
                turnTransaction.open(request.transactionContext());
        AiTurnTrace trace = transaction.trace();
        trace.checkpoint(AiTurnTrace.Checkpoint.ORCHESTRATION_STARTED);
        try {
            lifecycle.onStarted();
            trace.checkpoint(AiTurnTrace.Checkpoint.PRESENTATION_STARTED);
        } catch (Throwable error) {
            finishPreparationFailure(
                    request, lifecycle, transaction, error, completion);
            return completion;
        }

        trace.checkpoint(AiTurnTrace.Checkpoint.MODEL_STARTED);
        try {
            executionEngine.execute(request.command(), new AiTurnExecutionListener() {
            @Override
            public void onEvent(AiTurnEvent event) {
                trace.checkpoint(AiTurnTrace.Checkpoint.FIRST_EVENT);
                trace.recordEvent(event);
                lifecycle.onEvent(event);
            }

            @Override
            public void onCompleted(AiTurnResult result) throws Exception {
                trace.checkpoint(AiTurnTrace.Checkpoint.MODEL_COMPLETED);
                trace.recordModelResponse(result != null ? result.response() : null);
                lifecycle.beforeCommit();
                trace.checkpoint(AiTurnTrace.Checkpoint.PERSISTENCE_STARTED);
                AiTurnTransaction.CompletedTurn completed = transaction.commit(
                        result,
                        request.eventLog(),
                        request.planSnapshot().get(),
                        request.runtimeStats());
                trace.checkpoint(AiTurnTrace.Checkpoint.PERSISTENCE_COMPLETED);
                runAfterPersistence(
                        AiTurnOutcome.COMPLETED,
                        transaction,
                        lifecycle,
                        completion,
                        () -> {
                            lifecycle.onCommitted(completed);
                            trace.checkpoint(
                                    AiTurnTrace.Checkpoint.PRESENTATION_COMPLETED);
                            finishTrace(
                                    transaction, AiTurnOutcome.COMPLETED,
                                    null, null);
                            completeResult(completion, transaction);
                        });
            }

            @Override
            public void onFailed(AiTurnFailure failure) throws Exception {
                trace.checkpoint(AiTurnTrace.Checkpoint.MODEL_FAILED);
                finalizeFailure(
                        transaction, lifecycle, failure, true,
                        request.eventLog(), request.planSnapshot().get(),
                        completion);
            }

            @Override
            public void onTerminalFailure(
                    AiTurnOutcome attemptedOutcome, Exception error) {
                recoverTerminalFailure(
                        transaction, lifecycle, attemptedOutcome, error,
                        completion);
            }
            });
        } catch (Throwable error) {
            finishPreparationFailure(
                    request, lifecycle, transaction, error, completion);
        }
        return completion;
    }

    private void finishPreparationFailure(Request request,
                                          Lifecycle lifecycle,
                                          AiTurnTransaction.Session transaction,
                                          Throwable error,
                                          CompletableFuture<TerminalResult> completion) {
        AiTurnCoordinator.Execution execution = request.command().execution();
        transaction.trace().checkpoint(
                AiTurnTrace.Checkpoint.PREPARATION_FAILED);
        AiTurnOutcome outcome = execution.isCancellation(error)
                ? AiTurnOutcome.CANCELLED : AiTurnOutcome.FAILED;
        AiTurnFailure failure = new AiTurnFailure(
                error,
                outcome,
                outcome == AiTurnOutcome.CANCELLED
                        ? execution.cancellationReason() : null);
        try {
            execution.finish(outcome, () ->
                    finalizeFailure(
                            transaction, lifecycle, failure, false,
                            request.eventLog(), request.planSnapshot().get(),
                            completion));
        } catch (Throwable terminalError) {
            recoverTerminalFailure(
                    transaction, lifecycle, outcome, asException(terminalError),
                    completion);
        }
    }

    private void finalizeFailure(AiTurnTransaction.Session transaction,
                                 Lifecycle lifecycle,
                                 AiTurnFailure failure,
                                 boolean modelStarted,
                                 List<AiSseEvent> eventLog,
                                 Object planSnapshot,
                                 CompletableFuture<TerminalResult> completion)
            throws Exception {
        failActivePlan(planSnapshot, failure);
        lifecycle.beforeDiscard(failure);
        transaction.trace().checkpoint(
                AiTurnTrace.Checkpoint.PERSISTENCE_STARTED);
        AiTurnTransaction.FailedTurn failed = modelStarted
                ? transaction.discard(failure, eventLog, planSnapshot)
                : transaction.discardBeforeModel(failure, planSnapshot);
        transaction.trace().checkpoint(
                AiTurnTrace.Checkpoint.PERSISTENCE_COMPLETED);
        runAfterPersistence(
                failure.outcome(),
                transaction,
                lifecycle,
                completion,
                () -> {
                    lifecycle.onDiscarded(failed, failure);
                    transaction.trace().checkpoint(
                            AiTurnTrace.Checkpoint.PRESENTATION_COMPLETED);
                    finishTrace(
                            transaction,
                            failed.outcome(),
                            failed.cancelled()
                                    ? AiConversationStoreService.ERROR_CANCELLED
                                    : failed.classification().category(),
                            failed.message());
                    completeResult(completion, transaction);
                });
    }

    /**
     * Turn 失败时收口仍处于活动状态的计划，避免失败记录留下悬挂的
     * PLANNING/IN_PROGRESS 计划。成功路径仍要求 Agent 显式调用 completePlan。
     */
    private void failActivePlan(Object planSnapshot, AiTurnFailure failure) {
        if (!(planSnapshot instanceof AiPlan plan)
                || plan.getStatus() == null
                || plan.getStatus() == AiPlanStatus.COMPLETED
                || plan.getStatus() == AiPlanStatus.FAILED) {
            return;
        }
        String reason = failure != null && failure.cause() != null
                ? failure.cause().getMessage() : null;
        if (reason == null || reason.isBlank()) {
            reason = failure != null && failure.cancellationReason() != null
                    ? failure.cancellationReason() : "AI Turn 未完成，计划已自动失败";
        }
        try {
            plan.fail("Turn 失败：" + reason);
        } catch (RuntimeException error) {
            logger.warn("自动收口 AI 计划失败: {}", error.getMessage(), error);
        }
    }

    /**
     * 持久化已经进入终态后，传输和响应适配失败不能再反向改变 Turn 结果。
     */
    private void runAfterPersistence(AiTurnOutcome outcome,
                                     AiTurnTransaction.Session transaction,
                                     Lifecycle lifecycle,
                                     CompletableFuture<TerminalResult> completion,
                                     TerminalAction action) {
        try {
            action.run();
        } catch (Throwable error) {
            recoverTerminalFailure(
                    transaction, lifecycle, outcome, asException(error),
                    completion);
        }
    }

    private void recoverTerminalFailure(AiTurnTransaction.Session transaction,
                                        Lifecycle lifecycle,
                                        AiTurnOutcome attemptedOutcome,
                                        Exception error,
                                        CompletableFuture<TerminalResult> completion) {
        boolean recovered = false;
        Exception recoveryError = null;
        transaction.trace().checkpoint(AiTurnTrace.Checkpoint.RECOVERY_STARTED);
        try {
            recovered = transaction.recoverTerminalFailure(
                    attemptedOutcome, error);
        } catch (Throwable terminalRecoveryError) {
            recoveryError = asException(terminalRecoveryError);
            logger.warn("AI Turn 持久化补偿失败: {}",
                    recoveryError.getMessage(), recoveryError);
        }
        Terminal terminal = new Terminal(
                attemptedOutcome,
                error,
                recovered,
                transaction.completedTurn(),
                transaction.failedTurn(),
                recoveryError);
        try {
            lifecycle.onTerminal(terminal);
        } catch (Throwable notificationError) {
            logger.warn("AI Turn 终态通知失败: {}",
                    notificationError.getMessage(), notificationError);
        } finally {
            transaction.trace().checkpoint(
                    AiTurnTrace.Checkpoint.RECOVERY_COMPLETED);
            AiTurnTransaction.FailedTurn failed = transaction.failedTurn();
            AiTurnOutcome finalOutcome = transaction.completedTurn() != null
                    ? AiTurnOutcome.COMPLETED
                    : failed != null ? failed.outcome() : AiTurnOutcome.FAILED;
            String category;
            String message;
            if (recoveryError != null) {
                category = AiConversationStoreService.ERROR_PERSISTENCE;
                message = recoveryError.getMessage();
            } else if (failed != null) {
                category = failed.cancelled()
                        ? AiConversationStoreService.ERROR_CANCELLED
                        : failed.classification().category();
                message = failed.message();
            } else if (transaction.completedTurn() != null) {
                category = "presentation";
                message = error.getMessage();
            } else {
                category = AiConversationStoreService.ERROR_PERSISTENCE;
                message = error.getMessage();
            }
            try {
                finishTrace(
                        transaction, finalOutcome, category, message);
            } catch (Throwable traceError) {
                logger.warn("AI Turn 终态追踪写入失败: {}",
                        traceError.getMessage(), traceError);
            } finally {
                completeResult(completion, transaction);
            }
        }
    }

    private void completeResult(CompletableFuture<TerminalResult> completion,
                                AiTurnTransaction.Session transaction) {
        AiTurnTransaction.FailedTurn failed = transaction.failedTurn();
        AiTurnOutcome outcome = transaction.completedTurn() != null
                ? AiTurnOutcome.COMPLETED
                : failed != null ? failed.outcome() : AiTurnOutcome.FAILED;
        String errorMessage = failed != null ? failed.message() : null;
        completion.complete(new TerminalResult(outcome, errorMessage));
    }

    private void finishTrace(AiTurnTransaction.Session transaction,
                             AiTurnOutcome outcome,
                             String errorCategory,
                             String errorMessage) {
        AiTurnTrace trace = transaction.trace();
        trace.finish(outcome, errorCategory, errorMessage);
        transaction.persistTrace();
        telemetryRegistry.record(trace);
        logger.info(
                "AI Turn 完成 traceId={} runId={} conversationId={} outcome={} durationMs={} errorCategory={}",
                trace.traceId(), trace.runId(), trace.conversationId(),
                trace.outcome(), trace.durationMillis(), trace.errorCategory());
    }

    private static Exception asException(Throwable error) {
        return error instanceof Exception exception
                ? exception
                : new IllegalStateException("AI Turn 编排发生严重错误", error);
    }

    @FunctionalInterface
    private interface TerminalAction {
        void run() throws Exception;
    }

    public interface Lifecycle {

        default void onStarted() throws Exception {
        }

        void onEvent(AiTurnEvent event);

        default void beforeCommit() throws Exception {
        }

        void onCommitted(AiTurnTransaction.CompletedTurn completed) throws Exception;

        default void beforeDiscard(AiTurnFailure failure) throws Exception {
        }

        void onDiscarded(AiTurnTransaction.FailedTurn failed,
                         AiTurnFailure failure) throws Exception;

        default void onTerminal(Terminal terminal) {
        }
    }

    public record Request(AiTurnCommand command,
                          AiTurnTransaction.Context transactionContext,
                          List<AiSseEvent> eventLog,
                          Supplier<?> planSnapshot,
                          AiRuntimeStats runtimeStats) {

        public Request {
            Objects.requireNonNull(command, "command");
            Objects.requireNonNull(transactionContext, "transactionContext");
            eventLog = eventLog != null ? eventLog : List.of();
            planSnapshot = planSnapshot != null ? planSnapshot : () -> null;
        }
    }

    public record TerminalResult(AiTurnOutcome outcome, String errorMessage) {

        public String runtimeStatus() {
            return outcome.runtimeStatus();
        }
    }

    public record Terminal(AiTurnOutcome attemptedOutcome,
                           Exception error,
                           boolean recovered,
                           AiTurnTransaction.CompletedTurn completed,
                           AiTurnTransaction.FailedTurn failed,
                           Exception recoveryError) {

        public boolean committed() {
            return completed != null;
        }

        public boolean discarded() {
            return failed != null;
        }

        public boolean recoveryFailed() {
            return recoveryError != null;
        }
    }
}
