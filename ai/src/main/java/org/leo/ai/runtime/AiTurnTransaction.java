package org.leo.ai.runtime;

import dev.langchain4j.service.memory.ChatMemoryAccess;
import org.leo.ai.channel.AiModelFailoverService;
import org.leo.ai.memory.ManagedConversationMemory;
import org.leo.ai.service.AiErrorClassifier;
import org.leo.ai.thread.AiConversationStoreService;
import org.leo.core.entity.AiChatAuditEntry;
import org.leo.core.entity.AiRuntimeStats;
import org.leo.core.ai.AiRunStatus;
import org.leo.core.entity.AiSseEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 单轮 AI 执行的持久化事务收口。
 *
 * <p>每个 Turn 创建一个 {@link Session}，统一提交、丢弃、模型健康记录、审计和
 * 上下文 memory 重建。Session 自身保证持久化终态最多写入一次。
 */
@Component
public class AiTurnTransaction {

    private static final Logger logger = LoggerFactory.getLogger(AiTurnTransaction.class);
    private final AiConversationStoreService conversationStore;
    private final AiModelFailoverService failoverService;
    private final AiErrorClassifier errorClassifier;
    private final AiTurnArtifacts artifacts;
    private final ManagedConversationMemory managedMemory;

    public AiTurnTransaction(AiConversationStoreService conversationStore,
                             AiModelFailoverService failoverService,
                             AiErrorClassifier errorClassifier,
                             AiTurnArtifacts artifacts,
                             ManagedConversationMemory managedMemory) {
        this.conversationStore = conversationStore;
        this.failoverService = failoverService;
        this.errorClassifier = errorClassifier;
        this.artifacts = artifacts;
        this.managedMemory = managedMemory;
    }

    public Session open(Context context) {
        return new Session(context);
    }

    public final class Session {
        private final Context context;
        private PersistenceState state = PersistenceState.PENDING;
        private CompletedTurn completedTurn;
        private FailedTurn failedTurn;

        private Session(Context context) {
            if (context == null) throw new IllegalArgumentException("context 不能为空");
            this.context = context;
        }

        public synchronized CompletedTurn commit(AiTurnResult result,
                                                 List<AiSseEvent> eventLog,
                                                 Object planSnapshot,
                                                 AiRuntimeStats runtimeStats) {
            if (state == PersistenceState.COMMITTED) return completedTurn;
            requirePending("提交");

            boolean userInputRequested = result != null && result.userInputRequested();
            String output = userInputRequested ? "" : result != null ? result.output() : "";
            int toolCallCount = artifacts.toolCallCount(eventLog);
            Map<String, Object> review = artifacts.review(
                    output, eventLog, elapsedMillis());
            if (result != null && result.streamRecovered()) {
                review.put("streamRecovered", true);
            }
            Map<String, Object> usage =
                    artifacts.usage(result != null ? result.response() : null);
            List<Object> assistantNodes = artifacts.assistantNodes(
                    eventLog, !userInputRequested);

            conversationStore.completeTurn(
                    context.persistedTurn(), output, assistantNodes,
                    review, planSnapshot, toolCallCount);
            state = PersistenceState.COMMITTED;
            completedTurn = new CompletedTurn(
                    output, toolCallCount, review, usage, assistantNodes);
            if (result != null && result.streamRecovered()) {
                // 自动续接会在 ChatMemory 中加入内部恢复提示。
                // 提交后以数据库中的原始用户消息 + 合并结果重建，
                // 使后续 Turn 与重启后的上下文保持一致。
                rebuildContextMemory();
            }
            runAfterTerminal("记录模型成功", () ->
                    failoverService.recordSuccess(context.effectiveConfigId()));
            if (!usage.isEmpty()) {
                runAfterTerminal("累计模型用量", () ->
                        artifacts.accumulateUsage(runtimeStats, usage));
            }
            if (context.audit() != null) {
                runAfterTerminal("完成审计", () ->
                        context.audit().complete(output, toolCallCount, elapsedMillis()));
            }
            return completedTurn;
        }

        public synchronized FailedTurn discard(AiTurnFailure failure,
                                               List<AiSseEvent> eventLog,
                                               Object planSnapshot) {
            return discard(failure, eventLog, planSnapshot, true);
        }

        /**
         * 模型启动前失败时保留计划快照，不降低模型通道健康度。
         */
        public synchronized FailedTurn discardBeforeModel(AiTurnFailure failure,
                                                          Object planSnapshot) {
            return discard(failure, List.of(), planSnapshot, false);
        }

