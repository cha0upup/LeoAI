package org.leo.ai.runtime;

import dev.langchain4j.service.memory.ChatMemoryAccess;
import org.junit.jupiter.api.Test;
import org.leo.ai.channel.AiModelFailoverService;
import org.leo.ai.memory.ManagedConversationMemory;
import org.leo.ai.service.AiErrorClassifier;
import org.leo.ai.thread.AiConversationStoreService;
import org.leo.core.entity.AiChatAuditEntry;
import org.leo.core.entity.AiRuntimeStats;
import org.leo.core.entity.AiSseEvent;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiTurnTransactionTest {
    private final AiConversationStoreService store = mock(AiConversationStoreService.class);
    private final AiModelFailoverService failover = mock(AiModelFailoverService.class);
    private final ManagedConversationMemory memory = mock(ManagedConversationMemory.class);
    private final ChatMemoryAccess agent = mock(ChatMemoryAccess.class);
    private final AiConversationStoreService.PersistedTurn persistedTurn =
            new AiConversationStoreService.PersistedTurn(
                    "turn-1", "run-1", "thread-1", "message-1", "assistant-message-1", 1L, null);
    private final AiTurnTransaction.Session session = openSession();

    @Test
    void commitsExactlyOnceAndReturnsTheSameResult() {
        AiTurnResult result = new AiTurnResult("done", null, false, false);

        AiTurnTransaction.CompletedTurn first =
                session.commit(result, List.of(), null, new AiRuntimeStats());
        AiTurnTransaction.CompletedTurn repeated =
                session.commit(result, List.of(), null, new AiRuntimeStats());

        assertSame(first, repeated);
        assertEquals("done", first.output());
        assertTrue(session.isTerminal());
        verify(store, times(1)).completeTurn(
                eq(persistedTurn), eq("done"), any(), any(), any(), eq(0));
        verify(failover).recordSuccess(7);
        verify(store, never()).discardTurn(
                any(), any(), any(),
                any(), any(), anyInt(),
                any(), any(), any(),
                any());
    }

    @Test
    void discardsFailureExactlyOnceAndAlwaysRebuildsMemory() {
        AiTurnFailure failure = new AiTurnFailure(
                new RuntimeException("network down"), AiTurnOutcome.FAILED, null);

        AiTurnTransaction.FailedTurn first =
                session.discard(failure, List.of(), null);
        AiTurnTransaction.FailedTurn repeated =
                session.discard(failure, List.of(), null);

        assertSame(first, repeated);
        assertEquals("network", first.classification().category());
        verify(store, times(1)).discardTurn(
                eq(persistedTurn), eq("failed"), eq("network"),
                any(), eq("network down"), eq(0),
                eq(""), eq(List.of()), eq(null),
                eq(""));
        verify(memory).rebuild(agent, "session:thread-1");
        verify(failover).recordFailure(eq(7), any());
    }

    @Test
    void rebuildsMemoryEvenWhenDiscardPersistenceFails() {
        doThrow(new IllegalStateException("db unavailable"))
                .when(store).discardTurn(
                any(), any(), any(),
                any(), any(), anyInt(),
                any(), any(), any(),
                any());

        assertThrows(IllegalStateException.class,
                () -> session.discard(new AiTurnFailure(
                        new RuntimeException("boom"), AiTurnOutcome.FAILED, null), List.of(), null));

        verify(memory).rebuild(agent, "session:thread-1");
        assertFalse(session.isTerminal());
    }

    @Test
    void terminalRecoveryDoesNotDiscardAnAlreadyCommittedTurn() {
        session.commit(
                new AiTurnResult("done", null, false, false), List.of(), null, null);

        boolean recovered = session.recoverTerminalFailure(
                AiTurnOutcome.COMPLETED, new IllegalStateException("downstream"));

        assertFalse(recovered);
        verify(store, never()).discardTurn(
                any(), any(), any(),
                any(), any(), anyInt(),
                any(), any(), any(),
                any());
    }

    @Test
    void rebuildsCanonicalMemoryAfterARecoveredStreamCommits() {
        AiTurnTransaction.CompletedTurn completed = session.commit(
                new AiTurnResult("done", null, false, true),
                List.of(), null, null);

        verify(memory).rebuild(agent, "session:thread-1");
        assertEquals(true, completed.review().get("streamRecovered"));
    }

    @Test
    void discardsCancellationWithoutPenalizingTheModel() {
        AiTurnTransaction.FailedTurn failed = session.discard(
                new AiTurnFailure(new InterruptedException("stopped"),
                        AiTurnOutcome.CANCELLED, "用户停止"), List.of(), null);

        assertTrue(failed.cancelled());
        assertEquals(AiTurnOutcome.CANCELLED, failed.outcome());
        assertEquals("cancelled", failed.status());
        assertEquals("用户停止", failed.message());
        verify(store).discardTurn(
                eq(persistedTurn), eq("cancelled"), eq("cancelled"),
                eq("用户停止"), eq("用户停止"), eq(0),
                eq(""), eq(List.of()), eq(null),
                eq(""));
        verify(failover, never()).recordFailure(any(), any());
        verify(memory).rebuild(agent, "session:thread-1");
    }

    @Test
    void planOnlyFailureNeverPersistsTheRecoverySnapshotAsVisibleText() {
        var plan = Map.of("kind", "plan", "step", "fixture step");
        var events = List.of(new AiSseEvent("node", plan));
        session.discard(new AiTurnFailure(new RuntimeException("network down"), AiTurnOutcome.FAILED, null), events, plan);
        verify(store).discardTurn(
                eq(persistedTurn), eq("failed"), eq("network"),
                any(), any(), eq(0),
                eq(""), any(), eq(plan),
                eq(AiTurnRecoveryContext.build(events, plan)));
    }

    @Test
    void persistsInterruptedAssistantItemWithPartialOutput() {
        List<AiSseEvent> events = List.of(
                new AiSseEvent("delta", "停止前内容"),
                new AiSseEvent("node", Map.of(
                        "kind", "text", "content", "停止前内容")));

        session.discard(
                new AiTurnFailure(
                        new InterruptedException("stopped"),
                        AiTurnOutcome.CANCELLED,
                        "用户停止"),
                events,
                null);

        verify(store).discardTurn(
                eq(persistedTurn), eq("cancelled"), eq("cancelled"),
                eq("用户停止"), eq("用户停止"), eq(0),
                eq("停止前内容"), any(), eq(null),
                eq("停止前内容\n\n[执行在此处中断；以下是可用于继续任务的已完成进度]"
                        + "\n后续收到“继续”时，从上述进度衔接，不重复已经完成的步骤。"));
    }

    @Test
    void recoversCommitPersistenceFailureExactlyOnce() {
        doThrow(new IllegalStateException("commit unavailable"))
                .when(store).completeTurn(any(), any(), any(), any(), any(), anyInt());

        assertThrows(IllegalStateException.class, () -> session.commit(
                new AiTurnResult("done", null, false, false), List.of(), null, null));

        assertTrue(session.recoverTerminalFailure(
                AiTurnOutcome.COMPLETED, new IllegalStateException("commit unavailable")));
        assertFalse(session.recoverTerminalFailure(
                AiTurnOutcome.COMPLETED, new IllegalStateException("repeated")));
        assertEquals("persistence", session.failedTurn().classification().category());
        verify(store, times(1)).discardTurn(
                eq(persistedTurn), eq("failed"), eq("persistence"),
                eq("AI 回复提交失败"), eq("commit unavailable"), eq(0),
                eq(""), eq(List.of()), eq(null),
                eq(""));
        verify(memory).rebuild(agent, "session:thread-1");
    }

    @Test
    void postCommitHookFailureCannotUndoTheCommittedTurn() {
        doThrow(new IllegalStateException("health registry unavailable"))
                .when(failover).recordSuccess(7);

        AiTurnTransaction.CompletedTurn completed = session.commit(
                new AiTurnResult("done", null, false, false), List.of(), null, null);

        assertEquals("done", completed.output());
        assertSame(completed, session.completedTurn());
        assertTrue(session.isTerminal());
        assertFalse(session.recoverTerminalFailure(
                AiTurnOutcome.COMPLETED, new IllegalStateException("downstream")));
        verify(store, never()).discardTurn(
                any(), any(), any(),
                any(), any(), anyInt(),
                any(), any(), any(),
                any());
    }

    private AiTurnTransaction.Session openSession() {
        AiErrorClassifier classifier = mock(AiErrorClassifier.class);
        when(classifier.classify(any(Throwable.class))).thenReturn(
                new AiErrorClassifier.Classification(
                        "network", "网络连接失败", "network down", List.of()));
        when(classifier.classifyCategory(eq("persistence"), any())).thenReturn(
                new AiErrorClassifier.Classification(
                        "persistence", "AI 回复提交失败", "commit unavailable", List.of()));
        AiTurnTransaction transaction = new AiTurnTransaction(
                store, failover, classifier, new AiTurnArtifacts(), memory);
        AiTurnTrace trace = AiTurnTrace.testTrace(
                "trace-1", "test", persistedTurn.threadId(), persistedTurn.startedAt());
        trace.bind(persistedTurn.turnId(), persistedTurn.runId());
        return transaction.open(new AiTurnTransaction.Context(
                persistedTurn, 7, agent, "session:thread-1",
                AiChatAuditEntry.platform("user-1", "alice", "normal", "hello"),
                System.currentTimeMillis(), trace));
    }
}
