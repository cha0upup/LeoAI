package org.leo.web.service.ai;

import org.leo.ai.runtime.AiTurnCoordinator;
import org.leo.ai.runtime.AiTurnEvent;
import org.leo.ai.runtime.AiTurnFailure;
import org.leo.ai.runtime.AiTurnOrchestrator;
import org.leo.ai.runtime.AiTurnOutcome;
import org.leo.ai.runtime.AiTurnTransaction;
import org.leo.ai.runtime.AiTurnTelemetryRegistry;
import org.leo.ai.runtime.AiTurnTrace;
import org.leo.ai.service.AiErrorClassifier;
import org.leo.core.ai.AiEventStreamRuntime;
import org.leo.core.entity.AiChatAuditEntry;
import org.leo.core.entity.AiSseEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 平台 AI 与节点主 AI 共用的 SSE Turn 展示适配器。
 *
 * <p>封装 timeline、SSE transport、运行状态刷新以及成功/失败/补偿的最终呈现，
 * Turn Service 只负责准备模型与持久化上下文。
 */
@Component
public class AiSseTurnPresenter {

    private static final Logger logger = LoggerFactory.getLogger(AiSseTurnPresenter.class);
    private static final String STATUS_RUNNING = "running";
    private static final String STATUS_COMPLETED = "completed";
    private static final String STATUS_FAILED = "failed";

    private final AiSseTransport sseTransport;
    private final AiErrorClassifier errorClassifier;
    private final AiTurnTelemetryRegistry telemetryRegistry;

    public AiSseTurnPresenter(AiSseTransport sseTransport,
                              AiErrorClassifier errorClassifier,
                              AiTurnTelemetryRegistry telemetryRegistry) {
        this.sseTransport = sseTransport;
        this.errorClassifier = errorClassifier;
        this.telemetryRegistry = telemetryRegistry;
    }

    /**
     * 打开一次 SSE 展示会话。Transport 启动失败会在本方法内完成执行权收口。
     *
     * @return transport 启动失败时返回 {@code null}
     */
    public Session open(Context context) {
        Objects.requireNonNull(context, "context");
        AtomicReference<AiSseTransport.Session> transportRef = new AtomicReference<>();
        AiTimelineRecorder recorder = new AiTimelineRecorder((name, data) -> {
            AiSseTransport.Session current = transportRef.get();
            if (current != null) current.emitSafely(name, data);
        });
        try {
            AiSseTransport.Session transport = sseTransport.open(
                    context.source(), context.runtime(),
                    context.emitter(), recorder.eventLog());
            transportRef.set(transport);
            context.trace().checkpoint(AiTurnTrace.Checkpoint.TRANSPORT_READY);
            Session session = new Session(context, recorder, transport);
            session.emitTrace();
            return session;
        } catch (RuntimeException error) {
            finishWithoutTransport(context, error);
            return null;
        }
    }

    private void finishWithoutTransport(Context context, RuntimeException error) {
        AiErrorClassifier.Classification classification =
                errorClassifier.classify(error);
        context.trace().checkpoint(AiTurnTrace.Checkpoint.PREPARATION_FAILED);
        finishUnpersistedTrace(
                context.trace(), AiTurnOutcome.FAILED,
                classification.category(), classification.message());
        try {
            context.execution().finish(AiTurnOutcome.FAILED, () -> {
                refreshRuntimeSafely(context, "启动失败");
                if (context.audit() != null) {
                    context.audit().fail(
                            classification.message(), context.elapsedMillis());
                }
                AiControllerUtil.safeSendError(
                        context.emitter(), classification);
            });
        } catch (Exception finishError) {
            logger.warn("{} SSE 启动失败收口异常: {}",
                    context.source(), finishError.getMessage(), finishError);
            AiControllerUtil.safeComplete(context.emitter());
        }
    }

    public final class Session implements AiTurnOrchestrator.Lifecycle {
        private final Context context;
        private final AiTimelineRecorder recorder;
        private final AiSseTransport.Session transport;
        private final AiTurnTimelineEventAdapter eventAdapter;

