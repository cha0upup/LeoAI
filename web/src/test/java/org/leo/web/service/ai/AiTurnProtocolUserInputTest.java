package org.leo.web.service.ai;

import org.junit.jupiter.api.Test;
import org.leo.ai.thread.AiConversationStoreService;
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

    @Test
    void pendingQuestionMakesIdleThreadWaitWithoutHoldingExecutionClaim() {
        AiConversationStoreService store = mock(AiConversationStoreService.class);
        when(store.listInProgressProtocolTurns("thread-1")).thenReturn(List.of());
        AiUserInputRequest question = new AiUserInputRequest();
        question.setRequestId("question-1");
        question.setPrompt("请选择范围");
        question.setStatus(AiUserInputRequest.STATUS_PENDING);
        when(store.findPendingUserInputRequest("thread-1")).thenReturn(question);

        AiTurnProtocolService.ThreadSnapshot snapshot =
                new AiTurnProtocolService(store).snapshotThread("thread-1", "completed");

        assertEquals(AiTurnProtocolService.STATUS_WAITING_FOR_USER, snapshot.status());
        assertFalse(snapshot.executing());
        assertEquals("question-1", snapshot.pendingUserInput().getRequestId());
    }

    @Test
    void answerTurnIsBoundToQuestionDuringAtomicReservation() {
        AiConversationStoreService store = mock(AiConversationStoreService.class);
        when(store.reserveProtocolTurn(any(), eq("当前节点"), any(), eq("question-1")))
                .thenReturn(true);

        AiTurnProtocolService.Reservation reservation = new AiTurnProtocolService(store).begin(
                "thread-1", "client-1", "platform", "{}",
                "当前节点", List.of(), "question-1");

        assertEquals("question-1", reservation.turn().answerToQuestionId());
        verify(store).reserveProtocolTurn(
                any(), eq("当前节点"), any(), eq("question-1"));
    }
}
