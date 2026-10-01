package org.leo.web.service;

import com.alibaba.fastjson.JSON;
import org.leo.ai.channel.AiModelConfigService;
import org.leo.ai.service.SessionWarmupService;
import org.leo.ai.thread.AiConversationStoreService;
import org.leo.core.entity.AiExecutionPolicy;
import org.leo.core.entity.AiModelConfig;
import org.leo.core.entity.AiPlan;
import org.leo.core.entity.AiPlanStatus;
import org.leo.core.entity.AiThreadRecord;
import org.leo.core.session.AiThread;
import org.leo.core.ai.AiRunStatus;
import org.leo.core.session.AgentNodeSession;
import org.leo.core.util.session.AgentNodeSessionWorkDirUtil;
import org.leo.web.exception.ApiException;
import org.leo.web.util.ControllerUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Node AI 线程生命周期与查询用例。
 *
 * <p>只负责线程装载、CRUD、配置切换和历史查询；主 Turn 与委派 Turn 分别由
 * {@link NodeAiTurnService} 和 {@link NodeAiDelegationService} 执行。
 */
@Service
public class NodeAiThreadService {

    private static final Logger logger =
            LoggerFactory.getLogger(NodeAiThreadService.class);
    private final AiModelConfigService modelConfigService;
    private final AiModelChannelResolver channelResolver;
    private final AiConversationStoreService conversationStore;
    private final SessionWarmupService sessionWarmupService;
    private final NodeAiAgentRegistry agentRegistry;
    private final AiThreadQueryService threadQueries;

    public NodeAiThreadService(AiModelConfigService modelConfigService,
                                     AiModelChannelResolver channelResolver,
                                     AiConversationStoreService conversationStore,
                                     SessionWarmupService sessionWarmupService,
                                     NodeAiAgentRegistry agentRegistry,
                                     AiThreadQueryService threadQueries) {
        this.modelConfigService = modelConfigService;
        this.channelResolver = channelResolver;
        this.conversationStore = conversationStore;
        this.sessionWarmupService = sessionWarmupService;
        this.agentRegistry = agentRegistry;
        this.threadQueries = threadQueries;
    }

    public ThreadResolution ensureThreadReady(
            AgentNodeSession session, String threadId, Integer configId) {
        AiThread thread = session.getAiThread(threadId);
        AiThreadRecord persisted = findPersistedThread(session, threadId);
        if (thread == null) {
            thread = restorePersistedThread(session, threadId, persisted);
        }
        Integer resolvedConfigId = resolveConfigId(configId, thread, persisted);
        AiModelConfig resolvedChannel;
        try {
            resolvedChannel = channelResolver.optional(resolvedConfigId);
        } catch (ApiException | IllegalArgumentException | IllegalStateException error) {
            return new ThreadResolution(thread, error.getMessage());
        }
        if (resolvedChannel != null) resolvedConfigId = resolvedChannel.getId();
        String configError = validateConfigId(resolvedConfigId);
        if (configError != null) {
            return new ThreadResolution(thread, configError);
        }
        String persistenceError =
                ensureThreadPersisted(session, thread, persisted, resolvedChannel);
        if (persistenceError != null) {
            return new ThreadResolution(thread, persistenceError);
        }
        if (thread != null && (thread.getAiConfigId() == null || configId != null)) {
            thread.setAiConfigId(resolvedConfigId);
            updateThreadConfig(session, thread, resolvedChannel);
        }
        if (thread != null) {
            conversationStore.attachEventJournal(thread.getThreadId(), thread);
        }
        sessionWarmupService.warmupAsync(session.getSessionId());
        return new ThreadResolution(thread, null);
    }

    public AiThread requireThread(AgentNodeSession session, String threadId) {
        AiThread thread = session.getAiThread(threadId);
        if (thread == null) {
            thread = restorePersistedThread(
                    session, threadId, findPersistedThread(session, threadId));
        }
        if (thread == null) {
            throw ApiException.notFound("线程不存在，threadId: " + threadId);
        }
        conversationStore.attachEventJournal(threadId, thread);
        return thread;
    }

