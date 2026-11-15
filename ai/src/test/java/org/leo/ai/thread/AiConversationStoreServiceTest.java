package org.leo.ai.thread;

import org.junit.jupiter.api.Test;
import org.leo.ai.runtime.AiTurnTrace;
import org.leo.core.entity.AiMessageRecord;
import org.leo.core.entity.AiEventRecord;
import org.leo.core.entity.AiRunRecord;
import org.leo.core.entity.AiSseEvent;
import org.leo.core.entity.AiTurnRecord;
import org.leo.core.session.AiThread;
import org.leo.core.ai.AiRunStatus;
import org.leo.dao.mapper.AiConversationMapper;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiConversationStoreServiceTest {

    private final AiConversationMapper mapper = mock(AiConversationMapper.class);
    private final AiEventJournalRepository eventJournal = new AiEventJournalRepository(mapper);
    private final AiConversationStoreService service = new AiConversationStoreService(
            mapper, eventJournal,
            new AiTurnTerminalRepository(mapper), new AiProtocolTurnRepository(mapper),
            new AiUserInputRepository(mapper),
            new AiMessageRepository(mapper));

    @Test
    void startupFailureDiscardsMessagesAndPersistsOneReplayableTerminalEvent() {
        AiTurnRecord row = new AiTurnRecord();
        row.setTurnId("turn-start");
        row.setThreadId("thread-1");
        row.setAssistantItemId("assistant-start");
        when(mapper.findTurnById("turn-start")).thenReturn(row);
        when(mapper.failProtocolTurnBeforeRun("turn-start", "session expired", 100L)).thenReturn(1, 0);
        when(mapper.findLastEventSeq("thread-1")).thenReturn(9L);

        service.failProtocolTurnBeforeRun("turn-start", "session expired", 100L);
        service.failProtocolTurnBeforeRun("turn-start", "session expired", 100L);

        verify(mapper).updateTurnMessageStatus("thread-1", "turn-start", "discarded");
        verify(mapper).failOrphanedThread("thread-1", 100L);
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(mapper).insertEvent(anyString(), isNull(), eq("thread-1"), eq("turn-start"),
                eq("assistant-start"), isNull(), eq(10L), eq(100L), eq("turn/completed"),
                payload.capture(), isNull(), anyLong());
        assertTrue(payload.getValue().contains("session expired"));
        assertTrue(payload.getValue().contains("\"status\":\"failed\""));
    }

    @Test
    void reservesTurnAndVisibleMessagesInOneStoreOperation() {
        when(mapper.insertProtocolTurn(any(AiTurnRecord.class))).thenReturn(1);
        AiTurnRecord turn = new AiTurnRecord();
        turn.setTurnId("turn-queued");
        turn.setThreadId("thread-1");
        turn.setUserItemId("user-queued");
        turn.setAssistantItemId("assistant-queued");

        boolean reserved = service.reserveProtocolTurn(
                turn, "visible command", Map.of("name", "a.txt"), null);

        assertTrue(reserved);
        ArgumentCaptor<AiMessageRecord> messages =
                ArgumentCaptor.forClass(AiMessageRecord.class);
        verify(mapper).insertProtocolTurn(turn);
        verify(mapper, org.mockito.Mockito.times(2))
                .insertMessage(messages.capture());
        AiMessageRecord user = messages.getAllValues().get(0);
        AiMessageRecord assistant = messages.getAllValues().get(1);
        assertEquals("user-queued", user.getMessageId());
        assertEquals("visible command", user.getContent());
        assertEquals("{\"name\":\"a.txt\"}", user.getAttachmentsJson());
        assertEquals(AiConversationStoreService.MESSAGE_PENDING, user.getStatus());
        assertNull(user.getRunId());
        assertEquals("assistant-queued", assistant.getMessageId());
        assertEquals("assistant", assistant.getRole());
        assertEquals(AiConversationStoreService.MESSAGE_PENDING, assistant.getStatus());
        assertNull(assistant.getAttachmentsJson());
        assertNull(assistant.getRunId());
    }

    @Test
    void bindsAReservedTurnToItsRunWithoutDuplicatingMessages() {
        AiTurnRecord reserved = new AiTurnRecord();
        reserved.setTurnId("turn-1");
        reserved.setThreadId("thread-1");
        reserved.setUserItemId("user-1");
        reserved.setAssistantItemId("assistant-1");
        when(mapper.findTurnById("turn-1")).thenReturn(reserved);
        when(mapper.insertRun(any(AiRunRecord.class))).thenReturn(1);
        when(mapper.attachRunToTurnMessages(
                eq("thread-1"), eq("turn-1"), anyString())).thenReturn(2);

        AiConversationStoreService.PersistedTurn turn = service.beginTurn(
                "turn-1", "user-1", "assistant-1", "thread-1", 7,
                "guarded input", "visible input", null, 100L,
                "{\"model\":\"test\"}",
                AiTurnTrace.start("test", "thread-1", 100L), null);

        assertEquals("user-1", turn.userMessageId());
        assertEquals("assistant-1", turn.assistantMessageId());
        verify(mapper, never()).insertTurn(any(AiTurnRecord.class));
        verify(mapper, never()).insertMessage(any(AiMessageRecord.class));
        verify(mapper).attachRunToTurnMessages(
                eq("thread-1"), eq("turn-1"), eq(turn.runId()));
    }

    @Test
    void beginsTurnWithLinkedRunAndPendingUserMessage() {
        when(mapper.insertRun(any(AiRunRecord.class))).thenReturn(1);

        AiConversationStoreService.PersistedTurn turn = service.beginTurn(
                null, null, null, "thread-1", 7,
                "guarded input", "visible input", Map.of("name", "a.txt"),
                100L, "{\"model\":\"test\"}",
                AiTurnTrace.start("test", "thread-1", 100L), null);

        ArgumentCaptor<AiRunRecord> runCaptor = ArgumentCaptor.forClass(AiRunRecord.class);
        ArgumentCaptor<AiMessageRecord> messageCaptor = ArgumentCaptor.forClass(AiMessageRecord.class);
        InOrder order = inOrder(mapper);
        order.verify(mapper).insertTurn(any(AiTurnRecord.class));
        order.verify(mapper).insertRun(runCaptor.capture());
        order.verify(mapper).insertMessage(messageCaptor.capture());
        order.verify(mapper).insertMessage(messageCaptor.capture());

        AiRunRecord run = runCaptor.getValue();
        AiMessageRecord message = messageCaptor.getAllValues().get(0);
        AiMessageRecord assistant = messageCaptor.getAllValues().get(1);
        assertEquals(turn.runId(), run.getRunId());
        assertEquals(turn.turnId(), run.getTurnId());
        assertNotNull(run.getTraceId());
        assertNotNull(run.getTraceJson());
        assertEquals(turn.runId(), message.getRunId());
        assertEquals(turn.turnId(), message.getTurnId());
        assertEquals(AiConversationStoreService.MESSAGE_PENDING, message.getStatus());
        assertEquals("visible input", message.getContent());
        assertEquals("{\"name\":\"a.txt\"}", message.getAttachmentsJson());
        assertNotNull(turn.userMessageId());
        assertEquals("assistant", assistant.getRole());
        assertEquals(AiConversationStoreService.MESSAGE_PENDING, assistant.getStatus());
        assertEquals(turn.assistantMessageId(), assistant.getMessageId());
    }

    @Test
    void rejectsTurnCreationWhenLeaseTokenIsStale() {
        when(mapper.insertTurn(any(AiTurnRecord.class))).thenReturn(1);
        when(mapper.insertRunFenced(any(AiRunRecord.class), anyLong()))
                .thenReturn(0);

        assertThrows(IllegalStateException.class, () -> service.beginTurn(
                "turn-1", "user-1", "assistant-1", "thread-1", 7,
                "guarded input", "visible input", null, 100L,
                "{\"model\":\"test\"}",
                AiTurnTrace.start("test", "thread-1", 100L),
                "stale-token"));
    }

    @Test
    void completesTurnByCommittingBothMessagesAndFinishingRun() {
        when(mapper.updateMessageFenced(
                anyString(), anyString(), anyString(),
                isNull(), anyString(), anyString(),
                isNull(), anyString(), anyLong())).thenReturn(1);
        var turn = terminalTurnWithSuccessfulWrites();

        service.completeTurn(turn, "answer", List.of(Map.of("kind", "text")),
                Map.of("ok", true), null, 2);

        verify(mapper).updateMessageFenced(
                eq(turn.assistantMessageId()), eq(AiConversationStoreService.MESSAGE_COMMITTED), eq("answer"),
                isNull(), any(), any(),
                any(), eq("lease-1"), anyLong());
        verify(mapper).updateTurnMessageStatusFenced(
                eq("thread-1"), eq("turn-1"),
                eq(AiConversationStoreService.MESSAGE_COMMITTED),
                eq("lease-1"), anyLong());
        verify(mapper).finishTurn(
                eq("turn-1"),
                eq(AiConversationStoreService.MESSAGE_COMMITTED),
                anyLong(), eq("lease-1"));

        ArgumentCaptor<AiRunRecord> runCaptor = ArgumentCaptor.forClass(AiRunRecord.class);
        verify(mapper).finishRun(runCaptor.capture());
        assertEquals(AiRunStatus.COMPLETED, runCaptor.getValue().getStatus());
        assertEquals("answer", runCaptor.getValue().getOutput());
        assertNull(runCaptor.getValue().getErrorMessage());
    }

    @Test
    void discardsFailedTurnWithoutReturningItAsCommittedHistory() {
        when(mapper.updateMessageFenced(
                anyString(), anyString(), anyString(),
                isNull(), isNull(), isNull(),
                isNull(), anyString(), anyLong())).thenReturn(1);
        var turn = terminalTurnWithSuccessfulWrites();

        service.discardTurn(
                turn, AiRunStatus.CANCELLED, "cancelled",
                "用户取消", "用户取消", 0,
                "", List.of(), null,
                "");

        verify(mapper).updateTurnMessageStatusFenced(
                eq("thread-1"), eq("turn-1"),
                eq(AiConversationStoreService.MESSAGE_DISCARDED),
                eq("lease-1"), anyLong());
        verify(mapper).finishTurn(
                eq("turn-1"),
                eq(AiConversationStoreService.MESSAGE_DISCARDED),
                anyLong(), eq("lease-1"));
        ArgumentCaptor<AiRunRecord> runCaptor = ArgumentCaptor.forClass(AiRunRecord.class);
        verify(mapper).finishRun(runCaptor.capture());
        assertEquals(AiRunStatus.CANCELLED, runCaptor.getValue().getStatus());
        assertEquals("cancelled", runCaptor.getValue().getErrorCategory());
        assertEquals("用户取消", runCaptor.getValue().getErrorMessage());
        assertEquals("用户取消", runCaptor.getValue().getRawErrorMessage());
    }

    @Test
    void attachesPersistentJournalAndContinuesThreadSequence() {
        when(mapper.findLastEventSeq("thread-1")).thenReturn(41L);
        when(mapper.insertEvent(
                anyString(), any(), eq("thread-1"), eq("turn-1"),
                eq("item-1"), eq("subagent-1"), eq(42L),
                anyLong(), eq("delta"), eq("{\"text\":\"hello\"}"),
                isNull(), anyLong())).thenReturn(1);
        AiThread thread = new AiThread("thread-1", "test");
        thread.bindActiveTurnId("turn-1");
        thread.bindActiveItemId("item-1");
        thread.bindActiveRunId("run-1");

        service.attachEventJournal("thread-1", thread);
        AiSseEvent event = thread.recordSseEvent(
                "delta", Map.of("text", "hello"), "subagent-1");

        assertEquals(42L, event.seq());
        verify(mapper).insertEvent(
                anyString(), eq("run-1"), eq("thread-1"), eq("turn-1"),
                eq("item-1"), eq("subagent-1"), eq(42L), anyLong(),
                eq("delta"), eq("{\"text\":\"hello\"}"), isNull(), anyLong());
    }

    @Test
    void restoresPersistedEventsWithRoutingMetadata() {
        AiEventRecord row = new AiEventRecord();
        row.setEventId("event-1");
        row.setThreadId("thread-1");
        row.setTurnId("turn-1");
        row.setItemId("item-1");
        row.setRunId("run-1");
        row.setSubagentInvocationId("subagent-1");
        row.setEventSeq(9L);
        row.setTimestamp(100L);
        row.setName("node");
        row.setDataJson("{\"kind\":\"thinking\"}");
        when(mapper.listEventsAfter("thread-1", 8L, 200))
                .thenReturn(List.of(row));

        AiSseEvent event = service
                .listEventsAfter("thread-1", 8L, 200).get(0);

        assertEquals(9L, event.seq());
        assertEquals("turn-1", event.turnId());
        assertEquals("item-1", event.itemId());
        assertEquals("run-1", event.runId());
        assertEquals("subagent-1", event.subagentInvocationId());
        assertEquals("thinking",
                ((Map<?, ?>) event.data()).get("kind"));
    }

    @Test
    void rejectsTerminalWriteWhenLeaseTokenIsStale() {
        when(mapper.updateMessageFenced(
                anyString(), anyString(), anyString(),
                isNull(), any(), any(),
                any(), eq("stale-token"), anyLong())).thenReturn(0);
        var turn = new AiConversationStoreService.PersistedTurn(
                "turn-1", "run-1", "thread-1", "message-1",
                "assistant-1", 100L, "stale-token");

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> service.completeTurn(
                        turn, "answer", List.of(), Map.of(), null, 0));

        assertTrue(error.getMessage().contains("执行租约已失效"));
    }

    @Test
    void rejectsEventWhenRuntimeLeaseIsStale() {
        when(mapper.findLastEventSeq("thread-1")).thenReturn(0L);
        when(mapper.insertEvent(
                anyString(), any(), eq("thread-1"), any(), any(), any(),
                eq(1L), anyLong(), eq("delta"), anyString(),
                eq("stale-token"), anyLong())).thenReturn(0);
        AiThread thread = new AiThread("thread-1", "test");
        service.attachEventJournal("thread-1", thread);
        thread.bindActiveLeaseToken("stale-token");

        assertThrows(IllegalStateException.class,
                () -> thread.recordSseEvent("delta", "hello"));
    }

    private AiConversationStoreService.PersistedTurn terminalTurnWithSuccessfulWrites() {
        when(mapper.updateTurnMessageStatusFenced(
                anyString(), anyString(), anyString(), anyString(), anyLong())).thenReturn(2);
        when(mapper.finishTurn(anyString(), anyString(), anyLong(), anyString())).thenReturn(1);
        when(mapper.finishRun(any(AiRunRecord.class))).thenReturn(1);
        return new AiConversationStoreService.PersistedTurn(
                "turn-1", "run-1", "thread-1", "message-1", "assistant-1", 100L, "lease-1");
    }
}
