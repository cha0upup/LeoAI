package org.leo.web.service;

import org.leo.ai.runtime.AiTurnCoordinator;
import org.leo.ai.runtime.AiTurnEvent;
import org.leo.ai.runtime.AiTurnFailure;
import org.leo.ai.runtime.AiTurnOrchestrator;
import org.leo.ai.runtime.AiTurnOutcome;
import org.leo.ai.runtime.AiTurnTransaction;
import org.leo.ai.runtime.AiTurnTelemetryRegistry;
import org.leo.ai.runtime.AiTurnTrace;
import org.leo.ai.service.AiErrorClassifier;
import org.leo.ai.thread.AiConversationStoreService;
import org.leo.core.entity.AiChatAuditEntry;
import org.leo.core.entity.AiSseEvent;
import org.leo.core.session.AiThread;
import org.leo.core.ai.AiRunStatus;
import org.leo.core.session.PuppetNodeSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** 平台委派 Puppet AI 时使用的同步 Turn 展示适配器。 */
@Component
public class PuppetNodeAiDelegationPresenter {

    private static final Logger logger =
            LoggerFactory.getLogger(PuppetNodeAiDelegationPresenter.class);
    private final AiConversationStoreService conversationStore;
    private final AiErrorClassifier errorClassifier;
    private final AiTurnTelemetryRegistry telemetryRegistry;

    public PuppetNodeAiDelegationPresenter(
            AiConversationStoreService conversationStore,
            AiErrorClassifier errorClassifier,
            AiTurnTelemetryRegistry telemetryRegistry) {
        this.conversationStore = conversationStore;
        this.errorClassifier = errorClassifier;
        this.telemetryRegistry = telemetryRegistry;
    }

    public Session open(Context context) {
        Context required = Objects.requireNonNull(context, "context");
        required.trace().checkpoint(AiTurnTrace.Checkpoint.TRANSPORT_READY);
        Session session = new Session(required);
        session.recordEvent("trace", required.trace().eventPayload());
        return session;
    }

    public final class Session implements AiTurnOrchestrator.Lifecycle {
        private final Context context;
        private final List<AiSseEvent> eventLog = new ArrayList<>();
        private final CompletableFuture<Map<String, Object>> completion =
                new CompletableFuture<>();
        private final AiTimelineRecorder recorder;
        private final AiTurnTimelineEventAdapter eventAdapter;

        private Session(Context context) {
            this.context = context;
            this.recorder = new AiTimelineRecorder(this::recordEvent);
            this.eventAdapter = new AiTurnTimelineEventAdapter(
                    recorder,
                    this::recordEvent,
                    null,
                    this::drainQueuedEvents,
                    false);
        }

        public List<AiSseEvent> eventLog() {
            return eventLog;
        }

        public void emitWarning(String warning) {
            if (warning != null && !warning.isBlank()) {
                context.thread().offerSystemWarn(warning);
            }
        }

        public Map<String, Object> await() throws Exception {
            return completion.get();
        }

        @Override
        public void onStarted() {
            refreshRuntime();
            recordEvent("status", AiRunStatus.RUNNING);
        }

        @Override
        public void onEvent(AiTurnEvent event) {
            eventAdapter.accept(event);
        }

        @Override
        public void beforeCommit() {
            recorder.onBoundary();
            drainQueuedEvents();
        }

        @Override
        public void onCommitted(
                AiTurnTransaction.CompletedTurn completed) {
            context.thread().touchLastActiveAt();
            refreshRuntime();
            recordEvent("status", context.thread().getRunStatus());
            completion.complete(successResponse(completed.output()));
        }

        @Override
        public void onDiscarded(AiTurnTransaction.FailedTurn failed,
                                AiTurnFailure failure) {
            try {
                refreshRuntime();
                recordEvent("status", failed.status());
            } finally {
                completion.completeExceptionally(
                        new IllegalStateException(
                                failed.message(), failure.cause()));
            }
        }

        @Override
        public void onTerminal(AiTurnOrchestrator.Terminal terminal) {
            logger.warn("委派 Puppet AI 终态展示补偿: {}",
                    terminal.error().getMessage(), terminal.error());
            refreshRuntimeSafely();
            try {
                if (terminal.committed()) {
                    recordEvent("status", context.thread().getRunStatus());
                } else if (terminal.discarded()) {
                    recordEvent("status", terminal.failed().status());
                } else {
                    recordEvent("status", AiRunStatus.FAILED);
                }
            } catch (RuntimeException eventError) {
                logger.warn("委派 Puppet AI 终态事件发送失败: {}",
                        eventError.getMessage(), eventError);
            }
            if (terminal.committed()) {
                completion.complete(successResponse(
                        terminal.completed().output()));
            } else {
                Throwable cause = terminal.recoveryError() != null
                        ? terminal.recoveryError() : terminal.error();
                completion.completeExceptionally(cause);
            }
        }

