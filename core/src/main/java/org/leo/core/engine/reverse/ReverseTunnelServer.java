package org.leo.core.engine.reverse;

import org.leo.core.engine.socks5.Socks5ProxyStatistics;
import org.leo.core.engine.proxy.ProxySessionResources;
import org.leo.core.agent.capability.ComponentInvokeCapable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.Socket;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 反向隧道服务器（C2 侧）。
 *
 * 流程：
 *   1. 通过 invokeComponent 在 agent 端开 ServerSocket 监听 remoteListenPort
 *   2. 启动 AcceptPollThread 周期性轮询 agent 端的新连接
 *   3. 每个新连接由 dialerExecutor 异步在 C2 侧拨号到 forwardHost:forwardPort，
 *      并启动 ReverseReadThread / ReverseWriteThread 双向中继
 *
 * 类似 ssh -R remoteListenPort:forwardHost:forwardPort，方向与 LocalForward 相反。
 */
public class ReverseTunnelServer {

    private static final Logger logger = LoggerFactory.getLogger(ReverseTunnelServer.class);

    // 与 ReverseTunnelComponent 对齐
    static final int OP_START_LISTEN = 0;
    static final int OP_STOP_LISTEN  = 1;
    static final int OP_ACCEPT       = 2;
    static final int OP_READ         = 3;
    static final int OP_WRITE        = 4;
    static final int OP_CLOSE        = 5;

    private static final long DEFAULT_POLL_INTERVAL_MS = 150L;
    private static final int  DEFAULT_MAX_CONNS        = 200;
    private static final int  DEFAULT_DIALER_THREADS   = 8;

    private final ComponentInvokeCapable agentNode;
    private final String listenId;
    private final int remoteListenPort;
    private final String bindAddr;
    private final String forwardHost;
    private final int forwardPort;
    private final long pollIntervalMs;
    private final int maxConns;
    private final ProxySessionResources resources;
    private final Object lifecycleLock = new Object();

    private volatile boolean running;
    private Thread pollThread;
    private ThreadPoolExecutor dialerExecutor;
    private Runnable onDead;

    private final Socks5ProxyStatistics statistics;
    private final long startTime = System.currentTimeMillis();

    /** connId -> 本地 Socket（指向 forwardHost:forwardPort） */
    private final ConcurrentHashMap<String, Socket> localConns =
            new ConcurrentHashMap<String, Socket>();
    private final ConcurrentHashMap<String, DialerTask> dialerTasks = new ConcurrentHashMap<>();
    private final Set<String> pendingConns = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, Socket> pendingSockets = new ConcurrentHashMap<>();

    public ReverseTunnelServer(ComponentInvokeCapable agentNode,
                                int remoteListenPort,
                                String bindAddr,
                                String forwardHost,
                                int forwardPort) {
        this(agentNode, remoteListenPort, bindAddr, forwardHost, forwardPort,
                DEFAULT_POLL_INTERVAL_MS, DEFAULT_MAX_CONNS);
    }

    public ReverseTunnelServer(ComponentInvokeCapable agentNode,
                                int remoteListenPort,
                                String bindAddr,
                                String forwardHost,
                                int forwardPort,
                                long pollIntervalMs) {
        this(agentNode, remoteListenPort, bindAddr, forwardHost, forwardPort,
                pollIntervalMs, DEFAULT_MAX_CONNS);
    }

    public ReverseTunnelServer(ComponentInvokeCapable agentNode,
                                int remoteListenPort,
                                String bindAddr,
                                String forwardHost,
                                int forwardPort,
                                long pollIntervalMs,
                                int maxConns) {
        this(agentNode, remoteListenPort, bindAddr, forwardHost, forwardPort, pollIntervalMs, maxConns, null);
    }

    public ReverseTunnelServer(ComponentInvokeCapable agentNode, int remoteListenPort, String bindAddr,
                               String forwardHost, int forwardPort, ProxySessionResources resources) {
        this(agentNode, remoteListenPort, bindAddr, forwardHost, forwardPort,
                DEFAULT_POLL_INTERVAL_MS, DEFAULT_MAX_CONNS, resources);
    }