    public Map<String, Object> listThreads(AgentNodeSession session) {
        String userId = session.getCreateByUser();
        String agentId = AgentNodeSessionWorkDirUtil.resolveAgentId(session);
        List<AiThread> memoryThreads = session.listAiThreads();
        List<Map<String, Object>> result = new ArrayList<>();
        Map<String, AiThread> memoryById = new LinkedHashMap<>();
        for (AiThread thread : memoryThreads) {
            memoryById.put(thread.getThreadId(), thread);
        }
        if (agentId != null) {
            for (AiThreadRecord record :
                    conversationStore.listAgentThreads(userId, agentId)) {
                AiThread thread = memoryById.remove(record.getThreadId());
                Map<String, Object> item = thread != null
                        ? threadToMap(thread, safeMessageCount(record.getMessageCount()))
                        : threadRecordToMap(record);
                item.put("configName", record.getConfigName());
                item.put("configProtocol", record.getConfigProtocol());
                item.put("configModel", record.getConfigModel());
                result.add(item);
            }
        }
        for (AiThread thread : memoryById.values()) {
            result.add(threadToMap(
                    thread, conversationStore.countMessages(thread.getThreadId())));
        }
        result.sort((left, right) -> Long.compare(
                ControllerUtil.toLong(right.get("lastActiveAt")),
                ControllerUtil.toLong(left.get("lastActiveAt"))));

        Map<String, Object> data = new HashMap<>();
        data.put("threads", result);
        data.put("activeThreadId", session.getActiveThreadId());
        return data;
    }

    public Map<String, Object> createThread(
            AgentNodeSession session, String title, Integer configId) {
        return createThread(session, title, configId, null);
    }

    public Map<String, Object> createChildThread(
            AgentNodeSession session, String title, Integer configId,
            String parentThreadId) {
        if (parentThreadId == null || parentThreadId.isBlank()) {
            throw ApiException.badRequest("缺少 parentThreadId");
        }
        return createThread(session, title, configId, parentThreadId.trim());
    }

    private Map<String, Object> createThread(
            AgentNodeSession session, String requestedTitle, Integer configId,
            String parentThreadId) {
        String threadId = UUID.randomUUID().toString();
        String title = requestedTitle != null && !requestedTitle.isBlank()
                ? requestedTitle : "对话 " + (session.listAiThreads().size() + 1);
        AiModelConfig config = channelResolver.optional(configId);
        Integer resolvedConfigId = config != null ? config.getId() : null;

        AiThread thread = session.createAiThread(threadId, title);
        thread.setAiConfigId(resolvedConfigId);
        thread.setParentThreadId(parentThreadId);
        sessionWarmupService.warmupAsync(session.getSessionId());

        String agentId = AgentNodeSessionWorkDirUtil.resolveAgentId(session);
        if (agentId != null) {
            conversationStore.createAgentThread(
                    session.getCreateByUser(), agentId,
                    session.getSessionId(), thread, config);
            conversationStore.attachEventJournal(threadId, thread);
        }
        Map<String, Object> info = new HashMap<>();
        info.put("threadId", threadId);
        info.put("title", title);
        info.put("configId", resolvedConfigId);
        info.put("parentThreadId", parentThreadId);
        info.put("reconSummaryLoaded", session.hasReconSummary());
        return info;
    }

    public void deleteThread(AgentNodeSession session, String threadId) {
        session.removeAiThread(threadId);
        agentRegistry.evict(session, threadId);
        String agentId = AgentNodeSessionWorkDirUtil.resolveAgentId(session);
        if (agentId != null) {
            conversationStore.deleteThread(threadId);
        }
    }

    public void renameThread(
            AgentNodeSession session, String threadId, String title) {
        AiThread thread = session.getAiThread(threadId);
        if (thread != null) thread.setTitle(title);
        conversationStore.renameThread(threadId, title);
    }

