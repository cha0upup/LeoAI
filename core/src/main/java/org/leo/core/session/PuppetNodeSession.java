package org.leo.core.session;

import org.leo.core.config.LeoConfig;
import org.leo.core.entity.AiConfirmationRequest;
import org.leo.core.entity.AiExecutionPolicy;
import org.leo.core.entity.AiRuntimeStats;
import org.leo.core.entity.AiSseEvent;
import org.leo.core.entity.AsyncShellTask;
import org.leo.core.entity.Puppet;
import org.leo.core.puppet.AbstractPuppetNode;
import org.leo.core.puppet.impl.JavaPuppetNode;
import org.leo.core.util.BoundedTtlCache;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
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
    private Map<String, Map<String, Object>> basicInfoMap;
    private String currentHostId;
    private Set<String> allHostIds;

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
    /** 缓存模式下关联的 puppetId（javaPuppetNode 为 null 时用于定位持久化目录）。 */
    private volatile String puppetId;

    // ── 会话级 AI 上下文缓存（跨线程共享）────────────────────────────────────
    private volatile BoundedTtlCache aiContextCache;

    // ── 后台异步 Shell 任务 ───────────────────────────────────────────────────
    private final List<AsyncShellTask> asyncShellTasks = new CopyOnWriteArrayList<>();

    /** 最后活跃时间戳（ms），用于 TTL 清理。 */
    private volatile long lastActiveTime = System.currentTimeMillis();

    // ── 构造器 ────────────────────────────────────────────────────────────────

    public PuppetNodeSession(String sessionId, JavaPuppetNode javaPuppetNode, Long updateTime) {
        this.puppetNode = javaPuppetNode;
        this.sessionId = sessionId;
        this.updateTime = updateTime;
        createSessionCacheDirectory(sessionId, null);
    }

    public PuppetNodeSession(String sessionId, JavaPuppetNode javaPuppetNode, Long updateTime, String createByUser) {
        this.puppetNode = javaPuppetNode;
        this.sessionId = sessionId;
        this.updateTime = updateTime;
        this.createByUser = createByUser;
        createSessionCacheDirectory(sessionId, createByUser);
    }

    public PuppetNodeSession(String sessionId, AbstractPuppetNode puppetNode, Long updateTime, String createByUser) {
        this.puppetNode = puppetNode;
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

    // ── 活跃线程委托方法 ────────────────────────────────────

    /** 取消活跃线程所有挂起的确认请求。 */
    public void cancelAllPendingConfirmations() {
        AiThread t = getActiveThread();
        if (t != null) t.cancelAllPendingConfirmations();
    }

    /** 获取活跃线程的 SSE 事件队列。 */
    public LinkedBlockingQueue<AiSseEvent> getAiSseEventQueue() {
        AiThread t = getActiveThread();
        return t != null ? t.getSseEventQueue() : new LinkedBlockingQueue<>();
    }

    /** 获取活跃线程的运行统计。 */
    public AiRuntimeStats getAiRuntimeStats() {
        AiThread t = getActiveThread();
        return t != null ? t.getRuntimeStats() : new AiRuntimeStats();
    }

    /** 获取活跃线程的执行策略。 */
    public AiExecutionPolicy getAiExecutionPolicy() {
        AiThread t = getActiveThread();
        return t != null ? t.getExecutionPolicy() : AiExecutionPolicy.defaultPolicy();
    }

    /** 设置活跃线程的执行策略。 */
    public void setAiExecutionPolicy(AiExecutionPolicy policy) {
        AiThread t = getActiveThread();
        if (t != null) t.setExecutionPolicy(policy);
    }

    /** 活跃线程轮次计数 +1，达到阈值时返回警告文本。 */
    public String incrementAndCheckAiTurnCount() {
        AiThread t = getActiveThread();
        return t != null ? t.incrementAndCheckTurnCount() : null;
    }

    /** 向活跃线程的 SSE 队列推送系统 warn 消息。 */
    public void offerSystemWarn(String message) {
        AiThread t = getActiveThread();
        if (t != null) t.offerSystemWarn(message);
    }

    /** 向活跃线程注册工具确认请求。 */
    public java.util.concurrent.CompletableFuture<Boolean> registerAndAwaitConfirmation(
            AiConfirmationRequest request) {
        AiThread t = getActiveThread();
        if (t != null) return t.registerAndAwaitConfirmation(request);
        java.util.concurrent.CompletableFuture<Boolean> f = new java.util.concurrent.CompletableFuture<>();
        f.complete(false);
        return f;
    }

    /** 完成活跃线程挂起的确认 Future。 */
    public boolean resolveConfirmation(String callId, boolean approved) {
        AiThread t = getActiveThread();
        return t != null && t.resolveConfirmation(callId, approved);
    }

    /** 向活跃线程写入会话级工具授权。 */
    public void grantSessionType(String toolType) {
        AiThread t = getActiveThread();
        if (t != null) t.grantSessionType(toolType);
    }

    /** 开启活跃线程全量授权。 */
    public void grantSessionAll() {
        AiThread t = getActiveThread();
        if (t != null) t.grantSessionAll();
    }

    /** 判断活跃线程指定工具类型是否已授权。 */
    public boolean isSessionGranted(String toolType) {
        AiThread t = getActiveThread();
        return t != null && t.isSessionGranted(toolType);
    }

    /** 返回活跃线程已放行的工具类型集合。 */
    public Set<String> getSessionGrantedTypes() {
        AiThread t = getActiveThread();
        return t != null ? t.getSessionGrantedTypes() : Collections.emptySet();
    }

    /** 返回活跃线程是否处于全量放行状态。 */
    public boolean isSessionGrantedAll() {
        AiThread t = getActiveThread();
        return t != null && t.isSessionGrantedAll();
    }

    /**
     * 重置活跃线程的 AI 状态（停止执行、清空队列、重置统计）。
     */
    public synchronized void resetAiState() {
        AiThread t = getActiveThread();
        if (t != null) {
            t.stop();
            t.getSseEventQueue().clear();
            t.resetRuntimeStats();
            t.setExecutionPolicy(AiExecutionPolicy.defaultPolicy());
            t.resetTurnCount();
            t.resetSessionGrants();
        }
        if (aiContextCache != null) aiContextCache.clear();
        lastActiveTime = System.currentTimeMillis();
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

    public String getSessionId()                 { return sessionId; }
    public void   setSessionId(String sessionId) { this.sessionId = sessionId; }

    public Long   getUpdateTime()                { return updateTime; }
    public void   setUpdateTime(Long updateTime) { this.updateTime = updateTime; }

    public JavaPuppetNode getJavaPuppetNode()                        { return puppetNode instanceof JavaPuppetNode ? (JavaPuppetNode) puppetNode : null; }
    public void           setJavaPuppetNode(JavaPuppetNode node)     { this.puppetNode = node; }

    public AbstractPuppetNode getPuppetNode()                        { return puppetNode; }
    public void               setPuppetNode(AbstractPuppetNode node) { this.puppetNode = node; }

    // ── HostId 管理 ───────────────────────────────────────────────────────────

    public String getCurrentHostId() { return currentHostId; }

    public void setCurrentHostId(String currentHostId) {
        this.currentHostId = currentHostId;
        if (puppetNode instanceof JavaPuppetNode) ((JavaPuppetNode) puppetNode).setHostId(currentHostId);
        if (currentHostId != null && !currentHostId.isBlank()) addHostId(currentHostId);
    }

    public Set<String> getAllHostIds() {
        if (allHostIds == null) allHostIds = new HashSet<>();
        return allHostIds;
    }

    public void setAllHostIds(Set<String> allHostIds) {
        this.allHostIds = allHostIds == null ? new HashSet<>() : new HashSet<>(allHostIds);
    }

    public boolean addHostId(String hostId) {
        if (hostId == null || hostId.isBlank()) return false;
        return getAllHostIds().add(hostId);
    }

    public boolean removeHostId(String hostId) {
        if (hostId == null || allHostIds == null) return false;
        return allHostIds.remove(hostId);
    }

    public boolean containsHostId(String hostId) {
        if (hostId == null || allHostIds == null) return false;
        return allHostIds.contains(hostId);
    }

    public void updateCurrentHostId(String hostId) { setCurrentHostId(hostId); }

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
    public void    invalidateReconSummaryDigest()        { reconSummaryDigestDirty.set(true); }
    public boolean hasFreshReconSummaryDigest() {
        return reconSummaryDigest != null && !reconSummaryDigest.isBlank() && !reconSummaryDigestDirty.get();
    }

    public boolean isAutoAppendRecon()                        { return autoAppendRecon; }
    public void    setAutoAppendRecon(boolean autoAppendRecon) { this.autoAppendRecon = autoAppendRecon; }

    // ── 后台 Shell 任务 ───────────────────────────────────────────────────────

    public List<AsyncShellTask> getAsyncShellTasks() { return asyncShellTasks; }

    public void addAsyncShellTask(AsyncShellTask task) { asyncShellTasks.add(task); }

    public Optional<AsyncShellTask> findAsyncShellTask(String taskId) {
        return asyncShellTasks.stream().filter(t -> t.getTaskId().equals(taskId)).findFirst();
    }

    public int clearFinishedAsyncShellTasks() {
        int before = asyncShellTasks.size();
        asyncShellTasks.removeIf(t ->
            t.getStatus() == AsyncShellTask.TaskStatus.DONE ||
            t.getStatus() == AsyncShellTask.TaskStatus.FAILED ||
            t.getStatus() == AsyncShellTask.TaskStatus.CANCELLED);
        return before - asyncShellTasks.size();
    }

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
            org.leo.core.net.Communication comm = null;
            if (puppetNode instanceof JavaPuppetNode) {
                comm = ((JavaPuppetNode) puppetNode).getCommunication();
            }
            if (comm instanceof java.io.Closeable) {
                try { ((java.io.Closeable) comm).close(); } catch (Exception ignored) {}
            } else if (comm instanceof org.java_websocket.client.WebSocketClient) {
                try { ((org.java_websocket.client.WebSocketClient) comm).close(); } catch (Exception ignored) {}
            }
            // 停止 SOCKS5 代理
            if (puppetNode instanceof JavaPuppetNode) {
                ((JavaPuppetNode) puppetNode).stopSocks5Proxy();
            }
        }

        // 清理缓存
        if (aiContextCache != null) {
            aiContextCache.clear();
        }
    }

}
