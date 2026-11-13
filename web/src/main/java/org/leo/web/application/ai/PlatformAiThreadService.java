package org.leo.web.application.ai;

import org.leo.ai.platform.PlatformAiState;
import org.leo.ai.platform.PlatformAiStateStore;
import org.leo.ai.thread.AiConversationStoreService;
import org.leo.core.entity.AiModelConfig;
import org.leo.core.entity.AiThreadRecord;
import org.leo.core.entity.User;
import org.leo.web.exception.ApiException;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 平台 AI 线程生命周期与查询用例。
 *
 * <p>只负责状态装载、线程 CRUD、通道切换和历史读取，不参与模型执行。
 */
@Service
public class PlatformAiThreadService {

    private final AiModelChannelResolver channelResolver;
    private final AiConversationStoreService conversationStore;
    private final PlatformAiAgentRegistry agentRegistry;
    private final AiThreadQueryService threadQueries;

    public PlatformAiThreadService(AiModelChannelResolver channelResolver,
                                   AiConversationStoreService conversationStore,
                                   PlatformAiAgentRegistry agentRegistry,
                                   AiThreadQueryService threadQueries) {
        this.channelResolver = channelResolver;
        this.conversationStore = conversationStore;
        this.agentRegistry = agentRegistry;
        this.threadQueries = threadQueries;
    }

    public PlatformAiState createAgent(String sessionId, String previousStateId,
                                       User user, Integer configId) {
        AiModelConfig config = channelResolver.optional(configId);
        PlatformAiState state = recreateState(previousStateId);
        if (config != null) state.setAiConfigId(config.getId());

        conversationStore.createPlatformThread(
                user.getUserId(), sessionId, state.getStateId(),
                "平台 AI", state.getCreatedAt(), config);
        conversationStore.attachEventJournal(state.getStateId(), state);
        return state;
    }

    public void switchChannel(PlatformAiState state, Integer configId) {
        if (state.isExecuting()) {
            throw ApiException.badRequest("平台 AI 正在执行中，请等待完成或先停止后再切换通道");
        }
        AiModelConfig config = channelResolver.optional(configId);
        state.setAiConfigId(config != null ? config.getId() : null);
        agentRegistry.evict(state.getStateId());
        conversationStore.updateConfig(state.getStateId(), config);
    }

    public List<Map<String, Object>> listThreads(User user) {
        List<AiThreadRecord> records = conversationStore.listPlatformThreads(user.getUserId());
        if (records == null) return List.of();
        return records.stream().map(record -> {
            PlatformAiState runtime = PlatformAiStateStore.get(record.getThreadId());
            long persistedLastActiveAt =
                    record.getLastActiveAt() != null ? record.getLastActiveAt() : 0L;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("threadId", record.getThreadId());
            item.put("title", record.getTitle());
            item.put("createdAt", record.getCreatedAt());
            item.put("lastActiveAt", runtime != null
                    ? Math.max(persistedLastActiveAt, runtime.getLastActiveAt())
                    : record.getLastActiveAt());
            item.put("messageCount",
                    record.getMessageCount() != null ? record.getMessageCount() : 0);
            threadQueries.applyProtocolSnapshot(item, record.getThreadId(),
                    runtime != null ? runtime.getRunStatus() : record.getRunStatus());
            item.put("configId", record.getConfigId());
            item.put("configName", record.getConfigName());
            item.put("configProtocol", record.getConfigProtocol());
            item.put("configModel", record.getConfigModel());
            return item;
        }).toList();
    }

    public Map<String, Object> createThread(String sessionId, User user,
                                            String title, Integer configId) {
        AiModelConfig config = channelResolver.optional(configId);
        String threadId = "platform-ai-" + UUID.randomUUID();
        PlatformAiState state = PlatformAiStateStore.create(threadId);
        if (config != null) state.setAiConfigId(config.getId());

        String safeTitle = title != null && !title.isBlank() ? title : "新对话";
        conversationStore.createPlatformThread(
                user.getUserId(), sessionId, threadId,
                safeTitle, state.getCreatedAt(), config);
        conversationStore.attachEventJournal(threadId, state);

        Map<String, Object> info = new LinkedHashMap<>();
        info.put("threadId", threadId);
        info.put("title", safeTitle);
        info.put("configId", state.getAiConfigId());
        return info;
    }

