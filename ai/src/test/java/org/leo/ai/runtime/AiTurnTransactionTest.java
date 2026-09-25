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

    @Test
    void commitsExactlyOnceAndReturnsTheSameResult() {
        Fixture fixture = fixture();
        AiTurnTransaction.Session session = fixture.session();
        AiTurnResult result = new AiTurnResult("done", null, System.currentTimeMillis());

        AiTurnTransaction.CompletedTurn first =
                session.commit(result, List.of(), null, new AiRuntimeStats());
        AiTurnTransaction.CompletedTurn repeated =
                session.commit(result, List.of(), null, new AiRuntimeStats());

        assertSame(first, repeated);
        assertEquals("done", first.output());
        assertTrue(session.isTerminal());
        verify(fixture.store, times(1)).completeTurn(
                eq(fixture.persistedTurn), eq("done"), any(), any(), any(), eq(0));
        verify(fixture.failover).recordSuccess(7);
        verify(fixture.store, never()).discardTurn(
                any(), any(), any(), any(), any(), anyInt(), any(), any(), any());
    }

    @Test
    void discardsFailureExactlyOnceAndAlwaysRebuildsMemory() {
        Fixture fixture = fixture();
        AiTurnFailure failure = new AiTurnFailure(
                new RuntimeException("network down"), AiTurnOutcome.FAILED, null);

        AiTurnTransaction.FailedTurn first =
                fixture.session().discard(failure, List.of(), null);
        AiTurnTransaction.FailedTurn repeated =
                fixture.session().discard(failure, List.of(), null);

        assertSame(first, repeated);
        assertEquals("network", first.classification().category());
        verify(fixture.store, times(1)).discardTurn(
                eq(fixture.persistedTurn), eq("failed"), eq("network"),
                any(), eq("network down"), eq(0), eq(""), eq(List.of()), eq(null));
        verify(fixture.memory).rebuild(fixture.agent, "session:thread-1");
        verify(fixture.failover).recordFailure(eq(7), any());
    }

    @Test
    void rebuildsMemoryEvenWhenDiscardPersistenceFails() {
        Fixture fixture = fixture();
        doThrow(new IllegalStateException("db unavailable"))
                .when(fixture.store).discardTurn(
                        any(), any(), any(), any(), any(), anyInt(), any(), any(), any());

        assertThrows(IllegalStateException.class,
                () -> fixture.session().discard(new AiTurnFailure(
                        new RuntimeException("boom"), AiTurnOutcome.FAILED, null), List.of(), null));

        verify(fixture.memory).rebuild(fixture.agent, "session:thread-1");
        assertFalse(fixture.session().isTerminal());
    }

    @Test
    void terminalRecoveryDoesNotDiscardAnAlreadyCommittedTurn() {
        Fixture fixture = fixture();
        fixture.session().commit(
                new AiTurnResult("done", null, 1L), List.of(), null, null);

        boolean recovered = fixture.session().recoverTerminalFailure(
                AiTurnOutcome.COMPLETED, new IllegalStateException("downstream"));

        assertFalse(recovered);
        verify(fixture.store, never()).discardTurn(
                any(), any(), any(), any(), any(), anyInt(), any(), any(), any());
    }

    @Test
    void rebuildsCanonicalMemoryAfterARecoveredStreamCommits() {
        Fixture fixture = fixture();

        AiTurnTransaction.CompletedTurn completed = fixture.session().commit(
                new AiTurnResult("done", null, 1L, false, true),
                List.of(), null, null);

        verify(fixture.memory).rebuild(fixture.agent, "session:thread-1");
        assertEquals(true, completed.review().get("streamRecovered"));
    }

    @Test
    void discardsCancellationWithoutPenalizingTheModel() {
        Fixture fixture = fixture();

        AiTurnTransaction.FailedTurn failed = fixture.session().discard(
                new AiTurnFailure(new InterruptedException("stopped"),
                        AiTurnOutcome.CANCELLED, "用户停止"), List.of(), null);

        assertTrue(failed.cancelled());
        assertEquals(AiTurnOutcome.CANCELLED, failed.outcome());
        assertEquals("cancelled", failed.status());
        assertEquals("用户停止", failed.message());
        verify(fixture.store).discardTurn(
                eq(fixture.persistedTurn), eq("cancelled"), eq("cancelled"),
                eq("用户停止"), eq("用户停止"), eq(0), eq(""), eq(List.of()), eq(null));
        verify(fixture.failover, never()).recordFailure(any(), any());
        verify(fixture.memory).rebuild(fixture.agent, "session:thread-1");
    }

    @Test
    void persistsInterruptedAssistantItemWithPartialOutput() {
        Fixture fixture = fixture();
        List<AiSseEvent> events = List.of(
                new AiSseEvent("delta", "停止前内容"),
                new AiSseEvent("node", Map.of(
                        "kind", "text", "content", "停止前内容")));

        fixture.session().discard(
                new AiTurnFailure(
                        new InterruptedException("stopped"),
                        AiTurnOutcome.CANCELLED,
                        "用户停止"),
                events,
                null);

        verify(fixture.store).discardTurn(
                eq(fixture.persistedTurn), eq("cancelled"), eq("cancelled"),
                eq("用户停止"), eq("用户停止"), eq(0),
                eq("停止前内容\n\n[执行在此处中断；以下是可用于继续任务的已完成进度]"
                        + "\n后续收到“继续”时，从上述进度衔接，不重复已经完成的步骤。"),
                any(), eq(null));
    }

    @Test
    void recoversCommitPersistenceFailureExactlyOnce() {
        Fixture fixture = fixture();
        doThrow(new IllegalStateException("commit unavailable"))
                .when(fixture.store).completeTurn(any(), any(), any(), any(), any(), anyInt());

        assertThrows(IllegalStateException.class, () -> fixture.session().commit(
                new AiTurnResult("done", null, 1L), List.of(), null, null));

        assertTrue(fixture.session().recoverTerminalFailure(
                AiTurnOutcome.COMPLETED, new IllegalStateException("commit unavailable")));
        assertFalse(fixture.session().recoverTerminalFailure(
                AiTurnOutcome.COMPLETED, new IllegalStateException("repeated")));
        assertEquals("persistence", fixture.session().failedTurn().classification().category());
        verify(fixture.store, times(1)).discardTurn(
                eq(fixture.persistedTurn), eq("failed"), eq("persistence"),
                eq("AI 回复提交失败"), eq("commit unavailable"), eq(0),
                eq(""), eq(List.of()), eq(null));
        verify(fixture.memory).rebuild(fixture.agent, "session:thread-1");
    }

    @Test
    void postCommitHookFailureCannotUndoTheCommittedTurn() {
        Fixture fixture = fixture();
        doThrow(new IllegalStateException("health registry unavailable"))
                .when(fixture.failover).recordSuccess(7);

        AiTurnTransaction.CompletedTurn completed = fixture.session().commit(
                new AiTurnResult("done", null, 1L), List.of(), null, null);

        assertEquals("done", completed.output());
        assertSame(completed, fixture.session().completedTurn());
        assertTrue(fixture.session().isTerminal());
        assertFalse(fixture.session().recoverTerminalFailure(
                AiTurnOutcome.COMPLETED, new IllegalStateException("downstream")));
        verify(fixture.store, never()).discardTurn(
                any(), any(), any(), any(), any(), anyInt(), any(), any(), any());
    }

    private Fixture fixture() {
        AiConversationStoreService store = mock(AiConversationStoreService.class);
        AiModelFailoverService failover = mock(AiModelFailoverService.class);
        AiErrorClassifier classifier = mock(AiErrorClassifier.class);
        AiTurnArtifacts artifacts = new AiTurnArtifacts();
        ManagedConversationMemory memory = mock(ManagedConversationMemory.class);
        ChatMemoryAccess agent = mock(ChatMemoryAccess.class);
        AiConversationStoreService.PersistedTurn persisted =
                new AiConversationStoreService.PersistedTurn(
                        "turn-1", "run-1", "thread-1", "message-1",
                        "assistant-message-1", 1L, null);
        when(classifier.classify(any(Throwable.class))).thenReturn(
                new AiErrorClassifier.Classification(
                        "network", "网络连接失败", "network down", List.of()));
        when(classifier.classifyCategory(eq("persistence"), any())).thenReturn(
                new AiErrorClassifier.Classification(
                        "persistence", "AI 回复提交失败", "commit unavailable", List.of()));
        AiTurnTransaction transaction = new AiTurnTransaction(
                store, failover, classifier, artifacts, memory);
        AiTurnTransaction.Session session = transaction.open(
                new AiTurnTransaction.Context(
                        persisted, 7, agent, "session:thread-1",
                        AiChatAuditEntry.platform(
                                "user-1", "alice", "normal", "hello"),
                        System.currentTimeMillis(),
                        boundTrace(persisted)));
        return new Fixture(store, failover, memory, agent, persisted, session);
    }

    private AiTurnTrace boundTrace(
            AiConversationStoreService.PersistedTurn persisted) {
        AiTurnTrace trace = AiTurnTrace.testTrace(
                "trace-1", "test", persisted.threadId(), persisted.startedAt());
        trace.bind(persisted.turnId(), persisted.runId());
        return trace;
    }

    private record Fixture(AiConversationStoreService store,
                           AiModelFailoverService failover,
                           ManagedConversationMemory memory,
                           ChatMemoryAccess agent,
                           AiConversationStoreService.PersistedTurn persistedTurn,
                           AiTurnTransaction.Session session) {
    }
}
