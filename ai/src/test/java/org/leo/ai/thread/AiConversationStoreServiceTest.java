package org.leo.ai.thread;

import org.junit.jupiter.api.Test;
import org.leo.ai.runtime.AiTurnTrace;
import org.leo.core.entity.AiMessageRecord;
import org.leo.core.entity.AiRunRecord;
import org.leo.core.entity.AiTurnRecord;
import org.leo.dao.mapper.AiConversationMapper;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

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
            new AiProtocolTurnRepository(mapper),
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

        PersistedTurn turn = service.beginTurn(
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

        PersistedTurn turn = service.beginTurn(
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

}
