package org.leo.web.application.ai;

import org.leo.ai.platform.PlatformAiState;
import org.leo.ai.platform.PlatformAiStateStore;
import org.leo.ai.thread.AiConversationStoreService;
import org.leo.ai.thread.AiEventJournalRepository;
import org.leo.ai.thread.AiMessageRepository;
import org.leo.core.ai.AiEventStreamRuntime;
import org.leo.core.ai.AiRunStatus;
import org.leo.core.ai.AiRuntimeState;
import org.leo.core.entity.AiSseEvent;
import org.leo.core.entity.AiThreadRecord;
import org.leo.core.session.AgentNodeSession;
import org.leo.core.session.AiThread;
import org.leo.web.application.UseCaseException;
import org.leo.web.application.UseCaseException.Kind;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 线程列表、历史查询和视图装配；调用方负责用户及会话授权。 */
@Service
public class AiThreadQueryService {

    private static final Comparator<Map<String, Object>> LAST_ACTIVE_FIRST = Comparator.comparingLong(
            (Map<String, Object> item) -> item.get("lastActiveAt") instanceof Number time ? time.longValue() : 0L)
            .reversed();

    private final AiConversationStoreService conversationStore;
    private final AiMessageRepository messages;
    private final AiEventJournalRepository eventJournal;
    private final AiTurnProtocolService turnProtocolService;

    public AiThreadQueryService(AiConversationStoreService conversationStore,
                                AiMessageRepository messages,
                                AiEventJournalRepository eventJournal,
                                AiTurnProtocolService turnProtocolService) {
        this.conversationStore = conversationStore;
        this.messages = messages;
        this.eventJournal = eventJournal;
        this.turnProtocolService = turnProtocolService;
    }

    public Map<String, Object> listNodeThreads(AgentNodeSession session) {
        List<Map<String, Object>> result = new ArrayList<>();
        Map<String, AiThread> memoryById = new LinkedHashMap<>();
        for (AiThread thread : session.listAiThreads()) memoryById.put(thread.getThreadId(), thread);
        String agentId = session.resolveAgentId();
        if (agentId != null) {
            for (AiThreadRecord record : conversationStore.listAgentThreads(
                    session.getCreateByUser(), agentId, session.getCurrentHostId())) {
                result.add(nodeThread(memoryById.remove(record.getThreadId()), record));
            }
        }
        for (AiThread thread : memoryById.values()) result.add(nodeThread(thread, null));
        result.sort(LAST_ACTIVE_FIRST);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("threads", result);
        data.put("activeThreadId", session.getActiveThreadId());
        return data;
    }

    public List<Map<String, Object>> listPlatformThreads(String userId) {
        List<AiThreadRecord> records = conversationStore.listPlatformThreads(userId);
        if (records == null) return List.of();
        return records.stream().map(record -> {
            PlatformAiState runtime = PlatformAiStateStore.get(record.getThreadId());
            Map<String, Object> item = recordSummary(record);
            if (runtime != null) {
                long persistedTime = record.getLastActiveAt() != null ? record.getLastActiveAt() : 0L;
                item.put("lastActiveAt", Math.max(persistedTime, runtime.getLastActiveAt()));
            }
            applyProtocolSnapshot(item, record.getThreadId(),
                    runtime != null ? runtime.getRunStatus() : record.getRunStatus());
            return item;
        }).sorted(LAST_ACTIVE_FIRST).toList();
    }

    /** 游标按稳定消息序号向前翻页，新增消息不会使旧页偏移。 */
    public Map<String, Object> messages(String threadId, Integer requestedOffset,
                                       Integer requestedLimit, Long beforeSequence) {
        if (beforeSequence != null && beforeSequence <= 0) {
            throw new UseCaseException(Kind.INVALID_INPUT, "历史消息游标必须大于 0");
        }
        int offset = beforeSequence == null && requestedOffset != null ? Math.max(0, requestedOffset) : 0;
        int limit = requestedLimit != null ? requestedLimit : 50;
        limit = limit < 0 ? 200 : Math.max(1, Math.min(limit, 200));
        List<Map<String, Object>> page = beforeSequence == null
                ? messages.list(threadId, offset, limit)
                : messages.listBefore(threadId, beforeSequence, limit);
        Long cursor = !page.isEmpty() && page.get(0).get("sequence") instanceof Number sequence
                ? sequence.longValue() : null;
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("messages", page);
        data.put("nextBeforeSequence", cursor);
        data.put("hasMore", cursor != null && messages.hasBefore(threadId, cursor));
        return data;
    }

