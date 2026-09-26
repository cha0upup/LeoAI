package org.leo.ai.thread;

import com.alibaba.fastjson.JSON;
import org.leo.core.entity.AiMessageRecord;
import org.leo.core.util.json.JsonUtil;
import org.leo.dao.mapper.AiConversationMapper;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Persistence boundary for conversation messages and their JSON projections. */
@Repository
public class AiMessageRepository {

    private final AiConversationMapper mapper;

    public AiMessageRepository(AiConversationMapper mapper) {
        this.mapper = mapper;
    }

    public String appendPending(String requestedMessageId, String threadId, String turnId,
                                String runId, String role, String content, Object attachments) {
        long now = System.currentTimeMillis();
        AiMessageRecord row = new AiMessageRecord();
        row.setMessageId(requestedMessageId != null && !requestedMessageId.isBlank()
                ? requestedMessageId.trim() : UUID.randomUUID().toString());
        row.setThreadId(threadId);
        row.setTurnId(turnId);
        row.setRunId(runId);
        row.setStatus(AiConversationStoreService.MESSAGE_PENDING);
        row.setRole(role);
        row.setContent(content);
        row.setTimestamp(now);
        row.setAttachmentsJson(toJsonOrNull(attachments));
        mapper.insertMessage(row);
        mapper.refreshMessageCount(threadId, now);
        return row.getMessageId();
    }

    public List<Map<String, Object>> list(String threadId, int offset, int limit) {
        int safeOffset = Math.max(0, offset);
        int safeLimit = limit < 0 ? 200 : Math.max(1, Math.min(limit, 200));
        return toMaps(mapper.listMessages(threadId, safeOffset, safeLimit));
    }

    private static List<Map<String, Object>> toMaps(List<AiMessageRecord> records) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (AiMessageRecord record : records) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("messageId", record.getMessageId());
            item.put("turnId", record.getTurnId());
            item.put("runId", record.getRunId());
            item.put("runStatus", record.getRunStatus());
            item.put("protocolStatus", record.getProtocolStatus());
            item.put("dispatchStatus", record.getDispatchStatus());
            item.put("protocolErrorMessage", record.getProtocolErrorMessage());
            item.put("answerToQuestionId", record.getAnswerToQuestionId());
            item.put("sequence", record.getMessageSeq());
            item.put("status", record.getStatus());
            item.put("role", record.getRole());
            item.put("content", record.getContent());
            item.put("timestamp", record.getTimestamp());
            putJson(item, "attachments", record.getAttachmentsJson());
            putJson(item, "nodes", record.getNodesJson());
            putJson(item, "review", record.getReviewJson());
            putJson(item, "plan", record.getPlanJson());
            result.add(item);
        }
        return result;
    }

    private static void putJson(Map<String, Object> target, String key, String json) {
        Object parsed = fromJson(json);
        if (parsed != null) target.put(key, parsed);
    }

    public static String toJsonOrNull(Object value) {
        if (value == null) return null;
        if (value instanceof List<?> list && list.isEmpty()) return null;
        if (value instanceof Map<?, ?> map && map.isEmpty()) return null;
        return JsonUtil.toJsonString(value);
    }

    private static Object fromJson(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return JSON.parse(json);
        } catch (Exception ignored) {
            return null;
        }
    }
}