        private Session(Context context,
                        AiTimelineRecorder recorder,
                        AiSseTransport.Session transport) {
            this.context = context;
            this.recorder = recorder;
            this.transport = transport;
            this.eventAdapter = new AiTurnTimelineEventAdapter(
                    recorder,
                    transport::emitSafely,
                    context.thinkingStarted(),
                    null,
                    true);
        }

        public List<AiSseEvent> eventLog() {
            return recorder.eventLog();
        }

        public void emitWarning(String warning) {
            if (warning != null && !warning.isBlank()) {
                transport.emitSafely("warn", warning);
            }
        }

        private void emitTrace() {
            transport.emitSafely("trace", context.trace().eventPayload());
        }

        @Override
        public void onStarted() throws Exception {
            context.refreshRuntime().run();
            transport.emit("status", STATUS_RUNNING);
            context.afterStarted().run();
        }

        @Override
        public void onEvent(AiTurnEvent event) {
            eventAdapter.accept(event);
        }

        @Override
        public void beforeCommit() {
            recorder.onBoundary();
            transport.stopAndFlush();
        }

        @Override
        public void onCommitted(
                AiTurnTransaction.CompletedTurn completed) throws Exception {
            context.beforeCompleted().run();
            context.refreshRuntime().run();
            transport.emitStatus(context.runtime().getRunStatus());
            transport.emitSafely("turn", turnEvent(completed));
            AiControllerUtil.safeComplete(context.emitter());
        }

        @Override
        public void beforeDiscard(AiTurnFailure failure) {
            recorder.flushDelta();
            transport.stopAndFlush();
        }

        @Override
        public void onDiscarded(AiTurnTransaction.FailedTurn failed,
                                AiTurnFailure failure) throws Exception {
            context.refreshRuntime().run();
            transport.emitStatus(failed.status());
            sendFailure(failed);
        }

        @Override
        public void onTerminal(AiTurnOrchestrator.Terminal terminal) {
            logger.warn("{} Turn 终态展示补偿: {}",
                    context.source(),
                    terminal.error().getMessage(),
                    terminal.error());
            refreshRuntimeSafely(context, "终态补偿");
            if (terminal.recoveryFailed()) {
                transport.emitStatus(STATUS_FAILED);
                AiControllerUtil.safeSendError(
                        context.emitter(), "AI 回复提交失败，请重试");
            } else if (terminal.committed()) {
                transport.emitStatus(STATUS_COMPLETED);
                transport.emitSafely(
                        "turn", turnEvent(terminal.completed()));
                AiControllerUtil.safeComplete(context.emitter());
            } else if (terminal.discarded()) {
                transport.emitStatus(terminal.failed().status());
                if (terminal.recovered()) {
                    AiControllerUtil.safeSendError(
                            context.emitter(), terminal.failed().message());
                } else {
                    sendFailure(terminal.failed());
                }
            } else {
                transport.emitStatus(STATUS_FAILED);
                AiControllerUtil.safeSendError(
                        context.emitter(), "AI 执行失败，请重试");
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
                    transport.stopAndFlush();
                    refreshRuntimeSafely(context, "准备失败");
                    if (cancelled) {
                        String reason = context.execution().cancellationReason();
                        finishUnpersistedTrace(
                                context.trace(), AiTurnOutcome.CANCELLED,
                                "cancelled", reason);
                        if (context.audit() != null) {
                            context.audit().fail(reason, context.elapsedMillis());
                        }
                        transport.emitStatus("cancelled");
                        AiControllerUtil.safeSendError(
                                context.emitter(), reason);
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
                        transport.emitStatus(STATUS_FAILED);
                        AiControllerUtil.safeSendError(
                                context.emitter(), classification);
                    }
                });
            } catch (Exception finishError) {
                logger.warn("{} 准备失败收口异常: {}",
                        context.source(), finishError.getMessage(), finishError);
                AiControllerUtil.safeComplete(context.emitter());
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

        private void sendFailure(AiTurnTransaction.FailedTurn failed) {
            if (failed.cancelled()) {
                AiControllerUtil.safeSendError(
                        context.emitter(), failed.message());
            } else {
                AiControllerUtil.safeSendError(
                        context.emitter(), failed.classification());
            }
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
                          SseEmitter emitter,
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