    public Map<String, Object> threadMessages(
            AgentNodeSession session, String threadId,
            Integer requestedOffset, Integer requestedLimit) {
        return threadQueries.messages(threadId, requestedOffset, requestedLimit);
    }

    public Map<String, Object> threadEvents(
            AgentNodeSession session, String threadId,
            Long requestedAfterSeq, Integer requestedLimit) {
        AiThread thread = requireThread(session, threadId);
        return threadQueries.events(threadId, thread, thread.getRunStatus(), requestedAfterSeq, requestedLimit);
    }

    public Map<String, Object> resetThread(
            AgentNodeSession session, String threadId, Integer requestedConfigId) {
        AiThreadRecord persisted = findPersistedThread(session, threadId);
        AiThread thread = session.getAiThread(threadId);
        if (thread == null) thread = restorePersistedThread(session, threadId, persisted);
        if (thread == null) {
            throw ApiException.notFound("线程不存在，threadId: " + threadId);
        }
        AiModelConfig config = channelResolver.optional(
                resolveConfigId(requestedConfigId, thread, persisted));
        thread.stop();
        thread.clearSseEvents();
        thread.resetRuntimeStats();
        thread.setExecutionPolicy(AiExecutionPolicy.defaultPolicy());
        thread.setAiConfigId(config != null ? config.getId() : null);
        agentRegistry.evict(session, threadId);
        updateThreadMeta(session, thread);
        updateThreadConfig(session, thread, config);

        Map<String, Object> info = new HashMap<>();
        info.put("reconSummaryLoaded", session.hasReconSummary());
        return info;
    }

    public void switchChannel(
            AgentNodeSession session, String threadId, Integer requestedConfigId) {
        AiThreadRecord persisted = findPersistedThread(session, threadId);
        AiThread thread = session.getAiThread(threadId);
        if (thread == null) thread = restorePersistedThread(session, threadId, persisted);
        if (thread == null) {
            throw ApiException.notFound("线程不存在，threadId: " + threadId);
        }
        AiModelConfig config = channelResolver.optional(
                resolveConfigId(requestedConfigId, thread, persisted));
        thread.setAiConfigId(config != null ? config.getId() : null);
        agentRegistry.evict(session, threadId);
        updateThreadConfig(session, thread, config);
    }

    public void updateThreadMeta(AgentNodeSession session, AiThread thread) {
        try {
            conversationStore.updateRuntime(session.getSessionId(), thread, null);
        } catch (Exception error) {
            logger.warn("更新线程运行状态失败, threadId={}: {}",
                    thread.getThreadId(), error.getMessage());
        }
    }

    private AiThread restorePersistedThread(
            AgentNodeSession session, String threadId, AiThreadRecord record) {
        if (session == null || threadId == null || threadId.isBlank()) return null;
        String agentId = AgentNodeSessionWorkDirUtil.resolveAgentId(session);
        String userId = session.getCreateByUser();
        if (agentId == null || record == null
                || !agentId.equals(record.getAgentId())
                || record.getUserId() != null && !record.getUserId().equals(userId)) {
            return null;
        }
        String title = record.getTitle() != null && !record.getTitle().isBlank()
                ? record.getTitle() : "历史对话";
        AiThread thread = session.restoreAiThread(
                threadId, title,
                record.getCreatedAt() != null ? record.getCreatedAt() : 0L,
                record.getLastActiveAt() != null ? record.getLastActiveAt() : 0L);
        thread.setAiConfigId(record.getConfigId());
        thread.setParentThreadId(record.getParentThreadId());
        restoreLatestPlan(thread, threadId);
        conversationStore.attachEventJournal(threadId, thread);
        return thread;
    }

    private void restoreLatestPlan(AiThread thread, String threadId) {
        if (thread.getCurrentPlan() != null) return;
        try {
            String planJson = conversationStore.findLatestPlanJson(threadId);
            if (planJson == null) return;
            AiPlan plan = JSON.parseObject(planJson, AiPlan.class);
            if (plan == null) return;
            AiPlanStatus status = plan.getStatus();
            if (status == AiPlanStatus.PLANNING || status == AiPlanStatus.IN_PROGRESS) {
                thread.addPlan(plan);
            }
        } catch (Exception error) {
            logger.warn("恢复线程 {} 的计划快照失败：{}", threadId, error.getMessage());
        }
    }

