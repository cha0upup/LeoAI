package org.leo.web.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.leo.ai.thread.AiConversationStoreService;
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
import static org.mockito.Mockito.when;

class AiTurnProtocolServiceTest {

    private final AiConversationStoreService store = mock(AiConversationStoreService.class);
    private final AiTurnProtocolService service = new AiTurnProtocolService(store);

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
        AiTurnRecord existing = existingTurn();
        existing.setProtocolStatus(protocolStatus);
        existing.setDispatchStatus(dispatchStatus);
        when(store.findProtocolTurnByClientId("thread-1", "client-1"))
                .thenReturn(existing);

        AiTurnProtocolService.Reservation reservation = service.begin(
                "thread-1", "client-1", "platform",
                "{\"message\":\"hello\"}", "hello", null);

        assertTrue(reservation.reused());
        Map<String, Object> turn = reservation.turn().toMap();
        assertEquals(turnStatus, turn.get("status"));
        List<?> items = (List<?>) turn.get("items");
        assertEquals("completed", ((Map<?, ?>) items.get(0)).get("status"));
        assertEquals(itemStatus, ((Map<?, ?>) items.get(1)).get("status"));
    }

    @Test
    void rejectsIdempotencyKeyReusedForDifferentCommand() {
        AiTurnRecord existing = existingTurn();
        when(store.findProtocolTurnByClientId("thread-1", "client-1"))
                .thenReturn(existing);

        assertThrows(IllegalStateException.class, () -> service.begin(
                "thread-1", "client-1", "platform",
                "{\"message\":\"different\"}", "different", null));
        verify(store, never()).reserveProtocolTurn(any(), any(), any());
    }

    @Test
    void resolvesTheAuthoritativeActiveTurnWhenClientHasNoTurnId() {
        AiTurnRecord active = existingTurn();
        active.setDispatchStatus("running");
        when(store.listInProgressProtocolTurns("thread-1"))
                .thenReturn(List.of(active));
        AiTurnRecord cancelling = existingTurn();
        cancelling.setDispatchStatus("cancelling");
        cancelling.setInterruptRequested(true);
        when(store.findProtocolTurn("turn-1")).thenReturn(active);
        when(store.requestProtocolTurnInterrupt("thread-1", "turn-1"))
                .thenReturn(cancelling);

        AiTurnProtocolService.TurnSnapshot result =
                service.requestInterrupt("thread-1", null);

        assertEquals("turn-1", result.id());
        assertEquals("cancelling", result.status());
        verify(store).requestProtocolTurnInterrupt(
                "thread-1", "turn-1");
    }

    private AiTurnRecord existingTurn() {
        AiTurnRecord row = new AiTurnRecord();
        row.setTurnId("turn-1");
        row.setThreadId("thread-1");
        row.setProtocolStatus("inProgress");
        row.setDispatchStatus("queued");
        row.setCommandScope("platform");
        row.setCommandJson("{\"message\":\"hello\"}");
        row.setClientUserMessageId("client-1");
        row.setUserItemId("user-1");
        row.setAssistantItemId("assistant-1");
        row.setCreatedAt(100L);
        return row;
    }
}