        private FailedTurn discard(AiTurnFailure failure,
                                   List<AiSseEvent> eventLog,
                                   Object planSnapshot,
                                   boolean recordModelFailure) {
            if (state == PersistenceState.DISCARDED) return failedTurn;
            requirePending("丢弃");
            Objects.requireNonNull(failure, "failure");

            boolean cancelled = failure.cancelled();
            AiErrorClassifier.Classification classification =
                    cancelled ? null : errorClassifier.classify(failure.cause());
            String message = cancelled
                    ? normalizeCancellationReason(failure.cancellationReason())
                    : classification.message();
            String status = cancelled ? AiRunStatus.CANCELLED : AiRunStatus.FAILED;
            List<Object> assistantNodes = artifacts.assistantNodes(eventLog, true);
            String partialOutput = AiTurnRecoveryContext.build(
                    eventLog, planSnapshot);
            int toolCallCount = artifacts.toolCallCount(eventLog);
            try {
                String category = cancelled
                        ? AiConversationStoreService.ERROR_CANCELLED
                        : classification.category();
                String rawMessage = cancelled ? message : classification.rawMessage();
                conversationStore.discardTurn(
                        context.persistedTurn(), status, category,
                        message, rawMessage, toolCallCount,
                        partialOutput, assistantNodes, planSnapshot);
                state = PersistenceState.DISCARDED;
                failedTurn = new FailedTurn(failure.outcome(), status, message, classification);
                if (!cancelled && recordModelFailure) {
                    runAfterTerminal("记录模型失败", () ->
                            failoverService.recordFailure(
                                    context.effectiveConfigId(), classification));
                }
                if (context.audit() != null) {
                    runAfterTerminal("记录失败审计", () ->
                            context.audit().fail(message, elapsedMillis()));
                }
                return failedTurn;
            } finally {
                rebuildContextMemory();
            }
        }

        /**
         * 终态回调自身抛错后的补偿。已经提交或丢弃的 Session 不会再次写数据库。
         *
         * @return {@code true} 表示本次实际执行了持久化补偿
         */
        public synchronized boolean recoverTerminalFailure(
                AiTurnOutcome attemptedOutcome, Exception terminalError) {
            if (state != PersistenceState.PENDING) return false;
            String message = attemptedOutcome == AiTurnOutcome.COMPLETED
                    ? "AI 回复提交失败" : "AI 失败状态提交失败";
            AiErrorClassifier.Classification classification =
                    errorClassifier.classifyCategory(
                            AiConversationStoreService.ERROR_PERSISTENCE,
                            terminalError != null ? terminalError.getMessage() : message);
            try {
                conversationStore.discardTurn(
                        context.persistedTurn(), AiRunStatus.FAILED,
                        AiConversationStoreService.ERROR_PERSISTENCE,
                        message,
                        terminalError != null ? terminalError.getMessage() : message,
                        0, "", List.of(), null);
                state = PersistenceState.DISCARDED;
                failedTurn = new FailedTurn(
                        AiTurnOutcome.FAILED, AiRunStatus.FAILED,
                        message, classification);
                if (context.audit() != null) {
                    runAfterTerminal("记录终态补偿审计", () ->
                            context.audit().fail(message, elapsedMillis()));
                }
                return true;
            } finally {
                rebuildContextMemory();
            }
        }

        public synchronized boolean isTerminal() {
            return state != PersistenceState.PENDING;
        }

        public synchronized CompletedTurn completedTurn() {
            return completedTurn;
        }

        public synchronized FailedTurn failedTurn() {
            return failedTurn;
        }

        public AiTurnTrace trace() {
            return context.trace();
        }

        /** 最终 Presenter 阶段结束后补写完整 trace，不影响已确定的业务终态。 */
        public void persistTrace() {
            try {
                conversationStore.updateRunTrace(
                        context.persistedTurn(), context.trace());
            } catch (RuntimeException error) {
                logger.warn("持久化 AI Turn trace 失败, traceId={}, runId={}: {}",
                        context.trace().traceId(),
                        context.persistedTurn().runId(),
                        error.getMessage(), error);
            }
        }

        private void requirePending(String action) {
            if (state != PersistenceState.PENDING) {
                throw new IllegalStateException(
                        "Turn 已处于 " + state + "，不能再次" + action);
            }
        }

        private long elapsedMillis() {
            return Math.max(0L, System.currentTimeMillis() - context.startedAt());
        }

        private void rebuildContextMemory() {
            if (context.memoryAgent() == null || context.memoryId() == null) return;
            try {
                managedMemory.rebuild(context.memoryAgent(), context.memoryId());
            } catch (RuntimeException error) {
                logger.warn("重建 AI 上下文记忆失败, memoryId={}: {}",
                        context.memoryId(), error.getMessage());
            }
        }

        private void runAfterTerminal(String action, Runnable callback) {
            try {
                callback.run();
            } catch (RuntimeException error) {
                logger.warn("Turn 终态后处理失败, action={}: {}",
                        action, error.getMessage(), error);
            }
        }
    }

    private enum PersistenceState {
        PENDING,
        COMMITTED,
        DISCARDED
    }

    public record Context(AiConversationStoreService.PersistedTurn persistedTurn,
                          Integer effectiveConfigId,
                          ChatMemoryAccess memoryAgent,
                          Object memoryId,
                          AiChatAuditEntry audit,
                          long startedAt,
                          AiTurnTrace trace) {

        public Context {
            Objects.requireNonNull(persistedTurn, "persistedTurn");
            Objects.requireNonNull(trace, "trace");
        }
    }

    public record CompletedTurn(String output,
                                int toolCallCount,
                                Map<String, Object> review,
                                Map<String, Object> usage,
                                List<Object> assistantNodes) {
    }

    public record FailedTurn(AiTurnOutcome outcome,
                             String status,
                             String message,
                             AiErrorClassifier.Classification classification) {
        public boolean cancelled() {
            return outcome == AiTurnOutcome.CANCELLED;
        }
    }

    private static String normalizeCancellationReason(String reason) {
        return reason != null && !reason.isBlank() ? reason : "已停止";
    }
}