    public ReverseTunnelServer(ComponentInvokeCapable agentNode, int remoteListenPort, String bindAddr,
                               String forwardHost, int forwardPort, long pollIntervalMs, int maxConns,
                               ProxySessionResources resources) {
        this.resources = resources;
        this.agentNode = agentNode;
        this.remoteListenPort = remoteListenPort;
        this.bindAddr = (bindAddr == null || bindAddr.length() == 0) ? "127.0.0.1" : bindAddr;
        this.forwardHost = forwardHost;
        this.forwardPort = forwardPort;
        this.pollIntervalMs = pollIntervalMs > 0 ? pollIntervalMs : DEFAULT_POLL_INTERVAL_MS;
        this.maxConns = maxConns > 0 ? maxConns : DEFAULT_MAX_CONNS;
        this.listenId = UUID.randomUUID().toString().replace("-", "");
        // 复用统计模型；port 字段填 agent 侧监听端口
        this.statistics = new Socks5ProxyStatistics(remoteListenPort);
    }

    public String getListenId() {
        return listenId;
    }

    public int getRemoteListenPort() {
        return remoteListenPort;
    }

    public String getBindAddr() {
        return bindAddr;
    }

    public String getForwardHost() {
        return forwardHost;
    }

    public int getForwardPort() {
        return forwardPort;
    }

    public boolean isRunning() {
        return running;
    }

    public long getStartTime() {
        return startTime;
    }

    public Socks5ProxyStatistics getStatistics() {
        return statistics;
    }

    /**
     * 注册"隧道死亡"回调。
     * 当 AcceptPollLoop 检测到 agent 端 listenId 不再存在（404）时触发，
     * 由调用方（ComponentInvokeCapable）负责从 reverseTunnels map 中移除自身。
     */
    public void setOnDead(Runnable onDead) {
        this.onDead = onDead;
    }

    /**
     * 启动反向隧道：先在 agent 端开监听，成功后再启动轮询线程。
     * 若后续初始化失败，主动 STOP_LISTEN 回滚，避免 agent 端孤儿 ServerSocket。
     */
    public void start() throws Exception {
        start(() -> true);
    }

    public void start(BooleanSupplier stillRequested) throws Exception {
        synchronized (lifecycleLock) {
            if (!stillRequested.getAsBoolean()) throw new IllegalStateException("reverse tunnel start cancelled");
            if (running) return;
            if (resources != null && resources.isClosed()) throw new IllegalStateException("proxy session closed");
            Map<String, Object> params = listenerParams(OP_START_LISTEN);
            params.put("listenPort", remoteListenPort);
            params.put("bindAddr", bindAddr);
            Map<String, Object> response = agentNode.invokeComponent("ReverseTunnelComponent", params);
            if (!hasResponseCode(response, 200)) {
                throw new IllegalStateException("agent START_LISTEN failed: "
                        + (response == null ? "unknown" : response.get("msg")));
            }
            try {
                synchronized (this) {
                    if (!stillRequested.getAsBoolean()) throw new IllegalStateException("reverse tunnel start cancelled");
                    if (resources != null && resources.isClosed()) throw new IllegalStateException("proxy session closed");
                    dialerExecutor = resources == null
                            ? ProxySessionResources.newDialerExecutor(Math.min(DEFAULT_DIALER_THREADS, maxConns), maxConns)
                            : resources.dialers();
                    running = true;
                    pollThread = new Thread(new AcceptPollLoop(), "ReverseTunnel-Poll-" + remoteListenPort);
                    pollThread.setDaemon(true);
                    pollThread.start();
                }
            } catch (Exception error) {
                synchronized (this) {
                    running = false;
                    cleanupLocalResources();
                }
                try {
                    stopRemoteListener();
                } catch (Exception rollbackError) {
                    logger.warn("start 回滚时 STOP_LISTEN 失败: {}", rollbackError.getMessage());
                }
                throw error;
            }
            logger.info("反向隧道启动: agent:{}:{} -> {}:{} (listenId={})",
                    bindAddr, remoteListenPort, forwardHost, forwardPort, listenId);
        }
    }

