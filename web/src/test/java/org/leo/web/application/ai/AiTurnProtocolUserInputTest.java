package org.leo.web.application.ai;

import org.junit.jupiter.api.Test;
import org.leo.ai.thread.AiConversationStoreService;
import org.leo.ai.thread.AiUserInputRepository;
import org.leo.core.entity.AiUserInputRequest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiTurnProtocolUserInputTest {

    private final AiConversationStoreService store = mock(AiConversationStoreService.class);
    private final AiUserInputRepository requests = mock(AiUserInputRepository.class);
    private final AiTurnProtocolService service = new AiTurnProtocolService(store, requests);

    @Test
    void pendingQuestionMakesIdleThreadWaitWithoutHoldingExecutionClaim() {
        when(store.listInProgressProtocolTurns("thread-1")).thenReturn(List.of());
        AiUserInputRequest question = new AiUserInputRequest();
        question.setRequestId("question-1");
        question.setPrompt("请选择范围");
        question.setStatus(AiUserInputRequest.STATUS_PENDING);
        when(requests.findPending("thread-1")).thenReturn(question);

        AiTurnProtocolService.ThreadSnapshot snapshot =
                service.snapshotThread("thread-1", "completed");

        assertEquals(AiTurnProtocolService.STATUS_WAITING_FOR_USER, snapshot.status());
        assertFalse(snapshot.executing());
        assertEquals("question-1", snapshot.pendingUserInput().getRequestId());
    }

    @Test
    void answerTurnIsBoundToQuestionDuringAtomicReservation() {
        when(store.reserveProtocolTurn(any(), eq("当前节点"), any(), eq("question-1")))
                .thenReturn(true);

        AiTurnProtocolService.Reservation reservation = service.begin(
                "thread-1", "client-1", "platform", "{}",
                "当前节点", List.of(), "question-1");

        assertEquals("question-1", reservation.turn().answerToQuestionId());
        verify(store).reserveProtocolTurn(
                any(), eq("当前节点"), any(), eq("question-1"));
    }
}