    public Map<String, Object> events(String threadId, AiRuntimeState runtime, String runStatus,
                                      Long requestedAfterSeq, Integer requestedLimit) {
        long afterSeq = replayCursor(threadId, runtime, requestedAfterSeq);
        int limit = requestedLimit != null ? requestedLimit : 200;
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("events", eventJournal.listAfter(threadId, afterSeq, limit).stream()
                .map(AiThreadQueryService::eventPayload).toList());
        data.put("lastSeq", Math.max(runtime != null ? runtime.getLastSseEventSeq() : 0L,
                eventJournal.lastSequence(threadId)));
        data.put("stopReason", runtime != null ? runtime.getStopReason() : null);
        applyProtocolSnapshot(data, threadId, runStatus);
        return data;
    }

    /** 正数游标用于断点续读；未指定时只读取当前或最近 Turn 的事件。 */
    public long replayCursor(String threadId, AiEventStreamRuntime runtime, Long requestedAfterSeq) {
        if (requestedAfterSeq != null && requestedAfterSeq > 0L) return requestedAfterSeq;
        return Math.max(runtime != null ? runtime.getCurrentRunStartSeq() : 0L,
                eventJournal.latestTurnStartSequence(threadId));
    }

    private void applyProtocolSnapshot(Map<String, Object> target, String threadId, String fallback) {
        target.putAll(turnProtocolService.snapshotThread(threadId, fallback).toMap());
    }

    private Map<String, Object> nodeThread(AiThread thread, AiThreadRecord record) {
        Map<String, Object> item = record != null ? recordSummary(record) : new LinkedHashMap<>();
        String threadId = thread != null ? thread.getThreadId() : record.getThreadId();
        if (thread != null) {
            item.put("threadId", threadId);
            item.put("title", thread.getTitle());
            item.put("createdAt", thread.getCreatedAt());
            item.put("lastActiveAt", thread.getLastActiveAt());
            item.put("configId", thread.getAiConfigId());
        }
        if (record == null) item.put("messageCount", messages.count(threadId));
        String status = thread != null ? thread.getRunStatus()
                : record.getRunStatus() != null ? record.getRunStatus() : AiRunStatus.IDLE;
        applyProtocolSnapshot(item, threadId, status);
        item.put("parentThreadId", thread != null ? thread.getParentThreadId() : record.getParentThreadId());
        item.put("inMemory", thread != null);
        return item;
    }

    private static Map<String, Object> recordSummary(AiThreadRecord record) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("threadId", record.getThreadId());
        item.put("title", record.getTitle());
        item.put("createdAt", record.getCreatedAt());
        item.put("lastActiveAt", record.getLastActiveAt());
        item.put("messageCount", record.getMessageCount() != null ? record.getMessageCount() : 0);
        item.put("configId", record.getConfigId());
        item.put("configName", record.getConfigName());
        item.put("configProtocol", record.getConfigProtocol());
        item.put("configModel", record.getConfigModel());
        return item;
    }

    private static Map<String, Object> eventPayload(AiSseEvent event) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("seq", event.seq());
        item.put("timestamp", event.timestamp());
        item.put("name", event.name());
        item.put("data", event.data());
        if (event.subagentInvocationId() != null) {
            item.put("subagentInvocationId", event.subagentInvocationId());
        }
        if (event.turnId() != null) item.put("turnId", event.turnId());
        if (event.itemId() != null) item.put("itemId", event.itemId());
        if (event.runId() != null) item.put("runId", event.runId());
        return item;
    }
}
