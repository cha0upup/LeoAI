package org.leo.web.service;

import org.leo.ai.thread.AiConversationStoreService;
import org.leo.core.ai.AiRuntimeState;
import org.leo.web.exception.ApiException;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Shared history queries; callers retain responsibility for thread authorization. */
@Service
public class AiThreadQueryService {

    private final AiConversationStoreService conversationStore;
    private final AiTurnProtocolService turnProtocolService;

    public AiThreadQueryService(AiConversationStoreService conversationStore,
                                AiTurnProtocolService turnProtocolService) {
        this.conversationStore = conversationStore;
        this.turnProtocolService = turnProtocolService;
    }

    /** 游标按稳定消息序号向前翻页，新增消息不会使旧页偏移。 */
    public Map<String, Object> messages(String threadId, Integer requestedOffset,
                                       Integer requestedLimit, Long beforeSequence) {
        if (beforeSequence != null && beforeSequence <= 0) {
            throw ApiException.badRequest("历史消息游标必须大于 0");
        }
        int offset = beforeSequence == null && requestedOffset != null ? Math.max(0, requestedOffset) : 0;
        int limit = requestedLimit != null ? requestedLimit : 50;
        limit = limit < 0 ? 200 : Math.max(1, Math.min(limit, 200));
        List<Map<String, Object>> page = beforeSequence == null
                ? conversationStore.listMessages(threadId, offset, limit)
                : conversationStore.listMessagesBefore(threadId, beforeSequence, limit);
        Long cursor = !page.isEmpty() && page.get(0).get("sequence") instanceof Number sequence
                ? sequence.longValue() : null;
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("messages", page);
        data.put("total", conversationStore.countMessages(threadId));
        data.put("offset", offset);
        data.put("limit", limit);
        data.put("nextBeforeSequence", cursor);
        data.put("hasMore", cursor != null && conversationStore.hasMessagesBefore(threadId, cursor));
        return data;
    }

    public Map<String, Object> events(String threadId, AiRuntimeState runtime, String runStatus,
                                      Long requestedAfterSeq, Integer requestedLimit) {
        long cursor = requestedAfterSeq != null ? Math.max(0L, requestedAfterSeq) : 0L;
        long afterSeq = cursor > 0L ? cursor : Math.max(
                runtime != null ? runtime.getCurrentRunStartSeq() : 0L,
                conversationStore.findLatestTurnStartSeq(threadId));
        int limit = requestedLimit != null ? requestedLimit : 200;
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("events", conversationStore.listEventsAfter(threadId, afterSeq, limit).stream()
                .map(AiEventPayloadMapper::toMap).toList());
        data.put("lastSeq", Math.max(runtime != null ? runtime.getLastSseEventSeq() : 0L,
                conversationStore.findLastEventSeq(threadId)));
        data.put("stopReason", runtime != null ? runtime.getStopReason() : null);
        applyProtocolSnapshot(data, threadId, runStatus);
        return data;
    }

    public void applyProtocolSnapshot(Map<String, Object> target, String threadId, String fallback) {
        target.putAll(turnProtocolService.snapshotThread(threadId, fallback).toMap());
    }
}
