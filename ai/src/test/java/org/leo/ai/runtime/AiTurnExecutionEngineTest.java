package org.leo.ai.runtime;

import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.PartialResponse;
import dev.langchain4j.model.chat.response.PartialResponseContext;
import dev.langchain4j.model.chat.response.PartialThinking;
import dev.langchain4j.model.chat.response.PartialThinkingContext;
import dev.langchain4j.model.chat.response.PartialToolCall;
import dev.langchain4j.model.chat.response.PartialToolCallContext;
import dev.langchain4j.model.chat.response.StreamingHandle;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.tool.BeforeToolExecution;
import dev.langchain4j.service.tool.ToolExecution;
import org.junit.jupiter.api.Test;
import org.leo.ai.agent.AiToolErrorHandler;
import org.leo.core.ai.AiTurnRuntime;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class AiTurnExecutionEngineTest {

    private final AiTurnCoordinator coordinator = new AiTurnCoordinator();
    private final AiTurnExecutionEngine engine =
            new AiTurnExecutionEngine(new AiToolErrorHandler());

    @Test
    void emitsTransportIndependentDeltasAndCompletesOnlyOnce() {
        RecordingRuntime runtime = claimedRuntime();
        AiTurnCoordinator.Execution turn = coordinator.attach(runtime);
        TestHandle handle = new TestHandle();
        ChatResponse response = mock(ChatResponse.class);
        ScriptedTokenStream stream = new ScriptedTokenStream(tokenStream -> {
            tokenStream.thinking.accept(
                    new PartialThinking("分析"),
                    new PartialThinkingContext(handle));
            tokenStream.response.accept(
                    new PartialResponse("结果"),
                    new PartialResponseContext(handle));
            tokenStream.complete.accept(response);
            tokenStream.error.accept(new IllegalStateException("late error"));
        });
        RecordingListener listener = new RecordingListener();

        engine.execute(command(turn, stream), listener);

        assertEquals(List.of(
                AiTurnEvent.Type.THINKING_DELTA,
                AiTurnEvent.Type.TEXT_DELTA), listener.eventTypes());
        assertEquals(1, listener.completed.get());
        assertEquals(0, listener.failed.get());
        assertEquals("结果", listener.lastResult.output());
        assertEquals(response, listener.lastResult.response());
        assertEquals(AiTurnOutcome.COMPLETED, runtime.outcome);
        assertEquals(1, runtime.clearCount);
    }

    @Test
    void cancelsStreamingHandleEvenWhenItArrivesAfterStopRequest() {
        RecordingRuntime runtime = claimedRuntime();
        AiTurnCoordinator.Execution turn = coordinator.attach(runtime);
        TestHandle handle = new TestHandle();
        ScriptedTokenStream stream = new ScriptedTokenStream(tokenStream -> {
            runtime.requestStop("用户停止");
            tokenStream.response.accept(
                    new PartialResponse("late"),
                    new PartialResponseContext(handle));
            tokenStream.error.accept(new InterruptedException("stopped"));
        });
        RecordingListener listener = new RecordingListener();

        engine.execute(command(turn, stream), listener);

        assertTrue(handle.isCancelled());
        assertEquals(1, listener.failed.get());
        assertTrue(listener.lastFailure.cancelled());
        assertEquals("用户停止", listener.lastFailure.cancellationReason());
        assertEquals(AiTurnOutcome.CANCELLED, runtime.outcome);
    }

    @Test
    void doesNotCreateModelStreamWhenTurnWasAlreadyCancelled() {
        RecordingRuntime runtime = claimedRuntime();
        AiTurnCoordinator.Execution turn = coordinator.attach(runtime);
        runtime.requestStop("启动前停止");
        AtomicBoolean streamCreated = new AtomicBoolean(false);
        RecordingListener listener = new RecordingListener();

        engine.execute(new AiTurnCommand(
                "thread-1", "memory-1", turn, () -> {
                    streamCreated.set(true);
                    return new ScriptedTokenStream(tokenStream -> {});
                }), listener);

        assertFalse(streamCreated.get());
        assertTrue(listener.lastFailure.cancelled());
        assertEquals("启动前停止", listener.lastFailure.cancellationReason());
    }

    @Test
    void cancellationFinishesWithoutProviderCallbackAndRejectsLateTools() {
        RecordingRuntime runtime = claimedRuntime();
        AiTurnCoordinator.Execution turn = coordinator.attach(runtime);
        TestHandle handle = new TestHandle();
        ScriptedTokenStream stream = new ScriptedTokenStream(tokenStream ->
                tokenStream.response.accept(new PartialResponse("partial"),
                        new PartialResponseContext(handle)));
        RecordingListener listener = new RecordingListener();
        engine.execute(command(turn, stream), listener);

        runtime.requestStop("用户取消");

        assertTrue(handle.isCancelled());
        assertEquals(1, listener.failed.get());
        assertFalse(runtime.claimed);
        assertTrue(turn.isCancellationRequested());
        assertThrows(RuntimeException.class, () -> stream.beforeTool.accept(null));
        stream.response.accept(new PartialResponse("late"), new PartialResponseContext(handle));
        stream.complete.accept(mock(ChatResponse.class));
        assertEquals(1, listener.events.size());
        assertEquals(0, listener.completed.get());
        assertEquals(1, runtime.clearCount);
    }

    @Test
    void cancellationUsesLatestModelRequestHandle() {
        RecordingRuntime runtime = claimedRuntime();
        AiTurnCoordinator.Execution turn = coordinator.attach(runtime);
        TestHandle previous = new TestHandle();
        TestHandle current = new TestHandle();
        ScriptedTokenStream stream = new ScriptedTokenStream(tokenStream -> {
            tokenStream.response.accept(new PartialResponse("first"),
                    new PartialResponseContext(previous));
            tokenStream.response.accept(new PartialResponse("second"),
                    new PartialResponseContext(current));
        });
        engine.execute(command(turn, stream), new RecordingListener());

        runtime.requestStop("停止当前请求");

        assertTrue(current.isCancelled());
        assertFalse(previous.isCancelled());
    }

    @Test
    void cancellationWaitsForInFlightEventBeforePersistingTerminalState() throws Exception {
        RecordingRuntime runtime = claimedRuntime();
        AiTurnCoordinator.Execution turn = coordinator.attach(runtime);
        TestHandle handle = new TestHandle();
        ScriptedTokenStream stream = new ScriptedTokenStream(tokenStream -> {});
        CountDownLatch writing = new CountDownLatch(1);
        CountDownLatch releaseEvent = new CountDownLatch(1);
        CountDownLatch terminal = new CountDownLatch(1);
        AiTurnExecutionListener listener = new AiTurnExecutionListener() {
            @Override
            public void onEvent(AiTurnEvent event) {
                writing.countDown();
                try {
                    if (!releaseEvent.await(5, TimeUnit.SECONDS)) throw new AssertionError("event blocked");
                } catch (InterruptedException error) {
                    throw new IllegalStateException(error);
                }
            }

            @Override public void onCompleted(AiTurnResult result) { }
            @Override public void onFailed(AiTurnFailure failure) { terminal.countDown(); }
        };
        engine.execute(command(turn, stream), listener);
        var workers = Executors.newFixedThreadPool(2);
        try {
            var event = workers.submit(() -> stream.response.accept(
                    new PartialResponse("partial"), new PartialResponseContext(handle)));
            assertTrue(writing.await(5, TimeUnit.SECONDS));
            var cancellation = workers.submit(() -> runtime.requestStop("用户停止"));
            assertTrue(handle.cancelledSignal.await(5, TimeUnit.SECONDS));
            assertFalse(terminal.await(100, TimeUnit.MILLISECONDS));
            releaseEvent.countDown();
            event.get(5, TimeUnit.SECONDS);
            cancellation.get(5, TimeUnit.SECONDS);
            assertEquals(0, terminal.getCount());
        } finally {
            releaseEvent.countDown();
            workers.shutdownNow();
        }
    }

    @Test
    void convertsSynchronousStartFailureIntoFailedTurn() {
        RecordingRuntime runtime = claimedRuntime();
        AiTurnCoordinator.Execution turn = coordinator.attach(runtime);
        RecordingListener listener = new RecordingListener();

        engine.execute(command(turn, new ScriptedTokenStream(tokenStream -> {
            throw new IllegalStateException("start failed");
        })), listener);

        assertEquals(1, listener.failed.get());
        assertEquals("start failed", listener.lastFailure.cause().getMessage());
        assertEquals(AiTurnOutcome.FAILED, runtime.outcome);
        assertFalse(runtime.claimed);
    }

    @Test
    void continuesOnceFromCurrentMemoryWhenProviderReturnsNoValue() {
        RecordingRuntime runtime = claimedRuntime();
        AiTurnCoordinator.Execution turn = coordinator.attach(runtime);
        AtomicInteger primaryCalls = new AtomicInteger();
        AtomicInteger recoveryCalls = new AtomicInteger();
        RecordingListener listener = new RecordingListener();
        ScriptedTokenStream primary = new ScriptedTokenStream(tokenStream -> {
            tokenStream.response.accept(
                    new PartialResponse("已完成资料收集。"),
                    new PartialResponseContext(new TestHandle()));
            tokenStream.error.accept(new NoSuchElementException("No value present"));
        });
        ScriptedTokenStream recovery = new ScriptedTokenStream(tokenStream -> {
            tokenStream.response.accept(
                    new PartialResponse("继续并完成结论。"),
                    new PartialResponseContext(new TestHandle()));
            tokenStream.complete.accept(mock(ChatResponse.class));
        });

        engine.execute(new AiTurnCommand(
                "thread-1", "memory-1", turn,
                () -> {
                    primaryCalls.incrementAndGet();
                    return primary;
                },
                () -> {
                    recoveryCalls.incrementAndGet();
                    return recovery;
                }), listener);

        assertEquals(1, primaryCalls.get());
        assertEquals(1, recoveryCalls.get());
        assertEquals(1, listener.completed.get());
        assertEquals(0, listener.failed.get());
        assertEquals("已完成资料收集。继续并完成结论。", listener.lastResult.output());
        assertTrue(listener.lastResult.streamRecovered());
        assertEquals(AiTurnOutcome.COMPLETED, runtime.outcome);
    }

    @Test
    void stopsAfterOneNoValueRecoveryAttempt() {
        RecordingRuntime runtime = claimedRuntime();
        AiTurnCoordinator.Execution turn = coordinator.attach(runtime);
        AtomicInteger primaryCalls = new AtomicInteger();
        AtomicInteger recoveryCalls = new AtomicInteger();
        RecordingListener listener = new RecordingListener();
        ScriptedTokenStream primary = new ScriptedTokenStream(tokenStream ->
                tokenStream.error.accept(new NoSuchElementException("No value present")));
        ScriptedTokenStream recovery = new ScriptedTokenStream(tokenStream ->
                tokenStream.error.accept(new NoSuchElementException("No value present")));

        engine.execute(new AiTurnCommand(
                "thread-1", "memory-1", turn,
                () -> {
                    primaryCalls.incrementAndGet();
                    return primary;
                },
                () -> {
                    recoveryCalls.incrementAndGet();
                    return recovery;
                }), listener);

        assertEquals(1, primaryCalls.get());
        assertEquals(1, recoveryCalls.get());
        assertEquals(0, listener.completed.get());
        assertEquals(1, listener.failed.get());
        assertEquals(AiTurnOutcome.FAILED, runtime.outcome);
    }

    @Test
    void rejectsClaimedQuestionCardWhenControlToolWasNotCalled() {
        RecordingRuntime runtime = claimedRuntime();
        AiTurnCoordinator.Execution turn = coordinator.attach(runtime);
        RecordingListener listener = new RecordingListener();
        ScriptedTokenStream stream = new ScriptedTokenStream(tokenStream -> {
            tokenStream.response.accept(
                    new PartialResponse("请选择混淆策略（已发送提问卡片）"),
                    new PartialResponseContext(new TestHandle()));
            tokenStream.complete.accept(mock(ChatResponse.class));
        });

        engine.execute(command(turn, stream), listener);

        assertEquals(0, listener.completed.get());
        assertEquals(1, listener.failed.get());
        assertTrue(listener.lastFailure.cause().getMessage()
                .startsWith("CONTROL_ACTION_NOT_EMITTED"));
        assertEquals(AiTurnOutcome.FAILED, runtime.outcome);
    }

    @Test
    void reportsTerminalPersistenceFailureAndMarksRuntimeFailed() {
        RecordingRuntime runtime = claimedRuntime();
        AiTurnCoordinator.Execution turn = coordinator.attach(runtime);
        RecordingListener listener = new RecordingListener();
        listener.failCompletion = true;

        engine.execute(command(turn, new ScriptedTokenStream(tokenStream ->
                tokenStream.complete.accept(mock(ChatResponse.class)))), listener);

        assertEquals(1, listener.terminalFailures.get());
        assertEquals(AiTurnOutcome.COMPLETED, listener.attemptedOutcome);
        assertEquals(AiTurnOutcome.FAILED, runtime.outcome);
        assertFalse(runtime.claimed);
    }

    private RecordingRuntime claimedRuntime() {
        RecordingRuntime runtime = new RecordingRuntime();
        assertTrue(coordinator.tryClaim(runtime));
        return runtime;
    }

    private AiTurnCommand command(AiTurnCoordinator.Execution turn, TokenStream stream) {
        return new AiTurnCommand("thread-1", "memory-1", turn, () -> stream);
    }

    private static final class RecordingListener implements AiTurnExecutionListener {
        private final List<AiTurnEvent> events = new ArrayList<>();
        private final AtomicInteger completed = new AtomicInteger();
        private final AtomicInteger failed = new AtomicInteger();
        private final AtomicInteger terminalFailures = new AtomicInteger();
        private AiTurnFailure lastFailure;
        private AiTurnResult lastResult;
        private AiTurnOutcome attemptedOutcome;
        private boolean failCompletion;

        @Override
        public void onEvent(AiTurnEvent event) {
            events.add(event);
        }

        @Override
        public void onCompleted(AiTurnResult result) {
            completed.incrementAndGet();
            lastResult = result;
            if (failCompletion) {
                throw new IllegalStateException("database unavailable");
            }
        }

        @Override
        public void onFailed(AiTurnFailure failure) {
            failed.incrementAndGet();
            lastFailure = failure;
        }

        @Override
        public void onTerminalFailure(AiTurnOutcome attemptedOutcome, Exception error) {
            terminalFailures.incrementAndGet();
            this.attemptedOutcome = attemptedOutcome;
        }

        private List<AiTurnEvent.Type> eventTypes() {
            return events.stream().map(AiTurnEvent::type).toList();
        }
    }

    private static final class ScriptedTokenStream implements TokenStream {
        private final Consumer<ScriptedTokenStream> script;
        private BiConsumer<PartialResponse, PartialResponseContext> response = (value, context) -> {};
        private BiConsumer<PartialThinking, PartialThinkingContext> thinking = (value, context) -> {};
        private BiConsumer<PartialToolCall, PartialToolCallContext> partialTool = (value, context) -> {};
        private Consumer<BeforeToolExecution> beforeTool = value -> {};
        private Consumer<ToolExecution> toolExecuted = value -> {};
        private Consumer<ChatResponse> complete = value -> {};
        private Consumer<Throwable> error = value -> {};

        private ScriptedTokenStream(Consumer<ScriptedTokenStream> script) {
            this.script = script;
        }

        @Override
        public TokenStream onPartialResponse(Consumer<String> consumer) {
            return this;
        }

        @Override
        public TokenStream onPartialResponseWithContext(
                BiConsumer<PartialResponse, PartialResponseContext> consumer) {
            response = consumer;
            return this;
        }

        @Override
        public TokenStream onPartialThinking(Consumer<PartialThinking> consumer) {
            return this;
        }

        @Override
        public TokenStream onPartialThinkingWithContext(
                BiConsumer<PartialThinking, PartialThinkingContext> consumer) {
            thinking = consumer;
            return this;
        }

        @Override
        public TokenStream onPartialToolCall(Consumer<PartialToolCall> consumer) {
            return this;
        }

        @Override
        public TokenStream onPartialToolCallWithContext(
                BiConsumer<PartialToolCall, PartialToolCallContext> consumer) {
            partialTool = consumer;
            return this;
        }

        @Override
        public TokenStream onRetrieved(Consumer<List<Content>> consumer) {
            return this;
        }

        @Override
        public TokenStream onIntermediateResponse(Consumer<ChatResponse> consumer) {
            return this;
        }

        @Override
        public TokenStream beforeToolExecution(Consumer<BeforeToolExecution> consumer) {
            beforeTool = consumer;
            return this;
        }

        @Override
        public TokenStream onToolExecuted(Consumer<ToolExecution> consumer) {
            toolExecuted = consumer;
            return this;
        }

        @Override
        public TokenStream onCompleteResponse(Consumer<ChatResponse> consumer) {
            complete = consumer;
            return this;
        }

        @Override
        public TokenStream onError(Consumer<Throwable> consumer) {
            error = consumer;
            return this;
        }

        @Override
        public TokenStream ignoreErrors() {
            return this;
        }

        @Override
        public void start() {
            script.accept(this);
        }
    }

    private static final class TestHandle implements StreamingHandle {
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private final CountDownLatch cancelledSignal = new CountDownLatch(1);

        @Override
        public void cancel() {
            cancelled.set(true);
            cancelledSignal.countDown();
        }

        @Override
        public boolean isCancelled() {
            return cancelled.get();
        }
    }

    private static final class RecordingRuntime implements AiTurnRuntime {
        private boolean claimed;
        private boolean stopRequested;
        private String stopReason;
        private Runnable stopCallback;
        private AiTurnOutcome outcome;
        private int clearCount;

        @Override
        public boolean claimExecution() {
            if (claimed) return false;
            claimed = true;
            stopRequested = false;
            stopReason = null;
            return true;
        }

        @Override
        public void markExecuting(Thread thread) {
            claimed = true;
        }

        @Override
        public void clearExecuting() {
            claimed = false;
            stopRequested = false;
            stopCallback = null;
            clearCount++;
        }

        @Override
        public boolean isStopRequested() {
            return stopRequested;
        }

        @Override
        public String getStopReason() {
            return stopReason;
        }

        @Override
        public void setStopCallback(Runnable callback) {
            stopCallback = callback;
        }

        @Override
        public void markCompleted() {
            outcome = AiTurnOutcome.COMPLETED;
        }

        @Override
        public void markFailed() {
            outcome = AiTurnOutcome.FAILED;
        }

        @Override
        public void markCancelled() {
            outcome = AiTurnOutcome.CANCELLED;
        }

        private void requestStop(String reason) {
            stopRequested = true;
            stopReason = reason;
            if (stopCallback != null) {
                stopCallback.run();
            }
        }
    }
}
