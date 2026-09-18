package org.leo.ai.runtime;

import dev.langchain4j.model.chat.response.StreamingHandle;
import dev.langchain4j.service.TokenStream;
import org.leo.ai.agent.AiToolCatalog;
import org.leo.ai.agent.AiToolErrorHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * 平台 AI、节点 AI 和委派任务共用的流式 Turn 执行引擎。
 *
 * <p>负责回调注册、停止句柄竞态、工具事件标准化与唯一终态仲裁；
 * 不依赖 HTTP、SSE、Session 或具体 Agent 类型。
 */
@Component
public class AiTurnExecutionEngine {

    private static final Logger logger = LoggerFactory.getLogger(AiTurnExecutionEngine.class);
    private static final String REQUEST_USER_INPUT = "request_user_input";
    private static final int MAX_RECOVERY_ATTEMPTS = 1;
    private static final Pattern USER_INPUT_CARD_CLAIM = Pattern.compile(
            "(?:已|已经|刚刚)(?:向你)?(?:成功)?(?:发送|发出|展示|创建).{0,12}(?:提问|问题|确认|选择)卡片"
                    + "|(?:提问|问题|确认|选择)卡片.{0,8}(?:已|已经)(?:成功)?(?:发送|发出|展示|创建)");

    private final AiToolErrorHandler toolErrorHandler;
    private final AiToolEventNormalizer toolEvents;

    @Autowired
    public AiTurnExecutionEngine(AiToolErrorHandler toolErrorHandler,
                                 AiToolEventNormalizer toolEvents) {
        this.toolErrorHandler =
                Objects.requireNonNull(toolErrorHandler, "toolErrorHandler");
        this.toolEvents = Objects.requireNonNull(toolEvents, "toolEvents");
    }

    AiTurnExecutionEngine(AiToolErrorHandler toolErrorHandler) {
        this(toolErrorHandler, new AiToolEventNormalizer(new AiToolCatalog()));
    }

    void execute(AiTurnCommand command, AiTurnExecutionListener listener) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(listener, "listener");

        AiTurnCoordinator.Execution turn = command.execution();
        AiToolErrorHandler.TurnScope toolErrorScope =
                toolErrorHandler.beginTurn(command.memoryId());
        AtomicReference<StreamingHandle> handleRef = new AtomicReference<>();
        AtomicBoolean userInputRequested = new AtomicBoolean(false);
        AtomicInteger recoveryAttempts = new AtomicInteger();
        StringBuilder output = new StringBuilder();
        turn.registerCancellation(() -> {
            try {
                cancelCaptured(handleRef);
            } finally {
                fail(turn, listener,
                        new InterruptedException(turn.cancellationReason()), toolErrorScope);
            }
        });
        if (turn.isCancellationRequested()) {
            fail(turn, listener,
                    new InterruptedException(turn.cancellationReason()),
                    toolErrorScope);
            return;
        }

