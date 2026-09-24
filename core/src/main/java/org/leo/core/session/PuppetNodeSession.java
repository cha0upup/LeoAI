package org.leo.core.session;

import org.leo.core.config.LeoConfig;
import org.leo.core.entity.Puppet;
import org.leo.core.puppet.AbstractPuppetNode;
import org.leo.core.puppet.capability.HostScopedCapable;
import org.leo.core.puppet.capability.PuppetNodeCapabilityRegistry;
import org.leo.core.runtime.CapabilityStatus;
import org.leo.core.runtime.RuntimeProfile;
import org.leo.core.util.BoundedTtlCache;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 会话管理类，管理 puppet 连接会话状态。
 *
 * <p>每个 PuppetNodeSession 对应一次 puppet 连接，可持有多个独立的 {@link AiThread}，
 * 彼此独立、可并行执行。通过 {@link #getActiveThread()} 获取当前活跃线程，
 * 通过 {@link #createAiThread(String, String)} 创建新线程。
 *
 * @author LeoSpring
 * @version 3.0
 */
public class PuppetNodeSession {

    /** 通用节点引用 */
    private AbstractPuppetNode puppetNode;
    private String sessionId;
    private Long updateTime;
    private String createByUser;
    /** 创建会话时选择的业务项目；null 表示从未归档主机入口创建。 */
    private String projectId;
    private Map<String, Map<String, Object>> basicInfoMap;
    private volatile String currentHostId;

    // ── AI 对话线程管理 ───────────────────────────────────────────────────────
    /** 所有 AI 对话线程，key = threadId。 */
    private final ConcurrentHashMap<String, AiThread> aiThreads = new ConcurrentHashMap<>();
    /** 当前活跃线程 ID（最近操作的线程）。 */
    private volatile String activeThreadId;
    /** 每个 puppet 最多保留的线程数量。 */
    private static final int MAX_AI_THREADS = 20;

    // ── 侦察摘要 ──────────────────────────────────────────────────────────────
    /**
     * 侦察摘要：由 AI 或用户手动写入，描述目标节点的关键信息。
     * 每次创建新 Agent 时自动注入 system prompt，保证跨对话上下文连贯。
     */
    private volatile String reconSummary;
    /**
     * 侦察摘要精简版（Digest）：由 AI 对完整摘要压缩生成，最多 500 字符。
     */
    private volatile String reconSummaryDigest;
    /**
     * Digest 失效标记：每次 setReconSummary / appendReconSummary 后置为 true。
     */
    private final AtomicBoolean reconSummaryDigestDirty = new AtomicBoolean(true);
    /** AI 工具结果自动追加到侦察摘要的开关（默认开启）。
     *  AgentConfig.puppetNodeAgent 的 afterToolExecution 钩子据此决定是否触发 AutoReconAppendService。 */
    private volatile boolean autoAppendRecon = true;

    // ── 缓存模式 ──────────────────────────────────────────────────────────────
    /** 缓存模式标记：true 表示本 session 无实时连接，数据来自 puppet 级持久化目录。 */
    private volatile boolean cacheMode = false;
    /** 缓存模式下关联的 puppetId（实时 puppetNode 为 null 时用于定位持久化目录）。 */
    private volatile String puppetId;

    // ── 会话级 AI 上下文缓存（跨线程共享）────────────────────────────────────
    private volatile BoundedTtlCache aiContextCache;

    /** 最后活跃时间戳（ms），用于 TTL 清理。 */
    private volatile long lastActiveTime = System.currentTimeMillis();

    // ── 构造器 ────────────────────────────────────────────────────────────────

    public PuppetNodeSession(String sessionId, AbstractPuppetNode puppetNode, Long updateTime, String createByUser) {
        setPuppetNode(puppetNode);
        this.sessionId = sessionId;
        this.updateTime = updateTime;
        this.createByUser = createByUser;
        createSessionCacheDirectory(sessionId, createByUser);
    }

    public PuppetNodeSession() {}

    // ── AI 线程管理 ───────────────────────────────────────────────────────────

    /**
     * 创建新的 AI 对话线程并设为活跃线程。
     * 若线程数超过 {@link #MAX_AI_THREADS}，自动删除最旧的线程。
     *
     * @param threadId 线程 ID（通常为 UUID）
     * @param title    线程标题（可为 null，后续由首条消息自动生成）
     * @return 新建的 AiThread
     */
    public AiThread createAiThread(String threadId, String title) {
        AiThread thread = new AiThread(threadId, title != null ? title : "新对话");
        aiThreads.put(threadId, thread);
        activeThreadId = threadId;
        pruneAiThreadsIfNeeded();
        return thread;
    }

    public AiThread restoreAiThread(String threadId, String title, long createdAt, long lastActiveAt) {
        AiThread thread = new AiThread(threadId, title != null ? title : "历史对话", createdAt, lastActiveAt);
        aiThreads.put(threadId, thread);
        activeThreadId = threadId;
        pruneAiThreadsIfNeeded();
        return thread;
    }

    /**
     * 获取指定线程，不存在时返回 null。
     */
    public AiThread getAiThread(String threadId) {
        if (threadId == null) return null;
        return aiThreads.get(threadId);
    }

    /**
     * 获取当前活跃线程，不存在时返回 null。
     */
    public AiThread getActiveThread() {
        if (activeThreadId == null) return null;
        return aiThreads.get(activeThreadId);
    }

    /**
     * 切换活跃线程（线程必须已存在）。
     *
     * @return 切换后的线程，不存在时返回 null
     */
    public AiThread switchActiveThread(String threadId) {
        AiThread thread = aiThreads.get(threadId);
        if (thread != null) activeThreadId = threadId;
        return thread;
    }

    public String getActiveThreadId() { return activeThreadId; }

    /**
     * 返回所有线程，按 lastActiveAt 倒序排列（最近活跃的在前）。
     */
    public List<AiThread> listAiThreads() {
        return aiThreads.values().stream()
                .sorted(Comparator.comparingLong(AiThread::getLastActiveAt).reversed())
                .collect(Collectors.toList());
    }

    /**
     * 删除指定线程。若删除的是活跃线程，自动切换到最近活跃的其他线程。
     */
    public void removeAiThread(String threadId) {
        AiThread removed = aiThreads.remove(threadId);
        if (removed != null) removed.stop(); // 确保后端任务停止
        if (threadId.equals(activeThreadId)) {
            // 切换到最近活跃的其他线程
            activeThreadId = aiThreads.values().stream()
                    .max(Comparator.comparingLong(AiThread::getLastActiveAt))
                    .map(AiThread::getThreadId)
                    .orElse(null);
        }
    }

    /** 超出上限时删除最旧的线程（按 lastActiveAt 升序）。 */
    private void pruneAiThreadsIfNeeded() {
        if (aiThreads.size() <= MAX_AI_THREADS) return;
        aiThreads.values().stream()
                .sorted(Comparator.comparingLong(AiThread::getLastActiveAt))
                .limit(aiThreads.size() - MAX_AI_THREADS)
                .forEach(t -> {
                    aiThreads.remove(t.getThreadId());
                    t.stop();
                });
    }

    // ── 最后活跃时间 ──────────────────────────────────────────────────────────

    public long getLastActiveTime()  { return lastActiveTime; }
    public void touchLastActiveTime() { lastActiveTime = System.currentTimeMillis(); }

    // ── 缓存模式 ──────────────────────────────────────────────────────────────

    public boolean isCacheMode()                  { return cacheMode; }
    public void    setCacheMode(boolean cacheMode) { this.cacheMode = cacheMode; }
    public String  getPuppetId()                   { return puppetId; }
    public void    setPuppetId(String puppetId)    { this.puppetId = puppetId; }

    // ── 基础属性 ──────────────────────────────────────────────────────────────

    public String getCreateByUser()                    { return createByUser; }
    public void   setCreateByUser(String createByUser) { this.createByUser = createByUser; }

    public String getProjectId()                 { return projectId; }
    public void   setProjectId(String projectId) { this.projectId = projectId; }

    public String getSessionId()                 { return sessionId; }
    public void   setSessionId(String sessionId) { this.sessionId = sessionId; }

    public Long   getUpdateTime()                { return updateTime; }
    public void   setUpdateTime(Long updateTime) { this.updateTime = updateTime; }

    public AbstractPuppetNode getPuppetNode() { return puppetNode; }

    public void setPuppetNode(AbstractPuppetNode node) {
        if (this.puppetNode instanceof HostScopedCapable previous) {
            previous.setHostIdChangeListener(null);
        }
        this.puppetNode = node;
        if (node instanceof HostScopedCapable hostScoped) {
            hostScoped.setHostIdChangeListener(this::onNodeHostIdChanged);
        }
    }

    public List<String> getCapabilities() {
        return PuppetNodeCapabilityRegistry.listSupported(puppetNode);
    }

    public List<CapabilityStatus> getCapabilityStatuses() {
        return PuppetNodeCapabilityRegistry.listStatuses(puppetNode);
    }

    public RuntimeProfile getRuntimeProfile() {
        return puppetNode != null ? puppetNode.getRuntimeProfile() : null;
    }

    /** Resolve the persisted Puppet identity for both live and cache sessions. */
    public String resolvePuppetId() {
        if (puppetNode != null && puppetNode.getPuppet() != null) {
            String livePuppetId = puppetNode.getPuppet().getPuppetId();
            if (livePuppetId != null && !livePuppetId.isBlank()) {
                return livePuppetId;
            }
        }
        return puppetId != null && !puppetId.isBlank() ? puppetId : null;
    }

    // ── HostId 管理 ───────────────────────────────────────────────────────────

    public String getCurrentHostId() { return currentHostId; }

    /**
     * Bind the session to one backend instance during session creation.
     * A session cannot be rebound because its caches and AI context are HostId scoped.
     */
    public synchronized void bindHostId(String currentHostId) {
        if (currentHostId == null || currentHostId.isBlank()) {
            throw new IllegalArgumentException("HostId 不能为空");
        }
        String normalized = currentHostId.trim();
        if (this.currentHostId != null && !Objects.equals(this.currentHostId, normalized)) {
            throw new IllegalStateException("session 已绑定其他 HostId，不能切换");
        }
        applyCurrentHostId(normalized);
        if (puppetNode instanceof HostScopedCapable hostScopedNode
                && !Objects.equals(hostScopedNode.getHostId(), normalized)) {
            hostScopedNode.setHostId(normalized);
        }
    }

    private synchronized void onNodeHostIdChanged(String hostId) {
        if (currentHostId != null && !Objects.equals(currentHostId, hostId)) {
            throw new IllegalStateException("底层节点 HostId 与 session 绑定不一致");
        }
        applyCurrentHostId(hostId);
    }

    private void applyCurrentHostId(String hostId) {
        boolean changed = !Objects.equals(this.currentHostId, hostId);
        this.currentHostId = hostId;
        if (!changed) return;
        if (basicInfoMap != null) basicInfoMap.clear();
        if (aiContextCache != null) aiContextCache.clear();
        lastActiveTime = System.currentTimeMillis();
    }

    // ── BasicInfo ─────────────────────────────────────────────────────────────

    public Map<String, Object> getBasicInfo(String hostId) {
        if (basicInfoMap == null || hostId == null) return null;
        return basicInfoMap.get(hostId);
    }

    public void setBasicInfo(String hostId, Map<String, Object> basicInfo) {
        if (basicInfoMap == null) basicInfoMap = new HashMap<>();
        if (hostId != null) basicInfoMap.put(hostId, basicInfo);
    }

    // ── AI 上下文缓存（会话级，跨线程共享）────────────────────────────────────

    public BoundedTtlCache getAiContextCache() {
        BoundedTtlCache cache = aiContextCache;
        if (cache == null) {
            synchronized (this) {
                cache = aiContextCache;
                if (cache == null) {
                    cache = new BoundedTtlCache(200, 10, TimeUnit.MINUTES);
                    aiContextCache = cache;
                }
            }
        }
        return cache;
    }

    public Object getAiContextValue(String key) {
        if (key == null) return null;
        return getAiContextCache().get(key);
    }

    public void putAiContextValue(String key, Object value) {
        if (key == null || value == null) return;
        getAiContextCache().put(key, value);
    }

    public Object removeAiContextValue(String key) {
        if (key == null) return null;
        return getAiContextCache().remove(key);
    }

    /** 删除所有 key 以 prefix 开头的缓存条目（前缀失效）。 */
    public void removeAiContextByPrefix(String prefix) {
        if (prefix == null) return;
        getAiContextCache().removeByPrefix(prefix);
    }

    // ── 侦察摘要 ──────────────────────────────────────────────────────────────

    public String getReconSummary() { return reconSummary; }

    public synchronized void setReconSummary(String reconSummary) {
        this.reconSummary = reconSummary;
        reconSummaryDigestDirty.set(true);
    }

    public synchronized void appendReconSummary(String content) {
        if (content == null || content.isBlank()) return;
        if (this.reconSummary == null || this.reconSummary.isBlank()) {
            this.reconSummary = content;
        } else {
            this.reconSummary = this.reconSummary + "\n\n" + content;
        }
        reconSummaryDigestDirty.set(true);
    }

    public boolean hasReconSummary() {
        return reconSummary != null && !reconSummary.isBlank();
    }

    public String  getReconSummaryDigest()              { return reconSummaryDigest; }
    public void    setReconSummaryDigest(String digest)  {
        this.reconSummaryDigest = digest;
        reconSummaryDigestDirty.set(false);
    }
    public boolean isReconSummaryDigestDirty()           { return reconSummaryDigestDirty.get(); }
    public boolean hasFreshReconSummaryDigest() {
        return reconSummaryDigest != null && !reconSummaryDigest.isBlank() && !reconSummaryDigestDirty.get();
    }

    public boolean isAutoAppendRecon()                        { return autoAppendRecon; }
    public void    setAutoAppendRecon(boolean autoAppendRecon) { this.autoAppendRecon = autoAppendRecon; }

    // ── 连接链路 ──────────────────────────────────────────────────────────────

    public List<Map<String, Object>> buildConnLinkChain(Function<String, Puppet> findPuppetById) {
        List<Map<String, Object>> chain = new ArrayList<>();
        if (findPuppetById == null) return chain;
        if (puppetNode == null || puppetNode.getPuppet() == null) return chain;
        Puppet p = puppetNode.getPuppet();
        int depth = 0;
        final int maxDepth = 100;
        while (p != null && depth < maxDepth) {
            Map<String, Object> item = new HashMap<>();
            item.put("puppetId",       p.getPuppetId());
            item.put("puppetName",     p.getPuppetName());
            item.put("connLink",       p.getConnLink());
            item.put("parentPuppetId", p.getParentPuppetId());
            item.put("protocol",       p.getProtocol());
            chain.add(item);
            String parentId = p.getParentPuppetId();
            if (parentId == null || parentId.isBlank() || "root".equals(parentId)) break;
            p = findPuppetById.apply(parentId);
            depth++;
        }
        return chain;
    }

    // ── 私有辅助 ──────────────────────────────────────────────────────────────

    private void createSessionCacheDirectory(String sessionName, String userId) {
        File root = new File(LeoConfig.getVfsPath());
        File sessionsRoot;
        if (userId != null && !userId.isBlank()) {
            sessionsRoot = new File(new File(new File(root, "users"), userId.trim()), "sessions");
        } else {
            sessionsRoot = new File(root, "sessions");
        }
        if (!sessionsRoot.exists()) sessionsRoot.mkdirs();
        File session = new File(sessionsRoot, sessionName);
        if (!session.exists()) session.mkdirs();
    }

    // ── 资源释放 ──────────────────────────────────────────────────────────────

    /**
     * 关闭会话并释放底层资源（Communication 连接、AI 线程等）。
     * 在会话被驱逐或手动移除时调用。
     */
    public void close() {
        // 停止所有 AI 线程
        for (AiThread thread : aiThreads.values()) {
            try { thread.stop(); } catch (Exception ignored) {}
        }
        aiThreads.clear();

        // 关闭底层通信连接
        if (puppetNode != null) {
            try { puppetNode.close(); } catch (Exception ignored) {}
        }

        // 清理缓存
        if (aiContextCache != null) {
            aiContextCache.clear();
        }
    }

}
