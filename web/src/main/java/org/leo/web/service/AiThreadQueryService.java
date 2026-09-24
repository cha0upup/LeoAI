package org.leo.web.service;

import org.leo.ai.thread.AiConversationStoreService;
import org.leo.core.ai.AiRuntimeState;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
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

    public Map<String, Object> messages(String threadId, Integer requestedOffset, Integer requestedLimit) {
        int offset = requestedOffset != null ? Math.max(0, requestedOffset) : 0;
        int limit = requestedLimit != null ? requestedLimit : 50;
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("messages", conversationStore.listMessages(threadId, offset, limit));
        data.put("total", conversationStore.countMessages(threadId));
        data.put("offset", offset);
        data.put("limit", limit);
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