        startStream(command, listener, turn, toolErrorScope, handleRef,
                userInputRequested, recoveryAttempts, output, false);
    }

    private void startStream(AiTurnCommand command,
                             AiTurnExecutionListener listener,
                             AiTurnCoordinator.Execution turn,
                             AiToolErrorHandler.TurnScope toolErrorScope,
                             AtomicReference<StreamingHandle> handleRef,
                             AtomicBoolean userInputRequested,
                             AtomicInteger recoveryAttempts,
                             StringBuilder output,
                             boolean recovery) {
        try {
            TokenStream stream = Objects.requireNonNull(
                    (recovery ? command.recoveryStreamFactory()
                            : command.streamFactory()).get(),
                    (recovery ? "recoveryStreamFactory" : "streamFactory")
                            + " 返回了 null");
            stream
                    .onPartialThinkingWithContext((thinking, context) -> {
                        capture(handleRef, context.streamingHandle(), turn);
                        whileActive(turn, () -> listener.onEvent(AiTurnEvent.thinkingDelta(thinking.text())));
                    })
                    .onPartialResponseWithContext((partial, context) -> {
                        capture(handleRef, context.streamingHandle(), turn);
                        whileActive(turn, () -> {
                            if (partial.text() != null) output.append(partial.text());
                            listener.onEvent(AiTurnEvent.textDelta(partial.text()));
                        });
                    })
                    .onPartialToolCallWithContext((partial, context) -> {
                        capture(handleRef, context.streamingHandle(), turn);
                        whileActive(turn, () -> listener.onEvent(AiTurnEvent.toolCallDelta(
                                toolEvents.partial(partial))));
                    })
                    .beforeToolExecution(execution -> {
                        synchronized (turn) {
                            rejectToolWhenCancelled(turn);
                            listener.onEvent(AiTurnEvent.toolStarted(toolEvents.started(execution)));
                        }
                    })
                    .onToolExecuted(execution -> whileActive(turn, () -> {
                        listener.onEvent(AiTurnEvent.toolCompleted(
                                toolEvents.completed(execution)));
                        if (execution != null
                                && execution.request() != null
                                && REQUEST_USER_INPUT.equals(execution.request().name())
                                && !AiToolErrorHandler.isErrorResult(execution)) {
                            userInputRequested.set(true);
                        }
                    }))
                    .onCompleteResponse(response -> whileActive(turn, () -> {
                        if (turn.isCancellationRequested()) {
                            fail(turn, listener, new InterruptedException(turn.cancellationReason()),
                                    toolErrorScope);
                            return;
                        }
                        String modelOutput = output.toString();
                        if (!userInputRequested.get()
                                && claimsUserInputCardWasSent(modelOutput)) {
                            fail(turn, listener, new IllegalStateException(
                                            "CONTROL_ACTION_NOT_EMITTED: 模型声称已发送提问卡片，"
                                                    + "但本轮未成功调用 request_user_input"),
                                    toolErrorScope);
                            return;
                        }
                        // request_user_input 的结构化卡片是本轮唯一可见结果。
                        // 模型围绕卡片生成的复述不进入持久化正文。
                        String finalOutput = userInputRequested.get() ? "" : modelOutput;
                        complete(turn, listener, new AiTurnResult(
                                        finalOutput, response, System.currentTimeMillis(),
                                        userInputRequested.get(),
                                        recoveryAttempts.get() > 0),
                                toolErrorScope);
                    }))
                    .onError(error -> {
                        if (tryRecoverStream(
                                command, listener, turn, toolErrorScope,
                                handleRef, userInputRequested, recoveryAttempts,
                                output, error)) {
                            return;
                        }
                        fail(turn, listener, error, toolErrorScope);
                    })
                    .start();
        } catch (Throwable error) {
            if (tryRecoverStream(
                    command, listener, turn, toolErrorScope,
                    handleRef, userInputRequested, recoveryAttempts,
                    output, error)) {
                return;
            }
            fail(turn, listener, error, toolErrorScope);
        }
    }

    private boolean tryRecoverStream(AiTurnCommand command,
                                     AiTurnExecutionListener listener,
                                     AiTurnCoordinator.Execution turn,
                                     AiToolErrorHandler.TurnScope toolErrorScope,
                                     AtomicReference<StreamingHandle> handleRef,
                                     AtomicBoolean userInputRequested,
                                     AtomicInteger recoveryAttempts,
                                     StringBuilder output,
                                     Throwable error) {
        if (turn.isFinished() || turn.isCancellationRequested()
                || !isRecoverableStreamError(error)
                || !recoveryAttempts.compareAndSet(0, 1)) {
            return false;
        }
        handleRef.set(null);
        logger.warn("模型流返回不完整响应，自动从当前记忆续接, conversationId={}, attempt={}: {}",
                command.conversationId(), MAX_RECOVERY_ATTEMPTS,
                rootMessage(error));
        startStream(command, listener, turn, toolErrorScope, handleRef,
                userInputRequested, recoveryAttempts, output, true);
        return true;
    }

    private static boolean isRecoverableStreamError(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof NoSuchElementException) return true;
            String message = current.getMessage();
            if (message != null) {
                String normalized = message.trim().toLowerCase(java.util.Locale.ROOT);
                if ("no value present".equals(normalized)
                        || normalized.contains("chatcompletion$choice.message() is null")
                        || normalized.contains("chatcompletionmessage.role()")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        String message = null;
        while (current != null) {
            if (current.getMessage() != null && !current.getMessage().isBlank()) {
                message = current.getMessage();
            }
            current = current.getCause();
        }
        return message != null ? message : error != null
                ? error.getClass().getSimpleName() : "unknown";
    }

    private void complete(AiTurnCoordinator.Execution turn,
                          AiTurnExecutionListener listener,
                          AiTurnResult result,
                          AiToolErrorHandler.TurnScope toolErrorScope) {
        synchronized (turn) {
            try {
                turn.finish(AiTurnOutcome.COMPLETED, () -> listener.onCompleted(result));
            } catch (Throwable error) {
                notifyTerminalFailure(listener, AiTurnOutcome.COMPLETED, asException(error));
            } finally {
                toolErrorScope.close();
            }
        }
    }

    /** 流式回调和外部取消串行提交事件，避免持久化终态时事件列表仍在写入。 */
    private void whileActive(AiTurnCoordinator.Execution turn, Runnable action) {
        synchronized (turn) {
            if (!turn.isFinished()) action.run();
        }
    }

    private static boolean claimsUserInputCardWasSent(String output) {
        return output != null && USER_INPUT_CARD_CLAIM.matcher(output).find();
    }

    private void fail(AiTurnCoordinator.Execution turn,
                      AiTurnExecutionListener listener,
                      Throwable error,
                      AiToolErrorHandler.TurnScope toolErrorScope) {
        synchronized (turn) {
            if (turn.isFinished()) {
                toolErrorScope.close();
                return;
            }
            AiTurnOutcome outcome = turn.isCancellation(error)
                    ? AiTurnOutcome.CANCELLED : AiTurnOutcome.FAILED;
            AiTurnFailure failure = new AiTurnFailure(
                    error, outcome, outcome == AiTurnOutcome.CANCELLED
                            ? turn.cancellationReason() : null);
            try {
                turn.finish(outcome, () -> listener.onFailed(failure));
            } catch (Throwable terminalError) {
                notifyTerminalFailure(listener, outcome, asException(terminalError));
            } finally {
                toolErrorScope.close();
            }
        }
    }

    private void notifyTerminalFailure(AiTurnExecutionListener listener,
                                       AiTurnOutcome attemptedOutcome,
                                       Exception error) {
        try {
            listener.onTerminalFailure(attemptedOutcome, error);
        } catch (Throwable recoveryError) {
            logger.warn("AI Turn 最终恢复动作失败: {}", recoveryError.getMessage(), recoveryError);
        }
    }

    private Exception asException(Throwable error) {
        return error instanceof Exception exception
                ? exception
                : new IllegalStateException("AI Turn 终态动作发生严重错误", error);
    }

    private void rejectToolWhenCancelled(AiTurnCoordinator.Execution turn) {
        if (turn.isFinished() || turn.isCancellationRequested() || Thread.currentThread().isInterrupted()) {
            throw new RuntimeException(new InterruptedException(turn.cancellationReason()));
        }
    }

    private void capture(AtomicReference<StreamingHandle> handleRef,
                         StreamingHandle candidate,
                         AiTurnCoordinator.Execution turn) {
        if (candidate == null) return;
        handleRef.set(candidate);
        if (turn.isCancellationRequested()) {
            candidate.cancel();
        }
    }

    private void cancelCaptured(AtomicReference<StreamingHandle> handleRef) {
        StreamingHandle handle = handleRef.get();
        if (handle != null) {
            handle.cancel();
        }
    }
}