    private AiThreadRecord findPersistedThread(
            AgentNodeSession session, String threadId) {
        if (session == null || threadId == null || threadId.isBlank()) return null;
        return conversationStore.findThread(threadId);
    }

    private String ensureThreadPersisted(
            AgentNodeSession session, AiThread thread,
            AiThreadRecord persisted, AiModelConfig config) {
        if (session == null || thread == null || persisted != null) return null;
        String agentId = AgentNodeSessionWorkDirUtil.resolveAgentId(session);
        if (agentId == null) return null;
        try {
            conversationStore.createAgentThread(
                    session.getCreateByUser(), agentId,
                    session.getSessionId(), thread, config);
            return null;
        } catch (RuntimeException createError) {
            try {
                if (conversationStore.findThread(thread.getThreadId()) != null) return null;
            } catch (RuntimeException lookupError) {
                createError.addSuppressed(lookupError);
            }
            logger.warn("初始化 AI 线程持久化失败, sessionId={}, threadId={}: {}",
                    session.getSessionId(), thread.getThreadId(), createError.getMessage());
            return "初始化对话存储失败，请稍后重试";
        }
    }

    private Integer resolveConfigId(
            Integer requestedConfigId, AiThread thread, AiThreadRecord record) {
        if (requestedConfigId != null) return requestedConfigId;
        if (thread != null && thread.getAiConfigId() != null) return thread.getAiConfigId();
        return record != null ? record.getConfigId() : null;
    }

    private String validateConfigId(Integer configId) {
        if (configId == null) return null;
        try {
            return modelConfigService.findById(configId) != null
                    ? null
                    : "AI 通道不存在或已删除，configId: " + configId + "，请切换 AI 通道后重试";
        } catch (Exception error) {
            return "AI 通道校验失败，configId: " + configId + "，请检查配置后重试";
        }
    }

    private Map<String, Object> threadToMap(AiThread thread, int messageCount) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("threadId", thread.getThreadId());
        item.put("title", thread.getTitle());
        item.put("createdAt", thread.getCreatedAt());
        item.put("lastActiveAt", thread.getLastActiveAt());
        item.put("messageCount", messageCount);
        item.put("configId", thread.getAiConfigId());
        threadQueries.applyProtocolSnapshot(item, thread.getThreadId(), thread.getRunStatus());
        item.put("parentThreadId", thread.getParentThreadId());
        item.put("inMemory", true);
        return item;
    }

    private Map<String, Object> threadRecordToMap(AiThreadRecord record) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("threadId", record.getThreadId());
        item.put("title", record.getTitle());
        item.put("createdAt", record.getCreatedAt());
        item.put("lastActiveAt", record.getLastActiveAt());
        item.put("messageCount", safeMessageCount(record.getMessageCount()));
        item.put("configId", record.getConfigId());
        threadQueries.applyProtocolSnapshot(item, record.getThreadId(), record.getRunStatus() != null
                ? record.getRunStatus() : AiRunStatus.IDLE);
        item.put("parentThreadId", record.getParentThreadId());
        item.put("inMemory", false);
        return item;
    }

    private void updateThreadConfig(
            AgentNodeSession session, AiThread thread, AiModelConfig config) {
        try {
            if (AgentNodeSessionWorkDirUtil.resolveAgentId(session) != null) {
                conversationStore.updateConfig(thread.getThreadId(), config);
            }
        } catch (Exception error) {
            logger.warn("更新线程通道配置失败, threadId={}: {}",
                    thread.getThreadId(), error.getMessage());
        }
    }

    private static int safeMessageCount(Integer count) {
        return count != null ? count : 0;
    }

    public record ThreadResolution(AiThread thread, String errorMessage) {
    }
}
