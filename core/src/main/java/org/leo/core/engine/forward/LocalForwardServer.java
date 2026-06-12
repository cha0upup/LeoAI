package org.leo.core.engine.forward;

import org.leo.core.engine.socks5.Socks5ProxyStatistics;
import org.leo.core.puppet.impl.JavaPuppetNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.ServerSocket;
import java.net.Socket;

/**
 * 本地端口转发服务器。
 * 监听本地 localPort，将所有连接透明转发到 puppet 端的 targetHost:targetPort。
 * 类似 ssh -L localPort:targetHost:targetPort。
 */
public class LocalForwardServer implements Runnable {

    private static final Logger logger = LoggerFactory.getLogger(LocalForwardServer.class);
    private static final int MAX_CONNS = 200;

    private final JavaPuppetNode javaPuppetNode;
    private final int localPort;
    private final String targetHost;
    private final int targetPort;
    private volatile boolean running;
    private ServerSocket serverSocket;
    private Thread acceptThread;
    private final Socks5ProxyStatistics statistics;

    public LocalForwardServer(JavaPuppetNode javaPuppetNode, int localPort,
                               String targetHost, int targetPort) {
        this.javaPuppetNode = javaPuppetNode;
        this.localPort = localPort;
        this.targetHost = targetHost;
        this.targetPort = targetPort;
        this.statistics = new Socks5ProxyStatistics(localPort);
    }

    public int getLocalPort()    { return localPort; }
    public String getTargetHost() { return targetHost; }
    public int getTargetPort()   { return targetPort; }
    public boolean isRunning()   { return running; }
    public Socks5ProxyStatistics getStatistics() { return statistics; }

    public void start() throws Exception {
        if (running) return;
        serverSocket = new ServerSocket(localPort);
        running = true;
        acceptThread = new Thread(this);
        acceptThread.setName("LocalForwardServer-" + localPort);
        acceptThread.setDaemon(true);
        acceptThread.start();
        logger.info("本地端口转发已启动: 127.0.0.1:{} -> puppet:{}:{}", localPort, targetHost, targetPort);
    }

    public void stop() {
        running = false;
        try { if (serverSocket != null) serverSocket.close(); } catch (Exception ignored) {}
        if (statistics != null) statistics.reset();
        logger.info("本地端口转发已停止，本地端口: {}", localPort);
    }

    @Override
    public void run() {
        while (running) {
            try {
                Socket client = serverSocket.accept();
                // 连接数上限检查
                Socks5ProxyStatistics.StatisticsSnapshot snap = statistics.getSnapshot();
                if (snap.activeConnections >= MAX_CONNS) {
                    logger.warn("LocalForward 连接数已达上限 {}，拒绝新连接", MAX_CONNS);
                    try { client.close(); } catch (Exception ignored) {}
                    continue;
                }
                client.setTcpNoDelay(true);
                Thread t = new Thread(new LocalForwardHandleClientThread(
                        client, javaPuppetNode, targetHost, targetPort, statistics));
                t.setDaemon(true);
                t.start();
            } catch (Exception e) {
                if (running) logger.error("本地端口转发 accept 异常", e);
            }
        }
    }
}
