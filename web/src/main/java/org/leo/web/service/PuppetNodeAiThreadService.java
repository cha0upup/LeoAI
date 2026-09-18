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
import org.leo.core.session.PuppetNodeSession;
import org.leo.core.util.session.PuppetNodeSessionWorkDirUtil;
import org.leo.core.repository.session.PuppetAiCheckpointRepository;
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
 * Puppet AI 线程生命周期与查询用例。
 *
 * <p>只负责线程装载、CRUD、配置切换和历史查询；主 Turn 与委派 Turn 分别由
 * {@link PuppetNodeAiTurnService} 和 {@link PuppetNodeAiDelegationService} 执行。
 */
@Service
public class PuppetNodeAiThreadService {

    private static final Logger logger =
            LoggerFactory.getLogger(PuppetNodeAiThreadService.class);
    private final AiModelConfigService modelConfigService;
    private final AiModelChannelResolver channelResolver;
    private final AiConversationStoreService conversationStore;
    private final SessionWarmupService sessionWarmupService;
    private final PuppetNodeAiAgentRegistry agentRegistry;
    private final AiThreadQueryService threadQueries;
    private final PuppetAiCheckpointRepository checkpointRepository;

    public PuppetNodeAiThreadService(AiModelConfigService modelConfigService,
                                     AiModelChannelResolver channelResolver,
                                     AiConversationStoreService conversationStore,
                                     SessionWarmupService sessionWarmupService,
                                     PuppetNodeAiAgentRegistry agentRegistry,
                                     AiThreadQueryService threadQueries,
                                     PuppetAiCheckpointRepository checkpointRepository) {
        this.modelConfigService = modelConfigService;
        this.channelResolver = channelResolver;
        this.conversationStore = conversationStore;
        this.sessionWarmupService = sessionWarmupService;
        this.agentRegistry = agentRegistry;
        this.threadQueries = threadQueries;
        this.checkpointRepository = checkpointRepository;
    }

    public ThreadResolution ensureThreadReady(
            PuppetNodeSession session, String threadId, Integer configId) {
        AiThread thread = session.getAiThread(threadId);
        boolean restored = false;
        AiThreadRecord persisted = findPersistedThread(session, threadId);
        if (thread == null) {
            thread = restorePersistedThread(session, threadId, persisted);
            restored = thread != null;
        }
        Integer resolvedConfigId = resolveConfigId(configId, thread, persisted);
        AiModelConfig resolvedChannel;
        try {
            resolvedChannel = resolveOptionalChannel(resolvedConfigId);
        } catch (ApiException | IllegalArgumentException | IllegalStateException error) {
            boolean checkpoint = thread != null && hasThreadCheckpoint(session, threadId);
            return new ThreadResolution(thread, restored, checkpoint, error.getMessage());
        }
        if (resolvedChannel != null) resolvedConfigId = resolvedChannel.getId();
        boolean checkpoint = thread != null && hasThreadCheckpoint(session, threadId);
        String configError = validateConfigId(resolvedConfigId);
        if (configError != null) {
            return new ThreadResolution(thread, restored, checkpoint, configError);
        }
        String persistenceError =
                ensureThreadPersisted(session, thread, persisted, resolvedChannel);
        if (persistenceError != null) {
            return new ThreadResolution(thread, restored, checkpoint, persistenceError);
        }
        if (thread != null && (thread.getAiConfigId() == null || configId != null)) {
            thread.setAiConfigId(resolvedConfigId);
            updateThreadConfig(session, thread, resolvedChannel);
        }
        if (thread != null) {
            conversationStore.attachEventJournal(thread.getThreadId(), thread);
        }
        sessionWarmupService.warmupAsync(session.getSessionId());
        return new ThreadResolution(thread, restored, checkpoint, null);
    }

