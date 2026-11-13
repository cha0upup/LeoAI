package org.leo.web.application.ai;

import org.leo.ai.runtime.AiTurnCoordinator;
import org.leo.ai.runtime.AiTurnEvent;
import org.leo.ai.runtime.AiTurnFailure;
import org.leo.ai.runtime.AiTurnOrchestrator;
import org.leo.ai.runtime.AiTurnOutcome;
import org.leo.ai.runtime.AiTurnTelemetryRegistry;
import org.leo.ai.runtime.AiTurnTrace;
import org.leo.ai.runtime.AiTurnTransaction;
import org.leo.ai.service.AiErrorClassifier;
import org.leo.core.ai.AiEventStreamRuntime;
import org.leo.core.entity.AiChatAuditEntry;
import org.leo.core.entity.AiSseEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 平台 AI 与节点主 AI 共用的 Turn 事件与生命周期呈现。
 *
 * <p>只记录可重放事件、整理 timeline 并刷新运行状态；连接发送和心跳由订阅适配器负责。
 * 模型回调由执行引擎串行派发，排队的工具事件在回调和终态边界同步归档。
 */
@Component
public class AiTurnPresenter {

    private static final Logger logger = LoggerFactory.getLogger(AiTurnPresenter.class);
    private static final String STATUS_RUNNING = "running";
    private static final String STATUS_COMPLETED = "completed";
    private static final String STATUS_FAILED = "failed";

    private final AiErrorClassifier errorClassifier;
    private final AiTurnTelemetryRegistry telemetryRegistry;

    public AiTurnPresenter(AiErrorClassifier errorClassifier,
                           AiTurnTelemetryRegistry telemetryRegistry) {
        this.errorClassifier = errorClassifier;
        this.telemetryRegistry = telemetryRegistry;
    }

    public Session open(Context context) {
        Objects.requireNonNull(context, "context");
        context.runtime().getAiSseEventQueue().clear();
        context.trace().checkpoint(AiTurnTrace.Checkpoint.TRANSPORT_READY);
        Session session = new Session(context);
        session.emitSafely("trace", context.trace().eventPayload());
        return session;
    }

    public final class Session implements AiTurnOrchestrator.Lifecycle {
        private final Context context;
        private final AiTimelineRecorder recorder;
        private final AiTurnTimelineEventAdapter eventAdapter;

        private Session(Context context) {
            this.context = context;
            this.recorder = new AiTimelineRecorder(this::emitSafely);
            this.eventAdapter = new AiTurnTimelineEventAdapter(
                    recorder,
                    this::emitSafely,
                    context.thinkingStarted(),
                    null,
                    true);
        }

        public List<AiSseEvent> eventLog() {
            return recorder.eventLog();
        }

        public void emitWarning(String warning) {
            if (warning != null && !warning.isBlank()) {
                emitSafely("warn", warning);
            }
        }

        @Override
        public void onStarted() throws Exception {
            context.refreshRuntime().run();
            emit("status", STATUS_RUNNING);
            context.afterStarted().run();
            drainQueuedEvents();
        }

        @Override
        public void onEvent(AiTurnEvent event) {
            drainQueuedEvents();
            eventAdapter.accept(event);
        }

        @Override
        public void beforeCommit() {
            recorder.onBoundary();
            drainQueuedEvents();
        }

        @Override
        public void onCommitted(
                AiTurnTransaction.CompletedTurn completed) throws Exception {
            context.beforeCompleted().run();
            context.refreshRuntime().run();
            drainQueuedEvents();
            emitSafely("status", context.runtime().getRunStatus());
            emitSafely("turn", turnEvent(completed));
        }

        @Override
        public void beforeDiscard(AiTurnFailure failure) {
            recorder.flushDelta();
            drainQueuedEvents();
        }

        @Override
        public void onDiscarded(AiTurnTransaction.FailedTurn failed,
                                AiTurnFailure failure) throws Exception {
            context.refreshRuntime().run();
            emitSafely("status", failed.status());
        }

        @Override
        public void onTerminal(AiTurnOrchestrator.Terminal terminal) {
            logger.warn("{} Turn 终态展示补偿: {}",
                    context.source(),
                    terminal.error().getMessage(),
                    terminal.error());
            refreshRuntimeSafely(context, "终态补偿");
            drainQueuedEvents();
            if (terminal.recoveryFailed()) {
                emitSafely("status", STATUS_FAILED);
            } else if (terminal.committed()) {
                emitSafely("status", STATUS_COMPLETED);
                emitSafely(
                        "turn", turnEvent(terminal.completed()));
            } else if (terminal.discarded()) {
                emitSafely("status", terminal.failed().status());
            } else {
                emitSafely("status", STATUS_FAILED);
            }
        }

