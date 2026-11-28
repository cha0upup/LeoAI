package org.leo.web.application.ai;

import org.leo.ai.platform.PlatformAiState;
import org.leo.ai.platform.PlatformAiStateStore;
import org.leo.ai.thread.AiConversationStoreService;
import org.leo.ai.thread.AiSubagentInvocationRepository;
import org.leo.core.entity.AiModelConfig;
import org.leo.core.entity.AiSubagentInvocation;
import org.leo.core.entity.AiThreadRecord;
import org.leo.core.entity.User;
import org.leo.web.application.UseCaseException;
import org.leo.web.application.UseCaseException.Kind;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 平台 AI 线程生命周期与归属校验用例。
 *
 * <p>负责状态装载、线程 CRUD、通道切换及历史读取授权；列表视图由 AiThreadQueryService 组织。
 */
@Service
public class PlatformAiThreadService {

    private final AiModelChannelResolver channelResolver;
    private final AiConversationStoreService conversationStore;
    private final AiSubagentInvocationRepository invocations;
    private final PlatformAiAgentRegistry agentRegistry;
    private final AiThreadQueryService threadQueries;

    public PlatformAiThreadService(AiModelChannelResolver channelResolver,
                                   AiConversationStoreService conversationStore,
                                   AiSubagentInvocationRepository invocations,
                                   PlatformAiAgentRegistry agentRegistry,
                                   AiThreadQueryService threadQueries) {
        this.channelResolver = channelResolver;
        this.conversationStore = conversationStore;
        this.invocations = invocations;
        this.agentRegistry = agentRegistry;
        this.threadQueries = threadQueries;
    }

    public record CreatedThread(String threadId, String title, Integer configId) { }

    public CreatedThread initializeThread(String sessionId, String previousStateId,
                                          User user, Integer configId) {
        AiModelConfig config = channelResolver.optional(configId);
        removeRuntime(previousStateId, "平台 AI 会话已重建");
        return createThreadState(sessionId, user, "平台 AI", config);
    }

    public void switchChannel(PlatformAiState state, Integer configId) {
        if (state.isExecuting()) {
            throw new UseCaseException(Kind.INVALID_INPUT, "平台 AI 正在执行中，请等待完成或先停止后再切换通道");
        }
        AiModelConfig config = channelResolver.optional(configId);
        conversationStore.updateConfig(state.getStateId(), config);
        state.setAiConfigId(config != null ? config.getId() : null);
        agentRegistry.evict(state.getStateId());
    }

    public CreatedThread createThread(String sessionId, User user, String title, Integer configId) {
        AiModelConfig config = channelResolver.optional(configId);
        String safeTitle = title != null && !title.isBlank() ? title : "新对话";
        return createThreadState(sessionId, user, safeTitle, config);
    }

    public void deleteThread(User user, String threadId) {
        if (threadId == null || threadId.isBlank()) return;
        requireOwnedThread(user, threadId);
        removeRuntime(threadId, "线程已删除");
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
            throw new UseCaseException(Kind.INVALID_INPUT, "缺少 threadId");
        }
        AiThreadRecord record = requireOwnedThread(user, threadId);
        PlatformAiState state = PlatformAiStateStore.getOrCreate(threadId, record.getConfigId());
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

    public List<AiSubagentInvocation> subagentInvocations(
            User user, String threadId) {
        return invocations.listByParentThread(
                requireOwnedThread(user, threadId).getThreadId());
    }

    private AiThreadRecord requireOwnedThread(User user, String threadId) {
        AiThreadRecord record = conversationStore.findThread(threadId);
        if (record == null
                || !AiConversationStoreService.SCOPE_PLATFORM.equals(record.getScope())) {
            throw new UseCaseException(Kind.NOT_FOUND, "线程不存在");
        }
        if (user == null || user.getUserId() == null
                || !user.getUserId().equals(record.getUserId())) {
            throw new UseCaseException(Kind.NOT_FOUND, "线程不存在");
        }
        return record;
    }

    public PlatformAiState requireOwnedRuntime(User user, String threadId) {
        AiThreadRecord record = requireOwnedThread(user, threadId);
        PlatformAiState state = PlatformAiStateStore.getOrCreate(record.getThreadId(), record.getConfigId());
        conversationStore.attachEventJournal(record.getThreadId(), state);
        return state;
    }

    private CreatedThread createThreadState(String sessionId, User user, String title, AiModelConfig config) {
        PlatformAiState state = PlatformAiStateStore.getOrCreate(
                "platform-ai-" + UUID.randomUUID(), config != null ? config.getId() : null);
        conversationStore.createPlatformThread(
                user.getUserId(), sessionId, state.getStateId(), title, state.getCreatedAt(), config);
        conversationStore.attachEventJournal(state.getStateId(), state);
        return new CreatedThread(state.getStateId(), title, state.getAiConfigId());
    }

    private void removeRuntime(String stateId, String reason) {
        if (stateId == null || stateId.isBlank()) return;
        PlatformAiState state = PlatformAiStateStore.get(stateId);
        if (state != null) {
            state.stopGeneration(reason);
            PlatformAiStateStore.remove(stateId);
        }
        agentRegistry.evict(stateId);
    }
}
