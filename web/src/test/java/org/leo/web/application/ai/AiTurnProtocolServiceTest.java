package org.leo.web.application.ai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.leo.ai.thread.AiConversationStoreService;
import org.leo.ai.thread.AiProtocolTurnRepository;
import org.leo.ai.thread.AiUserInputRepository;
import org.leo.core.entity.AiTurnRecord;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AiTurnProtocolServiceTest {

    private final AiConversationStoreService store = mock(AiConversationStoreService.class);
    private final AiProtocolTurnRepository turns = mock(AiProtocolTurnRepository.class);
    private final AiTurnProtocolService service = new AiTurnProtocolService(store, turns, mock(AiUserInputRepository.class));

    @Test
    void startupFailureWithoutLeaseUsesAtomicTerminalEventPath() {
        AiTurnRecord failed = existingTurn("turn-1", "queued");
        failed.setProtocolStatus("failed");
        when(store.failProtocolTurnBeforeRun(org.mockito.ArgumentMatchers.eq("turn-1"),
                org.mockito.ArgumentMatchers.eq("expired"), org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(failed);

        assertEquals("failed", service.failStart("turn-1", "expired", null).status());
        verifyNoInteractions(turns);
    }

    @ParameterizedTest
    @CsvSource({
            "inProgress,queued,queued,queued",
            "inProgress,running,running,inProgress",
            "inProgress,cancelling,cancelling,cancelling",
            "completed,completed,completed,completed",
            "interrupted,interrupted,interrupted,interrupted",
            "failed,failed,failed,failed"
    })
    void reusesSameCommandWithCurrentTurnAndMessageStatuses(
            String protocolStatus, String dispatchStatus, String turnStatus, String itemStatus) {
        AiTurnRecord existing = existingTurn("turn-1", dispatchStatus);
        existing.setProtocolStatus(protocolStatus);
        when(turns.findByClientId("thread-1", "client-1"))
                .thenReturn(existing);

        AiTurnProtocolService.Reservation reservation = service.begin(
                "thread-1", "client-1", "platform",
                "{\"message\":\"hello\"}", "hello", null, null);

        assertTrue(reservation.reused());
        Map<String, Object> turn = reservation.turn().toMap();
        assertEquals(turnStatus, turn.get("status"));
        List<?> items = (List<?>) turn.get("items");
        assertEquals("completed", ((Map<?, ?>) items.get(0)).get("status"));
        assertEquals(itemStatus, ((Map<?, ?>) items.get(1)).get("status"));
    }

    @Test
    void rejectsIdempotencyKeyReusedForDifferentCommand() {
        AiTurnRecord existing = existingTurn("turn-1", "queued");
        when(turns.findByClientId("thread-1", "client-1"))
                .thenReturn(existing);

        assertThrows(IllegalStateException.class, () -> service.begin(
                "thread-1", "client-1", "platform",
                "{\"message\":\"different\"}", "different", null, null));
        verify(store, never()).reserveProtocolTurn(any(), any(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"running", "cancelling"})
    void resolvesTheAuthoritativeActiveTurnWhenClientHasNoTurnId(String activeStatus) {
        AiTurnRecord active = existingTurn("turn-1", activeStatus);
        when(turns.listInProgress("thread-1"))
                .thenReturn(List.of(existingTurn("queued-1", "queued"), active,
                        existingTurn("queued-2", "queued"), existingTurn("later-active", "running")));
        AiTurnRecord cancelling = existingTurn("turn-1", "cancelling");
        cancelling.setInterruptRequested(true);
        when(turns.find("turn-1")).thenReturn(active);
        when(store.requestProtocolTurnInterrupt("thread-1", "turn-1"))
                .thenReturn(cancelling);

        AiTurnProtocolService.ThreadSnapshot snapshot = service.snapshotThread("thread-1", "failed");
        assertEquals(activeStatus, snapshot.status());
        assertTrue(snapshot.executing());
        assertEquals("turn-1", snapshot.activeTurn().id());
        assertEquals(List.of("queued-1", "queued-2"), snapshot.queuedTurns().stream()
                .map(AiTurnProtocolService.TurnSnapshot::id).toList());
        assertEquals(3, snapshot.toMap().get("pendingTurnCount"));

        AiTurnProtocolService.TurnSnapshot result =
                service.requestInterrupt("thread-1", null);

        assertEquals("turn-1", result.id());
        assertEquals("cancelling", result.status());
        verify(store).requestProtocolTurnInterrupt(
                "thread-1", "turn-1");
    }

    private AiTurnRecord existingTurn(String turnId, String dispatchStatus) {
        AiTurnRecord row = new AiTurnRecord();
        row.setTurnId(turnId);
        row.setThreadId("thread-1");
        row.setProtocolStatus("inProgress");
        row.setDispatchStatus(dispatchStatus);
        row.setCommandScope("platform");
        row.setCommandJson("{\"message\":\"hello\"}");
        row.setClientUserMessageId("client-1");
        row.setUserItemId("user-1");
        row.setAssistantItemId("assistant-1");
        row.setCreatedAt(100L);
        return row;
    }
}