        /**
         * Agent 解析或 beginTurn 失败时的无持久化 Turn 收口。
         */
        public void finishPreparationFailure(Throwable error) {
            if (context.execution().isFinished()) return;
            context.trace().checkpoint(
                    AiTurnTrace.Checkpoint.PREPARATION_FAILED);
            boolean cancelled = context.execution().isCancellation(error);
            AiTurnOutcome outcome = cancelled
                    ? AiTurnOutcome.CANCELLED : AiTurnOutcome.FAILED;
            try {
                context.execution().finish(outcome, () -> {
                    drainQueuedEvents();
                    refreshRuntimeSafely(context, "准备失败");
                    if (cancelled) {
                        String reason = context.execution().cancellationReason();
                        finishUnpersistedTrace(
                                context.trace(), AiTurnOutcome.CANCELLED,
                                "cancelled", reason);
                        if (context.audit() != null) {
                            context.audit().fail(reason, context.elapsedMillis());
                        }
                        emitSafely("status", "cancelled");
                    } else {
                        AiErrorClassifier.Classification classification =
                                errorClassifier.classify(error);
                        finishUnpersistedTrace(
                                context.trace(), AiTurnOutcome.FAILED,
                                classification.category(),
                                classification.message());
                        if (context.audit() != null) {
                            context.audit().fail(
                                    classification.message(),
                                    context.elapsedMillis());
                        }
                        emitSafely("status", STATUS_FAILED);
                    }
                });
            } catch (Exception finishError) {
                logger.warn("{} 准备失败收口异常: {}",
                        context.source(), finishError.getMessage(), finishError);
            }
        }

        private Map<String, Object> turnEvent(
                AiTurnTransaction.CompletedTurn completed) {
            Map<String, Object> payload = new LinkedHashMap<>();
            if (!completed.usage().isEmpty()) {
                payload.put("usage", completed.usage());
            }
            payload.put("trace", context.trace().eventPayload());
            payload.put("review", completed.review());
            payload.put("content", completed.output());
            return payload;
        }

        private void emit(String name, Object data) {
            Object invocation = data instanceof Map<?, ?> map ? map.get("subagentInvocationId") : null;
            String invocationId = invocation != null ? invocation.toString() : null;
            context.runtime().recordSseEvent(name, data,
                    invocationId != null && !invocationId.isBlank() ? invocationId : null);
        }

        private void emitSafely(String name, Object data) {
            try {
                emit(name, data);
            } catch (RuntimeException error) {
                logger.debug("{} 事件记录失败: {}", context.source(), error.getMessage());
            }
        }

        private void drainQueuedEvents() {
            context.runtime().getAiSseEventQueue().drainTo(recorder.eventLog());
        }
    }

    private void refreshRuntimeSafely(Context context, String phase) {
        try {
            context.refreshRuntime().run();
        } catch (Exception runtimeError) {
            logger.warn("{} {}运行时状态刷新失败: {}",
                    context.source(), phase,
                    runtimeError.getMessage(), runtimeError);
        }
    }

    private void finishUnpersistedTrace(AiTurnTrace trace,
                                        AiTurnOutcome outcome,
                                        String errorCategory,
                                        String errorMessage) {
        trace.finish(outcome, errorCategory, errorMessage);
        telemetryRegistry.record(trace);
        logger.info(
                "AI Turn 准备阶段完成 traceId={} conversationId={} outcome={} durationMs={} errorCategory={}",
                trace.traceId(), trace.conversationId(), trace.outcome(),
                trace.durationMillis(), trace.errorCategory());
    }

    @FunctionalInterface
    public interface CheckedAction {
        void run() throws Exception;
    }

    public record Context(String source,
                          AiEventStreamRuntime runtime,
                          AiTurnCoordinator.Execution execution,
                          AiChatAuditEntry audit,
                          long startedAt,
                          AiTurnTrace trace,
                          CheckedAction refreshRuntime,
                          CheckedAction afterStarted,
                          CheckedAction beforeCompleted,
                          Runnable thinkingStarted) {

        public Context {
            if (source == null || source.isBlank()) source = "AI";
            Objects.requireNonNull(runtime, "runtime");
            Objects.requireNonNull(execution, "execution");
            Objects.requireNonNull(trace, "trace");
            refreshRuntime = refreshRuntime != null
                    ? refreshRuntime : () -> {};
            afterStarted = afterStarted != null
                    ? afterStarted : () -> {};
            beforeCompleted = beforeCompleted != null
                    ? beforeCompleted : () -> {};
            thinkingStarted = thinkingStarted != null
                    ? thinkingStarted : () -> {};
        }

        private long elapsedMillis() {
            return Math.max(0L, System.currentTimeMillis() - startedAt);
        }
    }
}