    /** Local state locks never cover transport RPCs. */
    public void stop() {
        synchronized (lifecycleLock) {
            boolean wasRunning;
            synchronized (this) {
                wasRunning = running;
                running = false;
                Thread polling = pollThread;
                pollThread = null;
                if (polling != null && polling != Thread.currentThread()) polling.interrupt();
                cleanupLocalResources();
            }
            if (wasRunning) {
                try {
                    stopRemoteListener();
                } catch (Exception error) {
                    logger.debug("STOP_LISTEN 调用异常: {}", error.getMessage());
                }
                logger.info("反向隧道停止: listenId={}", listenId);
            }
        }
    }

    private synchronized boolean handleRemoteListenerGone() {
        if (!running || pollThread != Thread.currentThread()) return false;
        running = false;
        pollThread = null;
        cleanupLocalResources();
        return true;
    }

    private void cleanupLocalResources() {
        for (Socket socket : localConns.values()) {
            closeSocket(socket);
        }
        localConns.clear();
        for (Socket socket : pendingSockets.values()) {
            closeSocket(socket);
        }
        pendingSockets.clear();
        ThreadPoolExecutor executor = dialerExecutor;
        for (DialerTask task : dialerTasks.values()) {
            if (executor != null) executor.remove(task);
            task.cancel();
        }
        dialerTasks.clear();
        pendingConns.clear();

        dialerExecutor = null;
        if (executor != null && resources == null) {
            executor.shutdownNow();
        }
        if (statistics != null) {
            statistics.reset();
        }
    }

    ComponentInvokeCapable getAgentNode() {
        return agentNode;
    }

    ConcurrentHashMap<String, Socket> getLocalConns() {
        return localConns;
    }

    synchronized boolean registerPendingConnection(String connId, Socket socket, DialerTask task) {
        if (!running || dialerTasks.get(connId) != task) {
            return false;
        }
        pendingSockets.put(connId, socket);
        return true;
    }

    synchronized boolean registerLocalConnection(String connId, Socket socket, String clientAddr, DialerTask task) {
        if (!running || dialerTasks.get(connId) != task) {
            return false;
        }
        localConns.put(connId, socket);
        pendingSockets.remove(connId, socket);
        pendingConns.remove(connId);
        statistics.addConnection(connId, forwardHost, forwardPort, clientAddr != null ? clientAddr : "");
        return true;
    }

    synchronized void completeLocalConnection(String connId, Socket socket) {
        pendingConns.remove(connId);
        if (socket != null) {
            pendingSockets.remove(connId, socket);
            localConns.remove(connId, socket);
        } else {
            pendingSockets.remove(connId);
            localConns.remove(connId);
        }
        if (statistics != null) {
            statistics.removeConnection(connId);
        }
        closeSocket(socket);
    }

    synchronized void recordUpload(String connId, int bytes) {
        if (running && localConns.containsKey(connId)) {
            statistics.addUploadBytes(connId, bytes);
        }
    }

    synchronized void recordDownload(String connId, int bytes) {
        if (running && localConns.containsKey(connId)) {
            statistics.addDownloadBytes(connId, bytes);
        }
    }

    synchronized void dialerCompleted(String connId, DialerTask task) {
        dialerTasks.remove(connId, task);
    }

