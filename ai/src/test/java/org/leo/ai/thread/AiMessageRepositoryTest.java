package org.leo.ai.thread;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.leo.core.entity.AiMessageRecord;
import org.leo.dao.mapper.AiConversationMapper;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AiMessageRepositoryTest {
    private final AiConversationMapper mapper = mock(AiConversationMapper.class);
    private final AiMessageRepository messages = new AiMessageRepository(mapper);

    @Test
    void modelContextKeepsSequenceOrderAndResumableProgress() {
        AiMessageRecord user = new AiMessageRecord();
        user.setMessageSeq(7L);
        user.setRole("user");
        user.setContent("continue");
        AiMessageRecord recovery = new AiMessageRecord();
        recovery.setMessageSeq(8L);
        recovery.setRole("assistant");
        recovery.setStatus("discarded");
        recovery.setContent("partial reply");
        recovery.setRecoveryContext("private progress");
        when(mapper.recentContextMessages("thread", 20)).thenReturn(List.of(user, recovery));
        when(mapper.findContextMessageBySequence("thread", 8L)).thenReturn(recovery);

        assertEquals(List.of(new ConversationMessage(7L, "user", "continue"),
                        new ConversationMessage(8L, "assistant", "private progress")),
                messages.contextMessages("thread", 20));
        assertEquals(new ConversationMessage(8L, "assistant", "private progress"), messages.contextMessage("thread", 8L));
        assertNull(messages.contextMessage("thread", 9L));
    }

    @ParameterizedTest
    @CsvSource({"0,50,0,50", "-8,20,0,20", "7,20,7,20", "0,0,0,1",
            "0,200,0,200", "0,201,0,200", "0,-1,0,200", "0,-2147483648,0,200"})
    void preservesOffsetPaginationBounds(int offset, int limit, int expectedOffset, int expectedLimit) {
        messages.list("thread", offset, limit);

        verify(mapper).listMessages("thread", expectedOffset, expectedLimit);
    }

    @ParameterizedTest
    @CsvSource({"0,1", "20,20", "200,200", "201,200", "-1,1", "-2147483648,1"})
    void cursorAndContextReadsKeepTheirExistingPageBounds(int limit, int expectedLimit) {
        messages.listBefore("thread", 10L, limit);
        messages.contextMessages("thread", limit);

        verify(mapper).listMessagesBefore("thread", 10L, expectedLimit);
        verify(mapper).recentContextMessages("thread", expectedLimit);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void publicHistoryKeepsMetadataAndOmitsPrivateRecoveryAndInvalidJson(boolean cursor) {
        AiMessageRecord row = new AiMessageRecord();
        row.setMessageId("message-1");
        row.setThreadId("thread");
        row.setTurnId("turn-1");
        row.setRunId("run-1");
        row.setMessageSeq(3L);
        row.setStatus("discarded");
        row.setRole("assistant");
        row.setContent("partial reply");
        row.setRecoveryContext("private progress");
        row.setRunStatus("cancelled");
        row.setProtocolStatus("interrupted");
        row.setTimestamp(100L);
        row.setAttachmentsJson("[{\"name\":\"a.txt\"}]");
        row.setNodesJson("not-json");
        row.setReviewJson("{\"ok\":true}");
        row.setPlanJson("null");
        when(mapper.listMessages("thread", 0, 20)).thenReturn(List.of(row));
        when(mapper.listMessagesBefore("thread", 10L, 20)).thenReturn(List.of(row));

        Map<String, Object> message = (cursor
                ? messages.listBefore("thread", 10L, 20) : messages.list("thread", 0, 20)).get(0);

        assertEquals("message-1", message.get("messageId"));
        assertEquals("partial reply", message.get("content"));
        assertEquals("turn-1", message.get("turnId"));
        assertEquals("run-1", message.get("runId"));
        assertEquals(3L, message.get("sequence"));
        assertEquals("discarded", message.get("status"));
        assertEquals("cancelled", message.get("runStatus"));
        assertEquals("interrupted", message.get("protocolStatus"));
        assertEquals(List.of(Map.of("name", "a.txt")), message.get("attachments"));
        assertEquals(Map.of("ok", true), message.get("review"));
        assertFalse(message.containsKey("nodes"));
        assertFalse(message.containsKey("plan"));
        assertFalse(message.containsKey("recoveryContext"));
        assertFalse(message.containsValue("private progress"));
    }

    @Test
    void latestPlanScansBackwardThroughChronologicalHistoryAndSkipsEmptySnapshots() {
        String latest = "{\"step\":\"latest\"}";
        when(mapper.recentContextMessages("thread", 50))
                .thenReturn(List.of(plan("{\"step\":\"older\"}"), plan(latest), plan("null"), plan(" "), plan(null)))
                .thenReturn(List.of());

        assertEquals(latest, messages.findLatestPlanJson("thread"));
        assertNull(messages.findLatestPlanJson("thread"));
    }

    private AiMessageRecord plan(String json) {
        AiMessageRecord row = new AiMessageRecord();
        row.setPlanJson(json);
        return row;
    }
}