    public AiThread requireThread(PuppetNodeSession session, String threadId) {
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

    public Map<String, Object> listThreads(PuppetNodeSession session) {
        String userId = session.getCreateByUser();
        String puppetId = PuppetNodeSessionWorkDirUtil.resolvePuppetId(session);
        List<AiThread> memoryThreads = session.listAiThreads();
        List<Map<String, Object>> result = new ArrayList<>();
        Map<String, AiThread> memoryById = new LinkedHashMap<>();
        for (AiThread thread : memoryThreads) {
            memoryById.put(thread.getThreadId(), thread);
        }
        if (puppetId != null) {
            for (AiThreadRecord record :
                    conversationStore.listPuppetThreads(userId, puppetId)) {
                AiThread thread = memoryById.remove(record.getThreadId());
                Map<String, Object> item = thread != null
                        ? threadToMap(thread, safeMessageCount(record.getMessageCount()))
                        : threadRecordToMap(record);
                item.put("configName", record.getConfigName());
                item.put("configProtocol", record.getConfigProtocol());
                item.put("configModel", record.getConfigModel());
                item.put("hasCheckpoint",
                        hasThreadCheckpoint(session, record.getThreadId()));
                threadQueries.applyProtocolSnapshot(item, record.getThreadId());
                result.add(item);
            }
        }
        for (AiThread thread : memoryById.values()) {
            Map<String, Object> item = threadToMap(
                    thread, conversationStore.countMessages(thread.getThreadId()));
            if (puppetId != null) {
                item.put("hasCheckpoint",
                        hasThreadCheckpoint(session, thread.getThreadId()));
            }
            threadQueries.applyProtocolSnapshot(item, thread.getThreadId());
            result.add(item);
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
            PuppetNodeSession session, String title, Integer configId) {
        return createThread(session, title, configId, null);
    }

    public Map<String, Object> createChildThread(
            PuppetNodeSession session, String title, Integer configId,
            String parentThreadId) {
        if (parentThreadId == null || parentThreadId.isBlank()) {
            throw ApiException.badRequest("缺少 parentThreadId");
        }
        return createThread(session, title, configId, parentThreadId.trim());
    }

    private Map<String, Object> createThread(
            PuppetNodeSession session, String requestedTitle, Integer configId,
            String parentThreadId) {
        String threadId = UUID.randomUUID().toString();
        String title = requestedTitle != null && !requestedTitle.isBlank()
                ? requestedTitle : "对话 " + (session.listAiThreads().size() + 1);
        AiModelConfig config = resolveOptionalChannel(configId);
        Integer resolvedConfigId = config != null ? config.getId() : null;

        AiThread thread = session.createAiThread(threadId, title);
        thread.setAiConfigId(resolvedConfigId);
        thread.setParentThreadId(parentThreadId);
        sessionWarmupService.warmupAsync(session.getSessionId());

        String puppetId = PuppetNodeSessionWorkDirUtil.resolvePuppetId(session);
        if (puppetId != null) {
            conversationStore.createPuppetThread(
                    session.getCreateByUser(), puppetId,
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

    public void deleteThread(PuppetNodeSession session, String threadId) {
        session.removeAiThread(threadId);
        agentRegistry.evict(session, threadId);
        String puppetId = PuppetNodeSessionWorkDirUtil.resolvePuppetId(session);
        if (puppetId != null) {
            conversationStore.deleteThread(threadId);
            checkpointRepository.delete(
                    session.getCreateByUser(), puppetId, threadId);
        }
    }

    public void renameThread(
            PuppetNodeSession session, String threadId, String title) {
        AiThread thread = session.getAiThread(threadId);
        if (thread != null) thread.setTitle(title);
        conversationStore.renameThread(threadId, title);
    }

    public Map<String, Object> threadMessages(
            PuppetNodeSession session, String threadId,
            Integer requestedOffset, Integer requestedLimit) {
        return threadQueries.messages(threadId, requestedOffset, requestedLimit);
    }

    public Map<String, Object> threadEvents(
            PuppetNodeSession session, String threadId,
            Long requestedAfterSeq, Integer requestedLimit) {
        AiThread thread = requireThread(session, threadId);
        return threadQueries.events(threadId, thread, thread.getRunStatus(), requestedAfterSeq, requestedLimit);
    }

    public Map<String, Object> resetThread(
            PuppetNodeSession session, String threadId, Integer requestedConfigId) {
        AiThreadRecord persisted = findPersistedThread(session, threadId);
        AiThread thread = session.getAiThread(threadId);
        if (thread == null) thread = restorePersistedThread(session, threadId, persisted);
        if (thread == null) {
            throw ApiException.notFound("线程不存在，threadId: " + threadId);
        }
        AiModelConfig config = resolveOptionalChannel(
                resolveConfigId(requestedConfigId, thread, persisted));
        thread.stop();
        thread.clearSseEvents();
        thread.resetRuntimeStats();
        thread.setExecutionPolicy(AiExecutionPolicy.defaultPolicy());
        thread.resetTurnCount();
        thread.setAiConfigId(config != null ? config.getId() : null);
        agentRegistry.evict(session, threadId);
        updateThreadMeta(session, thread);
        updateThreadConfig(session, thread, config);

        Map<String, Object> info = new HashMap<>();
        info.put("reconSummaryLoaded", session.hasReconSummary());
        return info;
    }

    public void switchChannel(
            PuppetNodeSession session, String threadId, Integer requestedConfigId) {
        AiThreadRecord persisted = findPersistedThread(session, threadId);
        AiThread thread = session.getAiThread(threadId);
        if (thread == null) thread = restorePersistedThread(session, threadId, persisted);
        if (thread == null) {
            throw ApiException.notFound("线程不存在，threadId: " + threadId);
        }
        AiModelConfig config = resolveOptionalChannel(
                resolveConfigId(requestedConfigId, thread, persisted));
        thread.setAiConfigId(config != null ? config.getId() : null);
        agentRegistry.evict(session, threadId);
        updateThreadConfig(session, thread, config);
    }

    public void updateThreadMeta(PuppetNodeSession session, AiThread thread) {
        try {
            conversationStore.updateRuntime(session.getSessionId(), thread);
        } catch (Exception error) {
            logger.warn("更新线程运行状态失败, threadId={}: {}",
                    thread.getThreadId(), error.getMessage());
        }
    }

    private AiThread restorePersistedThread(
            PuppetNodeSession session, String threadId, AiThreadRecord record) {
        if (session == null || threadId == null || threadId.isBlank()) return null;
        String puppetId = PuppetNodeSessionWorkDirUtil.resolvePuppetId(session);
        String userId = session.getCreateByUser();
        if (puppetId == null || record == null
                || !puppetId.equals(record.getPuppetId())
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
            PuppetNodeSession session, String threadId) {
        if (session == null || threadId == null || threadId.isBlank()) return null;
        return conversationStore.findThread(threadId);
    }

    private String ensureThreadPersisted(
            PuppetNodeSession session, AiThread thread,
            AiThreadRecord persisted, AiModelConfig config) {
        if (session == null || thread == null || persisted != null) return null;
        String puppetId = PuppetNodeSessionWorkDirUtil.resolvePuppetId(session);
        if (puppetId == null) return null;
        try {
            conversationStore.createPuppetThread(
                    session.getCreateByUser(), puppetId,
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

    private boolean hasThreadCheckpoint(PuppetNodeSession session, String threadId) {
        String puppetId = PuppetNodeSessionWorkDirUtil.resolvePuppetId(session);
        return puppetId != null && checkpointRepository.exists(
                session.getCreateByUser(), puppetId, threadId);
    }

    private AiModelConfig resolveOptionalChannel(Integer configId) {
        return channelResolver.optional(configId);
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
        item.put("runStatus", thread.getRunStatus());
        item.put("executing", thread.isExecuting());
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
        item.put("runStatus", record.getRunStatus() != null
                ? record.getRunStatus() : AiRunStatus.IDLE);
        item.put("executing", false);
        item.put("parentThreadId", record.getParentThreadId());
        item.put("inMemory", false);
        return item;
    }

    private void updateThreadConfig(
            PuppetNodeSession session, AiThread thread, AiModelConfig config) {
        try {
            if (PuppetNodeSessionWorkDirUtil.resolvePuppetId(session) != null) {
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

    public record ThreadResolution(AiThread thread,
                                   boolean restoredFromPersistence,
                                   boolean hasPersistentCheckpoint,
                                   String errorMessage) {
    }
}
