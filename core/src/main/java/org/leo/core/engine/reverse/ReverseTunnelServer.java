package org.leo.core.engine.reverse;

import org.leo.core.engine.socks5.Socks5ProxyStatistics;
import org.leo.core.agent.capability.ComponentInvokeCapable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.Socket;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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

    private volatile boolean running;
    private Thread pollThread;
    private ThreadPoolExecutor dialerExecutor;
    private Runnable onDead;

    private final Socks5ProxyStatistics statistics;
    private final long startTime = System.currentTimeMillis();

    /** connId -> 本地 Socket（指向 forwardHost:forwardPort） */
    private final ConcurrentHashMap<String, Socket> localConns =
            new ConcurrentHashMap<String, Socket>();
    private final Set<String> pendingConns = ConcurrentHashMap.newKeySet();

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
    public synchronized void start() throws Exception {
        if (running) {
            return;
        }

        Map<String, Object> params = listenerParams(OP_START_LISTEN);
        params.put("listenPort", Integer.valueOf(remoteListenPort));
        params.put("bindAddr", bindAddr);
        Map<String, Object> res = agentNode.invokeComponent("ReverseTunnelComponent", params);
        if (!hasResponseCode(res, 200)) {
            String msg = res != null ? String.valueOf(res.get("msg")) : "unknown";
            throw new RuntimeException("agent START_LISTEN failed: " + msg);
        }

        // START_LISTEN 已成功，后续初始化若失败必须回滚
        try {
            running = true;
            int dialerThreads = Math.max(1, Math.min(DEFAULT_DIALER_THREADS, maxConns));
            dialerExecutor = new ThreadPoolExecutor(
                    dialerThreads,
                    dialerThreads,
                    60L,
                    TimeUnit.SECONDS,
                    new ArrayBlockingQueue<Runnable>(maxConns),
                    new ThreadFactory() {
                private final AtomicInteger idx = new AtomicInteger();

                @Override
                public Thread newThread(Runnable r) {
                    String threadName = "ReverseTunnel-Dialer-" + remoteListenPort
                            + "-" + idx.incrementAndGet();
                    Thread t = new Thread(r, threadName);
                    t.setDaemon(true);
                    return t;
                }
            }, new ThreadPoolExecutor.AbortPolicy());
            dialerExecutor.allowCoreThreadTimeOut(true);

            pollThread = new Thread(new AcceptPollLoop(), "ReverseTunnel-Poll-" + remoteListenPort);
            pollThread.setDaemon(true);
            pollThread.start();
        } catch (Exception e) {
            // 回滚：让 agent 端释放已绑定的端口
            running = false;
            cleanupLocalResources();
            try {
                stopRemoteListener();
            } catch (Exception rollbackEx) {
                logger.warn("start 回滚时 STOP_LISTEN 失败: {}", rollbackEx.getMessage());
            }
            throw e;
        }

        logger.info("反向隧道启动: agent:{}:{} -> {}:{} (listenId={})",
                bindAddr, remoteListenPort, forwardHost, forwardPort, listenId);
    }

    /**
     * 停止反向隧道：先停轮询，再关 agent 监听，最后清理本地连接。
     */
    public synchronized void stop() {
        boolean wasRunning = running;
        running = false;
        Thread polling = pollThread;
        pollThread = null;
        if (polling != null && polling != Thread.currentThread()) {
            polling.interrupt();
        }

        if (wasRunning) {
            try {
                stopRemoteListener();
            } catch (Exception e) {
                logger.debug("STOP_LISTEN 调用异常: {}", e.getMessage());
            }
        }

        cleanupLocalResources();
        if (wasRunning) {
            logger.info("反向隧道停止: listenId={}", listenId);
        }
    }

    private synchronized void handleRemoteListenerGone() {
        if (!running) {
            return;
        }
        running = false;
        pollThread = null;
        cleanupLocalResources();
    }

    private void cleanupLocalResources() {
        for (Socket socket : localConns.values()) {
            closeSocket(socket);
        }
        localConns.clear();
        pendingConns.clear();

        ThreadPoolExecutor executor = dialerExecutor;
        dialerExecutor = null;
        if (executor != null) {
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

    synchronized boolean registerLocalConnection(String connId, Socket socket) {
        if (!running) {
            return false;
        }
        localConns.put(connId, socket);
        pendingConns.remove(connId);
        return true;
    }

    void completeLocalConnection(String connId, Socket socket) {
        pendingConns.remove(connId);
        if (socket != null) {
            localConns.remove(connId, socket);
        } else {
            localConns.remove(connId);
        }
        if (statistics != null) {
            statistics.removeConnection(connId);
        }
        closeSocket(socket);
    }

    private void scheduleDialer(String connId, String clientAddr) {
        if (!pendingConns.add(connId)) {
            return;
        }
        if (!running || localConns.size() + pendingConns.size() > maxConns) {
            pendingConns.remove(connId);
            closeRemoteConnection(connId);
            if (running) {
                logger.warn("反向隧道连接数已达上限 {}, 拒绝新连接 connId={}", maxConns, connId);
            }
            return;
        }
        ThreadPoolExecutor executor = dialerExecutor;
        if (executor == null) {
            pendingConns.remove(connId);
            closeRemoteConnection(connId);
            return;
        }
        try {
            executor.execute(new DialerTask(this, connId, clientAddr));
        } catch (RejectedExecutionException error) {
            pendingConns.remove(connId);
            closeRemoteConnection(connId);
            logger.debug("反向隧道拨号队列繁忙，已关闭连接: connId={}", connId);
        }
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
        if (response == null) {
            return false;
        }
        Object code = response.get("code");
        return code instanceof Number && ((Number) code).intValue() == expectedCode;
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
            while (running) {
                try {
                    if (!pollOnce()) {
                        break;
                    }
                } catch (Exception e) {
                    if (running) {
                        logger.debug("accept 轮询异常: {}", e.getMessage());
                    }
                }
                if (!waitForNextPoll()) {
                    break;
                }
            }
        }
    }

    private boolean pollOnce() throws Exception {
        Map<String, Object> response = agentNode.invokeComponent(
                "ReverseTunnelComponent", listenerParams(OP_ACCEPT));
        if (hasResponseCode(response, 200)) {
            scheduleAcceptedConnections(response.get("newConns"));
            return true;
        }
        if (!hasResponseCode(response, 404)) {
            return true;
        }

        logger.warn("反向隧道 listenId 在 agent 端不存在，停止轮询: listenId={}", listenId);
        handleRemoteListenerGone();
        notifyDeadListener();
        return false;
    }

    private void scheduleAcceptedConnections(Object value) {
        if (!(value instanceof List<?>)) {
            return;
        }
        for (Object item : (List<?>) value) {
            if (!(item instanceof Map<?, ?>)) {
                continue;
            }
            Map<?, ?> connection = (Map<?, ?>) item;
            Object connIdValue = connection.get("connId");
            if (connIdValue == null) {
                continue;
            }
            Object clientAddrValue = connection.get("clientAddr");
            String connId = String.valueOf(connIdValue);
            String clientAddr = clientAddrValue == null ? null : String.valueOf(clientAddrValue);
            scheduleDialer(connId, clientAddr);
        }
    }

    private boolean waitForNextPoll() {
        try {
            Thread.sleep(pollIntervalMs);
            return true;
        } catch (InterruptedException ignored) {
            return running;
        }
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
