package org.leo.ai.thread;

import com.alibaba.fastjson.JSON;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.leo.ai.runtime.AiTurnRecoveryContext;
import org.leo.core.entity.AiMessageRecord;
import org.leo.core.entity.AiSseEvent;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AiMessageContentTest {

    @ParameterizedTest
    @CsvSource({"user,discarded", "assistant,committed", "assistant,pending", "system,discarded", "tool,discarded"})
    void ordinaryMessagesUseTheirBodyForModelMemory(String role, String status) {
        var row = message(role, status, "actual reply");
        row.setRecoveryContext("private progress");
        assertEquals("actual reply", AiMessageContent.modelContext(row));
    }

    @Test
    void interruptedAssistantUsesPrivateRecoveryWithoutSerializingIt() throws Exception {
        var row = message("assistant", "discarded", "partial reply");
        row.setRecoveryContext("private progress");
        assertEquals("private progress", AiMessageContent.modelContext(row));
        assertEquals("partial reply", row.getContent());
        for (String json : List.of(JSON.toJSONString(row), new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(row))) {
            assertFalse(json.contains("private progress"));
            assertFalse(json.contains("recoveryContext"));
            assertTrue(json.contains("partial reply"));
        }
    }

    @Test
    void absentOrBlankRecoveryUsesTheBodyWithoutGuessingFromItsText() {
        String literalReply = AiTurnRecoveryContext.build(List.of(), Map.of("step", "quoted example"));
        var row = message("assistant", "discarded", literalReply);
        for (String recovery : new String[]{null, "", "  "}) {
            row.setRecoveryContext(recovery);
            assertEquals(literalReply, AiMessageContent.modelContext(row));
        }
        assertNull(AiMessageContent.modelContext(message("assistant", "discarded", null)));
    }

    @Test
    void extractsOnlyTheActualReplyWithoutTruncationOrInternalPayloads() {
        String reply = "正文".repeat(5000);
        var events = List.of(new AiSseEvent("delta", reply),
                new AiSseEvent("node", Map.of("kind", "thinking", "content", "private thinking")),
                new AiSseEvent("node", Map.of("kind", "plan", "payload", "private plan")));
        assertEquals(reply, AiTurnRecoveryContext.visibleOutput(events));
        assertEquals("", AiTurnRecoveryContext.visibleOutput(List.of(events.get(1), events.get(2))));
    }

    private static AiMessageRecord message(String role, String status, String content) {
        var row = new AiMessageRecord();
        row.setRole(role);
        row.setStatus(status);
        row.setContent(content);
        return row;
    }
}