        /**
         * 将准备阶段或 await 阶段异常转换为委派 API 的稳定异常。
         */
        public IllegalStateException executionFailure(
                AiTurnCoordinator.Execution execution, Throwable error) {
            if (execution.isFinished()) {
                Throwable cause = unwrapCompletionError(error);
                return new IllegalStateException(
                        normalizedMessage(cause), cause);
            }

            context.trace().checkpoint(
                    AiTurnTrace.Checkpoint.PREPARATION_FAILED);
            boolean cancelled = execution.isCancellation(error);
            AiErrorClassifier.Classification classification = cancelled
                    ? null : errorClassifier.classify(error);
            String message = cancelled
                    ? execution.cancellationReason()
                    : classification.message();
            try {
                execution.finish(
                        cancelled
                                ? AiTurnOutcome.CANCELLED
                                : AiTurnOutcome.FAILED,
                        () -> {
                            if (context.audit() != null) {
                                context.audit().fail(
                                        message, context.elapsedMillis());
                            }
                            refreshRuntimeSafely();
                            try {
                                recordEvent(
                                        "status",
                                        cancelled
                                                ? AiRunStatus.CANCELLED
                                                : AiRunStatus.FAILED);
                            } catch (RuntimeException eventError) {
                                logger.warn("委派 Puppet AI 准备失败事件发送异常: {}",
                                        eventError.getMessage(), eventError);
                            }
                        });
            } catch (Exception finishError) {
                logger.warn("委派 Puppet AI 准备失败收口异常: {}",
                        finishError.getMessage(), finishError);
            }
            context.trace().finish(
                    cancelled ? AiTurnOutcome.CANCELLED : AiTurnOutcome.FAILED,
                    cancelled ? "cancelled" : classification.category(),
                    message);
            telemetryRegistry.record(context.trace());
            return new IllegalStateException(message, error);
        }

        private void refreshRuntime() {
            conversationStore.updateRuntime(
                    context.session().getSessionId(), context.thread(),
                    context.thread().getActiveLeaseToken());
        }

        private void refreshRuntimeSafely() {
            try {
                refreshRuntime();
            } catch (RuntimeException runtimeError) {
                logger.warn("委派 Puppet AI 运行时状态刷新失败: {}",
                        runtimeError.getMessage(), runtimeError);
            }
        }

        private void recordEvent(String name, Object data) {
            AiSseEvent event = context.thread().recordSseEvent(
                    name, data, context.invocationId());
            eventLog.add(event);
            if (context.eventSink() != null) {
                context.eventSink().accept(event);
            }
        }

        private void drainQueuedEvents() {
            AiSseEvent queued;
            while ((queued = context.thread().getAiSseEventQueue().poll()) != null) {
                AiSseEvent event = queued.subagentInvocationId() != null
                        ? queued : queued.withSubagent(context.invocationId());
                eventLog.add(event);
                if (context.eventSink() != null) {
                    context.eventSink().accept(event);
                }
            }
        }

        private Map<String, Object> successResponse(String output) {
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("sessionId", context.session().getSessionId());
            response.put("threadId", context.thread().getThreadId());
            response.put("status", context.thread().getRunStatus());
            response.put("summary", output);
            response.put("traceId", context.trace().traceId());
            if (context.trace().runId() != null) {
                response.put("runId", context.trace().runId());
            }
            return response;
        }

        private String normalizedMessage(Throwable cause) {
            String message = cause != null ? cause.getMessage() : null;
            return message != null && !message.isBlank()
                    ? message : "Puppet AI 执行失败";
        }
    }

    private static Throwable unwrapCompletionError(Throwable error) {
        Throwable current = error;
        while ((current instanceof java.util.concurrent.ExecutionException
                || current instanceof java.util.concurrent.CompletionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    public record Context(PuppetNodeSession session,
                          AiThread thread,
                          AiChatAuditEntry audit,
                          long startedAt,
                          AiTurnTrace trace,
                          String invocationId,
                          Consumer<AiSseEvent> eventSink) {

        public Context {
            Objects.requireNonNull(session, "session");
            Objects.requireNonNull(thread, "thread");
            Objects.requireNonNull(trace, "trace");
        }

        private long elapsedMillis() {
            return Math.max(0L, System.currentTimeMillis() - startedAt);
        }
    }
}
