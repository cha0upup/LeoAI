package org.leo.ai.thread;

import com.alibaba.fastjson.JSON;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.leo.core.entity.AiThreadRecord;
import org.leo.dao.mapper.AiConversationMapper;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AiContextCheckpointRepositoryTest {
    private final AiConversationMapper mapper = mock(AiConversationMapper.class);
    private final AiContextCheckpointRepository checkpoints = new AiContextCheckpointRepository(mapper);

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void storesAndRestoresSummaryAndVersionedBoundaryMetadata(int version) {
        String summary = "[历史摘要]\nsummary";
        checkpoints.update("thread", summary, 42L, "hash-42", version);
        ArgumentCaptor<String> metadata = ArgumentCaptor.forClass(String.class);
        verify(mapper).updateThreadContextCheckpoint(eq("thread"), eq(summary), metadata.capture(), anyLong());
        assertEquals(Map.of("version", version, "boundarySequence", 42, "boundaryHash", "hash-42"),
                JSON.parseObject(metadata.getValue()));
        var thread = new AiThreadRecord();
        thread.setContextSummary(summary);
        thread.setContextCheckpointJson(metadata.getValue());
        when(mapper.findThread("thread")).thenReturn(thread);

        assertEquals(new ConversationCheckpoint(summary, 42L, "hash-42", version), checkpoints.find("thread"));
    }

    @Test
    void missingThreadHasNoCheckpoint() {
        assertNull(checkpoints.find("missing"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "null", "[]", "not-json", "{}",
            "{\"boundarySequence\":42,\"boundaryHash\":\"hash\"}",
            "{\"version\":2,\"boundaryHash\":\"hash\"}",
            "{\"version\":2,\"boundarySequence\":42}",
            "{\"version\":2,\"boundarySequence\":42,\"boundaryHash\":\" \"}",
            "{\"version\":\"invalid\",\"boundarySequence\":42,\"boundaryHash\":\"hash\"}"})
    void missingOrMalformedMetadataFallsBackToUncompressedHistory(String metadata) {
        var thread = new AiThreadRecord();
        thread.setContextSummary("summary");
        thread.setContextCheckpointJson(metadata);
        when(mapper.findThread("thread")).thenReturn(thread);

        assertNull(checkpoints.find("thread"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = " ")
    void emptySummaryDoesNotRestoreACheckpoint(String summary) {
        var thread = new AiThreadRecord();
        thread.setContextSummary(summary);
        thread.setContextCheckpointJson("{\"version\":2,\"boundarySequence\":42,\"boundaryHash\":\"hash\"}");
        when(mapper.findThread("thread")).thenReturn(thread);

        assertNull(checkpoints.find("thread"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = " ")
    void blankThreadIdsNeverReadOrWriteCheckpoints(String threadId) {
        assertNull(checkpoints.find(threadId));
        checkpoints.update(threadId, "summary", 42L, "hash", 2);
        checkpoints.clear(threadId);

        verifyNoInteractions(mapper);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = " ")
    void incompleteCheckpointsNeverOverwriteTheStoredSummary(String blank) {
        checkpoints.update("thread", blank, 42L, "hash", 2);
        checkpoints.update("thread", "summary", 42L, blank, 2);

        verifyNoInteractions(mapper);
    }

    @Test
    void clearingAValidThreadRemovesBothSummaryAndMetadata() {
        checkpoints.clear("thread");

        verify(mapper).updateThreadContextCheckpoint(eq("thread"), isNull(), isNull(), anyLong());
    }
}