    private void scheduleDialer(String connId, String clientAddr) {
        synchronized (this) {
            if (pollThread != Thread.currentThread()) return;
            if (localConns.containsKey(connId) || pendingConns.contains(connId)) return;
            if (running && localConns.size() + pendingConns.size() < maxConns && dialerExecutor != null) {
                ProxySessionResources.ConnectionPermit permit = resources == null
                        ? null : resources.tryAcquireConnection();
                if (resources == null || permit != null) {
                    DialerTask task = new DialerTask(this, connId, clientAddr, permit);
                    pendingConns.add(connId);
                    dialerTasks.put(connId, task);
                    try {
                        dialerExecutor.execute(task);
                        return;
                    } catch (RejectedExecutionException error) {
                        task.cancel();
                        pendingConns.remove(connId);
                    }
                }
            }
        }
        // Rejection cleanup may block on the transport, so keep it outside the state lock.
        closeRemoteConnection(connId);
        if (running) logger.debug("反向隧道容量已满，已关闭连接: connId={}", connId);
    }

    void closeRemoteConnection(String connId) {
        try {
            Map<String, Object> params = new HashMap<String, Object>();
            params.put("op", Integer.valueOf(OP_CLOSE));
            params.put("connId", connId);
            agentNode.invokeComponent("ReverseTunnelComponent", params);
        } catch (Exception ignored) {}
    }

    private Map<String, Object> listenerParams(int operation) {
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("op", Integer.valueOf(operation));
        params.put("listenId", listenId);
        return params;
    }

    private void stopRemoteListener() throws Exception {
        agentNode.invokeComponent("ReverseTunnelComponent", listenerParams(OP_STOP_LISTEN));
    }

    private static boolean hasResponseCode(Map<String, Object> response, int expectedCode) {
        return responseCode(response) == expectedCode;
    }

    static int responseCode(Map<String, Object> response) {
        Object code = response != null ? response.get("code") : null;
        return code instanceof Number ? ((Number) code).intValue() : -1;
    }

    private static void closeSocket(Socket socket) {
        if (socket == null) {
            return;
        }
        try {
            socket.close();
        } catch (Exception ignored) {
            // 资源清理阶段无需覆盖原始错误。
        }
    }

    /**
     * accept 轮询线程。
     */
    private class AcceptPollLoop implements Runnable {
        @Override
        public void run() {
            long delay = pollIntervalMs;
            long maxDelay = Math.max(pollIntervalMs, pollIntervalMs >= 125 ? 1_000L : pollIntervalMs * 8);
            while (running) {
                boolean accepted = false;
                try {
                    int count = pollOnce();
                    if (count < 0) break;
                    accepted = count > 0;
                } catch (Exception error) {
                    if (running) logger.debug("accept 轮询异常: {}", error.getMessage());
                }
                delay = accepted || !localConns.isEmpty() || !pendingConns.isEmpty()
                        ? pollIntervalMs : (delay >= maxDelay / 2 ? maxDelay : delay * 2);
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException ignored) {
                    break;
                }
            }
        }
    }

    private int pollOnce() throws Exception {
        Map<String, Object> response = agentNode.invokeComponent(
                "ReverseTunnelComponent", listenerParams(OP_ACCEPT));
        synchronized (this) {
            if (!running || pollThread != Thread.currentThread()) return -1;
        }
        if (hasResponseCode(response, 200)) return scheduleAcceptedConnections(response.get("newConns"));
        if (!hasResponseCode(response, 404)) return 0;
        logger.warn("反向隧道 listenId 在 agent 端不存在，停止轮询: listenId={}", listenId);
        if (handleRemoteListenerGone()) notifyDeadListener();
        return -1;
    }

    private int scheduleAcceptedConnections(Object value) {
        if (!(value instanceof List<?> connections)) return 0;
        int count = 0;
        for (Object item : connections) {
            if (!(item instanceof Map<?, ?> connection) || connection.get("connId") == null) continue;
            Object clientAddr = connection.get("clientAddr");
            scheduleDialer(String.valueOf(connection.get("connId")),
                    clientAddr == null ? null : String.valueOf(clientAddr));
            count++;
        }
        return count;
    }

    private void notifyDeadListener() {
        Runnable callback = onDead;
        if (callback == null) {
            return;
        }
        try {
            callback.run();
        } catch (Exception ignored) {
            // 回调异常不影响隧道资源清理。
        }
    }
}
