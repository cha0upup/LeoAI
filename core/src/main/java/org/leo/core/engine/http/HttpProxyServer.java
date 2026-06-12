package org.leo.core.engine.http;

import org.leo.core.engine.socks5.Socks5ProxyStatistics;
import org.leo.core.puppet.impl.JavaPuppetNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.ServerSocket;
import java.net.Socket;

/**
 * HTTP 代理服务器（本地监听）。
 * 支持 HTTP CONNECT 隧道（HTTPS）和普通 HTTP 请求转发，
 * 通过 ProxyForwardComponent 在 puppet 端建立实际连接。
 */
public class HttpProxyServer implements Runnable {

    private static final Logger logger = LoggerFactory.getLogger(HttpProxyServer.class);
    private static final int MAX_CONNS = 200;

    private final JavaPuppetNode javaPuppetNode;
    private final int listenPort;
    private volatile boolean running;
    private ServerSocket serverSocket;
    private Thread acceptThread;
    private final Socks5ProxyStatistics statistics;

    public HttpProxyServer(JavaPuppetNode javaPuppetNode, int listenPort) {
        this.javaPuppetNode = javaPuppetNode;
        this.listenPort = listenPort;
        this.statistics = new Socks5ProxyStatistics(listenPort);
    }

    public int getListenPort() { return listenPort; }
    public boolean isRunning() { return running; }

    public void start() throws Exception {
        if (running) return;
        serverSocket = new ServerSocket(listenPort);
        running = true;
        acceptThread = new Thread(this);
        acceptThread.setName("HttpProxyServer-" + listenPort);
        acceptThread.setDaemon(true);
        acceptThread.start();
        logger.info("HTTP 代理服务器已启动，监听端口: {}", listenPort);
    }

    public void stop() {
        running = false;
        try { if (serverSocket != null) serverSocket.close(); } catch (Exception ignored) {}
        if (statistics != null) statistics.reset();
        logger.info("HTTP 代理服务器已停止，端口: {}", listenPort);
    }

    public Socks5ProxyStatistics getStatistics() { return statistics; }

    @Override
    public void run() {
        while (running) {
            try {
                Socket client = serverSocket.accept();
                // 连接数上限检查
                Socks5ProxyStatistics.StatisticsSnapshot snap = statistics.getSnapshot();
                if (snap.activeConnections >= MAX_CONNS) {
                    logger.warn("HTTP 代理连接数已达上限 {}，拒绝新连接", MAX_CONNS);
                    try { client.close(); } catch (Exception ignored) {}
                    continue;
                }
                client.setTcpNoDelay(true);
                client.setSoTimeout(30000);
                Thread t = new Thread(new HttpProxyHandleClientThread(client, javaPuppetNode, statistics));
                t.setDaemon(true);
                t.start();
            } catch (Exception e) {
                if (running) logger.error("HTTP 代理 accept 异常", e);
            }
        }
    }
}
