package org.leo.ai.thread;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.leo.core.ai.AiRunStatus;
import org.leo.core.entity.AiRunRecord;
import org.leo.dao.mapper.AiConversationMapper;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AiTurnTerminalRepositoryTest {

    private final AiConversationMapper mapper = mock(AiConversationMapper.class);
    private final AiTurnTerminalRepository terminal = new AiTurnTerminalRepository(mapper);

    @Test
    void completesTurnByCommittingBothMessagesAndFinishingRun() {
        when(mapper.updateMessageFenced(
                anyString(), anyString(), anyString(),
                isNull(), anyString(), anyString(),
                isNull(), anyString(), anyLong())).thenReturn(1);
        var turn = terminalTurnWithSuccessfulWrites();

        terminal.complete(turn, "answer", List.of(Map.of("kind", "text")),
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

        terminal.discard(
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

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectsTerminalWriteWhenLeaseTokenIsStale(boolean discarded) {
        when(mapper.updateMessageFenced(
                anyString(), anyString(), anyString(),
                nullable(String.class), any(), any(),
                any(), eq("stale-token"), anyLong())).thenReturn(0);
        var turn = new PersistedTurn(
                "turn-1", "run-1", "thread-1", "message-1",
                "assistant-1", 100L, "stale-token");

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> {
                    if (discarded) {
                        terminal.discard(turn, "failed", "network", "error", "error", 0,
                                "partial", List.of(), null, "recovery");
                    } else {
                        terminal.complete(turn, "answer", List.of(), Map.of(), null, 0);
                    }
                });

        assertTrue(error.getMessage().contains("执行租约已失效"));
        verify(mapper, never()).finishRun(any());
    }

    private PersistedTurn terminalTurnWithSuccessfulWrites() {
        when(mapper.updateTurnMessageStatusFenced(
                anyString(), anyString(), anyString(), anyString(), anyLong())).thenReturn(2);
        when(mapper.finishTurn(anyString(), anyString(), anyLong(), anyString())).thenReturn(1);
        when(mapper.finishRun(any(AiRunRecord.class))).thenReturn(1);
        return new PersistedTurn(
                "turn-1", "run-1", "thread-1", "message-1", "assistant-1", 100L, "lease-1");
    }
}
