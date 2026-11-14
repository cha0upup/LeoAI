package org.leo.ai.thread;

import org.junit.jupiter.api.Test;
import org.leo.core.entity.AiEventRecord;
import org.leo.core.entity.AiOrphanedRunRecord;
import org.leo.core.entity.AiSseEvent;
import org.leo.dao.mapper.AiConversationMapper;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AiExecutionLeaseRepositoryTest {
    private final AiConversationMapper mapper = mock(AiConversationMapper.class);
    private final AiExecutionLeaseRepository leases = new AiExecutionLeaseRepository(
            mapper, new AiEventJournalRepository(mapper));

    @Test
    void closesOrphanedRunAndAppendsAuthoritativeCompletionEvent() {
        AiOrphanedRunRecord run = new AiOrphanedRunRecord();
        run.setThreadId("thread-1");
        run.setTurnId("turn-1");
        run.setRunId("run-1");
        run.setAssistantMessageId("item-1");
        run.setStartedAt(100L);
        when(mapper.listRunningRuns("thread-1")).thenReturn(List.of(run));
        when(mapper.failOrphanedRun(
                eq("run-1"), eq(1_000L), any(String.class))).thenReturn(1);
        when(mapper.countTurnCompletedEvents("thread-1", "turn-1")).thenReturn(0);
        when(mapper.findLastEventSeq("thread-1")).thenReturn(7L);
        when(mapper.insertEvent(
                anyString(), eq("run-1"), eq("thread-1"), eq("turn-1"),
                eq("item-1"), isNull(), eq(8L), eq(1_000L),
                eq("turn/completed"), anyString(), isNull(), anyLong()))
                .thenReturn(1);
        AiEventRecord delta = new AiEventRecord();
        delta.setRunId("run-1");
        delta.setName("delta");
        delta.setDataJson("\"partial\"");
        AiEventRecord tool = new AiEventRecord();
        tool.setRunId("run-1");
        tool.setName("patch");
        tool.setDataJson("""
                {"kind":"tool","toolName":"getBasicInfo","toolCallId":"call-1",
                 "status":"completed","resultPreview":"os=linux"}
                """);
        when(mapper.listEventsByRun("run-1")).thenReturn(List.of(delta, tool));

        List<AiSseEvent> events =
                leases.recoverOrphanedRuns("thread-1", 1_000L);

        assertEquals(1, events.size());
        AiSseEvent event = events.get(0);
        assertEquals(8L, event.seq());
        assertEquals("turn/completed", event.name());
        assertEquals("turn-1", event.turnId());
        assertEquals("item-1", event.itemId());
        assertEquals("run-1", event.runId());
        verify(mapper).discardRunMessages("run-1");
        ArgumentCaptor<String> recovery = ArgumentCaptor.forClass(String.class);
        verify(mapper).updateMessageRecovery(eq("item-1"), eq("partial"), recovery.capture());
        assertTrue(recovery.getValue().contains("可用于继续任务"));
        assertTrue(recovery.getValue().contains("getBasicInfo"));
        assertTrue(recovery.getValue().contains("os=linux"));
        verify(mapper).discardOrphanedTurn("turn-1", 1_000L);
        verify(mapper).failOrphanedThread("thread-1", 1_000L);
        verify(mapper).insertEvent(
                anyString(), eq("run-1"), eq("thread-1"), eq("turn-1"),
                eq("item-1"), isNull(), eq(8L), eq(1_000L),
                eq("turn/completed"), anyString(), isNull(), anyLong());
    }
}
