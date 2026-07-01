package org.leo.ai.thread;

import com.alibaba.fastjson.JSON;
import org.leo.ai.channel.DynamicModelProvider;
import org.leo.core.entity.AiModelConfig;
import org.leo.core.entity.AiMessageRecord;
import org.leo.core.entity.AiRunRecord;
import org.leo.core.entity.AiThreadRecord;
import org.leo.core.entity.AiSseEvent;
import org.leo.core.session.AiThread;
import org.leo.core.util.json.JsonUtil;
import org.leo.dao.mapper.AiConversationMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AiConversationStoreService {

    public static final String SCOPE_PUPPET = "puppet";
    public static final String SCOPE_PLATFORM = "platform";

    private final AiConversationMapper mapper;

    public AiConversationStoreService(AiConversationMapper mapper) {
        this.mapper = mapper;
    }

    public List<AiThreadRecord> listPuppetThreads(String userId, String puppetId) {
        if (isBlank(puppetId)) {
            return List.of();
        }
        return mapper.listThreads(SCOPE_PUPPET, userId, puppetId);
    }

    public List<AiThreadRecord> listPlatformThreads(String userId) {
        if (isBlank(userId)) {
            return List.of();
        }
        return mapper.listPlatformThreads(userId);
    }

    public AiThreadRecord findThread(String threadId) {
        if (isBlank(threadId)) {
            return null;
        }
        return mapper.findThread(threadId);
    }

    public void createPuppetThread(String userId, String puppetId, String sessionId,
                                   AiThread thread, AiModelConfig config) {
        AiThreadRecord row = new AiThreadRecord();
        row.setThreadId(thread.getThreadId());
        row.setScope(SCOPE_PUPPET);
        row.setUserId(userId);
        row.setPuppetId(puppetId);
        row.setSessionId(sessionId);
        row.setTitle(thread.getTitle());
        applyConfig(row, config);
        row.setCreatedAt(thread.getCreatedAt());
        row.setLastActiveAt(thread.getLastActiveAt());
        row.setMessageCount(0);
        row.setRunStatus(AiThread.STATUS_IDLE);
        row.setMode(thread.getMode());
        row.setParentThreadId(thread.getParentThreadId());
        mapper.insertThread(row);
    }

    public void createPlatformThread(String userId, String sessionId, String threadId,
                                     String title, long createdAt, AiModelConfig config) {
        AiThreadRecord row = new AiThreadRecord();
        row.setThreadId(threadId);
        row.setScope(SCOPE_PLATFORM);
        row.setUserId(userId);
        row.setSessionId(sessionId);
        row.setTitle(isBlank(title) ? "平台 AI" : title);
        applyConfig(row, config);
        row.setCreatedAt(createdAt > 0 ? createdAt : System.currentTimeMillis());
        row.setLastActiveAt(row.getCreatedAt());
        row.setMessageCount(0);
        row.setRunStatus(AiThread.STATUS_IDLE);
        row.setMode(AiThread.MODE_AUTO);
        mapper.insertThread(row);
    }

    /** 切换执行模式并刷新 last_active。 */
    public void updateMode(String threadId, String mode) {
        long now = System.currentTimeMillis();
        mapper.updateThreadMode(threadId, mode == null || mode.isBlank() ? AiThread.MODE_AUTO : mode, now);
    }

    public void renameThread(String threadId, String title) {
        mapper.renameThread(threadId, title, System.currentTimeMillis());
    }

    public void updateRuntime(String sessionId, AiThread thread) {
        AiThreadRecord row = new AiThreadRecord();
        row.setThreadId(thread.getThreadId());
        row.setSessionId(sessionId);
        row.setLastActiveAt(thread.getLastActiveAt());
        row.setRunStatus(thread.getRunStatus());
        mapper.updateThreadRuntime(row);
    }

    public void updateRuntime(String sessionId, String threadId, long lastActiveAt, String runStatus) {
        AiThreadRecord row = new AiThreadRecord();
        row.setThreadId(threadId);
        row.setSessionId(sessionId);
        row.setLastActiveAt(lastActiveAt > 0 ? lastActiveAt : System.currentTimeMillis());
        row.setRunStatus(runStatus);
        mapper.updateThreadRuntime(row);
    }

    public void updateConfig(String threadId, AiModelConfig config) {
        AiThreadRecord row = new AiThreadRecord();
        row.setThreadId(threadId);
        row.setLastActiveAt(System.currentTimeMillis());
        applyConfig(row, config);
        mapper.updateThreadConfig(row);
    }

    public void deleteThread(String threadId) {
        mapper.deleteMessages(threadId);
        mapper.deleteRuns(threadId);
        mapper.deleteSubagentInvocations(threadId);
        mapper.deleteThread(threadId);
    }

    // ── 子 Agent 派发记录 ───────────────────────────────────────────────────

    /** 写入一条 pending 派发记录，调用方需要先填好 invocationId / parentThreadId / task。 */
    public void insertSubagentInvocation(org.leo.core.entity.AiSubagentInvocation row) {
        if (row.getStatus() == null) {
            row.setStatus(org.leo.core.entity.AiSubagentInvocation.STATUS_PENDING);
        }
        if (row.getCreatedAt() == null) {
            row.setCreatedAt(System.currentTimeMillis());
        }
        mapper.insertSubagentInvocation(row);
    }

    /** 更新派发状态：running / completed / failed / cancelled，以及 summary、childThreadId。 */
    public void updateSubagentInvocation(org.leo.core.entity.AiSubagentInvocation row) {
        if (row.getInvocationId() == null) {
            throw new IllegalArgumentException("invocationId 不能为空");
        }
        if (row.getStatus() != null
                && !org.leo.core.entity.AiSubagentInvocation.STATUS_PENDING.equals(row.getStatus())
                && !org.leo.core.entity.AiSubagentInvocation.STATUS_RUNNING.equals(row.getStatus())
                && row.getCompletedAt() == null) {
            row.setCompletedAt(System.currentTimeMillis());
        }
        mapper.updateSubagentInvocation(row);
    }

    public List<org.leo.core.entity.AiSubagentInvocation> listSubagentInvocations(String parentThreadId) {
        if (parentThreadId == null || parentThreadId.isBlank()) {
            return java.util.Collections.emptyList();
        }
        return mapper.listSubagentInvocations(parentThreadId);
    }

    public void appendMessage(String threadId, String role, String content) {
        appendMessage(threadId, role, content, null, null, null);
    }

    public void appendMessage(String threadId, String role, String content,
                              List<Object> nodes,
                              Map<String, Object> review) {
        appendMessage(threadId, role, content, nodes, review, null);
    }

    public void appendMessage(String threadId, String role, String content,
                              List<Object> nodes,
                              Map<String, Object> review,
                              Object planSnapshot) {
        long now = System.currentTimeMillis();
        AiMessageRecord row = new AiMessageRecord();
        row.setMessageId(UUID.randomUUID().toString());
        row.setThreadId(threadId);
        row.setRole(role);
        row.setContent(content);
        row.setTimestamp(now);
        row.setNodesJson(toJsonOrNull(nodes));
        row.setReviewJson(toJsonOrNull(review));
        row.setPlanJson(toJsonOrNull(planSnapshot));
        mapper.insertMessage(row);
        mapper.refreshMessageCount(threadId, now);
    }

    public List<Map<String, Object>> listMessages(String threadId, int offset, int limit) {
        int safeOffset = Math.max(0, offset);
        int safeLimit = limit < 0 ? Integer.MAX_VALUE : Math.max(1, Math.min(limit, 200));
        return toMessageMaps(mapper.listMessages(threadId, safeOffset, safeLimit));
    }

    public List<Map<String, Object>> recentMessages(String threadId, int limit) {
        return toMessageMaps(mapper.recentMessages(threadId, Math.max(1, Math.min(limit, 50))));
    }

    public int countMessages(String threadId) {
        return mapper.countMessages(threadId);
    }

    /**
     * 查找指定线程最近一条带有 plan 快照的消息中的 plan_json，
     * 用于线程重启后恢复计划状态（含每步的 preApproved 标志）。
     *
     * @return plan JSON 字符串，找不到则返回 null
     */
    public String findLatestPlanJson(String threadId) {
        if (threadId == null || threadId.isBlank()) return null;
        List<AiMessageRecord> recent = mapper.recentMessages(threadId, 50);
        if (recent == null) return null;
        for (AiMessageRecord record : recent) {
            String planJson = record.getPlanJson();
            if (planJson != null && !planJson.isBlank() && !"null".equals(planJson)) {
                return planJson;
            }
        }
        return null;
    }

    public String startRun(AiThread thread, String input, long startedAt) {
        return startRun(thread.getThreadId(), thread.getAiConfigId(), input, startedAt, null);
    }

    public String startRun(AiThread thread, String input, long startedAt, String runtimeJson) {
        return startRun(thread.getThreadId(), thread.getAiConfigId(), input, startedAt, runtimeJson);
    }

    public String startRun(String threadId, Integer configId, String input, long startedAt) {
        return startRun(threadId, configId, input, startedAt, null);
    }

    public String startRun(String threadId, Integer configId, String input, long startedAt, String runtimeJson) {
        AiRunRecord row = new AiRunRecord();
        row.setRunId(UUID.randomUUID().toString());
        row.setThreadId(threadId);
        row.setStatus(AiThread.STATUS_RUNNING);
        row.setStartedAt(startedAt);
        row.setConfigId(configId);
        row.setInput(input);
        row.setRuntimeJson(runtimeJson);
        mapper.insertRun(row);
        return row.getRunId();
    }

    public void finishRun(String runId, String status, long startedAt, String output,
                          String errorMessage, int toolCallCount) {
        AiRunRecord row = new AiRunRecord();
        row.setRunId(runId);
        row.setStatus(status);
        long finishedAt = System.currentTimeMillis();
        row.setFinishedAt(finishedAt);
        row.setDurationMs(Math.max(0L, finishedAt - startedAt));
        row.setOutput(output);
        row.setErrorMessage(errorMessage);
        row.setToolCallCount(toolCallCount);
        mapper.finishRun(row);
    }

    private static void applyConfig(AiThreadRecord row, AiModelConfig config) {
        if (config == null) {
            return;
        }
        row.setConfigId(config.getId());
        row.setConfigName(config.getName());
        row.setConfigProtocol(DynamicModelProvider.resolveProtocol(config));
        row.setConfigModel(config.getModel());
        row.setConfigBaseUrl(config.getBaseUrl());
        row.setConfigCompletionsPath(config.getCompletionsPath());
        row.setConfigMaxOutputTokens(config.getMaxOutputTokens());
    }

    private static List<Map<String, Object>> toMessageMaps(List<AiMessageRecord> records) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (AiMessageRecord record : records) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("messageId", record.getMessageId());
            item.put("role", record.getRole());
            item.put("content", record.getContent());
            item.put("timestamp", record.getTimestamp());
            putJson(item, "nodes", record.getNodesJson());
            putJson(item, "review", record.getReviewJson());
            putJson(item, "plan", record.getPlanJson());
            result.add(item);
        }
        return result;
    }

    private static void putJson(Map<String, Object> target, String key, String json) {
        Object parsed = fromJson(json);
        if (parsed != null) {
            target.put(key, parsed);
        }
    }

    private static String toJsonOrNull(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof List<?> list && list.isEmpty()) {
            return null;
        }
        if (value instanceof Map<?, ?> map && map.isEmpty()) {
            return null;
        }
        return JsonUtil.toJsonString(value);
    }

    private static Object fromJson(String json) {
        if (isBlank(json)) {
            return null;
        }
        try {
            return JSON.parse(json);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