    public void deleteThread(User user, String threadId) {
        if (threadId == null || threadId.isBlank()) return;
        requireOwnedThread(user, threadId);
        PlatformAiState state = PlatformAiStateStore.get(threadId);
        if (state != null) {
            state.stopGeneration("线程已删除");
            PlatformAiStateStore.remove(threadId);
        }
        agentRegistry.evict(threadId);
        conversationStore.deleteThread(threadId);
    }

    public void renameThread(User user, String threadId, String title) {
        if (threadId == null || threadId.isBlank()) return;
        requireOwnedThread(user, threadId);
        String safeTitle = title != null && !title.isBlank() ? title.trim() : "未命名对话";
        conversationStore.renameThread(threadId, safeTitle);
    }

    public PlatformAiState activateThread(User user, String threadId) {
        if (threadId == null || threadId.isBlank()) {
            throw ApiException.badRequest("缺少 threadId");
        }
        AiThreadRecord record = requireOwnedThread(user, threadId);
        PlatformAiState state = PlatformAiStateStore.get(threadId);
        if (state == null) state = PlatformAiStateStore.create(threadId);
        state.setAiConfigId(record.getConfigId());
        conversationStore.attachEventJournal(threadId, state);
        return state;
    }

    public Map<String, Object> events(User user, String threadId,
                                      Long requestedAfterSeq, Integer requestedLimit) {
        AiThreadRecord persisted = requireOwnedThread(user, threadId);
        PlatformAiState state = PlatformAiStateStore.get(threadId);
        String runStatus = state != null && state.isExecuting()
                ? state.getRunStatus() : persisted.getRunStatus();
        return threadQueries.events(threadId, state, runStatus, requestedAfterSeq, requestedLimit);
    }

    public Map<String, Object> messages(User user, String threadId,
                                        Integer requestedOffset, Integer requestedLimit, Long beforeSequence) {
        AiThreadRecord thread = requireOwnedThread(user, threadId);
        return threadQueries.messages(thread.getThreadId(), requestedOffset, requestedLimit, beforeSequence);
    }

    public List<org.leo.core.entity.AiSubagentInvocation> subagentInvocations(
            User user, String threadId) {
        return conversationStore.listSubagentInvocations(
                requireOwnedThread(user, threadId).getThreadId());
    }

    private AiThreadRecord requireOwnedThread(User user, String threadId) {
        AiThreadRecord record = conversationStore.findThread(threadId);
        if (record == null
                || !AiConversationStoreService.SCOPE_PLATFORM.equals(record.getScope())) {
            throw ApiException.notFound("线程不存在");
        }
        if (user == null || user.getUserId() == null
                || !user.getUserId().equals(record.getUserId())) {
            throw ApiException.notFound("线程不存在");
        }
        return record;
    }

    public PlatformAiState requireOwnedRuntime(User user, String threadId) {
        AiThreadRecord record = requireOwnedThread(user, threadId);
        PlatformAiState state = PlatformAiStateStore.get(record.getThreadId());
        if (state == null) {
            state = PlatformAiStateStore.create(record.getThreadId());
            state.setAiConfigId(record.getConfigId());
        }
        conversationStore.attachEventJournal(record.getThreadId(), state);
        return state;
    }

    private PlatformAiState recreateState(String previousStateId) {
        if (previousStateId != null && !previousStateId.isBlank()) {
            PlatformAiState existingState = PlatformAiStateStore.get(previousStateId);
            if (existingState != null) existingState.stopGeneration("平台 AI 会话已重建");
            PlatformAiStateStore.remove(previousStateId);
            agentRegistry.evict(previousStateId);
        }
        String stateId = "platform-ai-" + UUID.randomUUID();
        return PlatformAiStateStore.create(stateId);
    }
}
