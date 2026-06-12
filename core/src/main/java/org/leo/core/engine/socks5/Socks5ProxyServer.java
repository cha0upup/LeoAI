package org.leo.core.engine.socks5;


import org.leo.core.puppet.impl.JavaPuppetNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.ServerSocket;
import java.net.Socket;

public class Socks5ProxyServer implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(Socks5ProxyServer.class);
    private static final int MAX_CONNS = 200;
    private final JavaPuppetNode javaPuppetNode;
    private final int listenPort;
    private volatile boolean running;
    private ServerSocket serverSocket;
    private Thread acceptThread;
    private final Socks5ProxyStatistics statistics;

    public int getListenPort() {
        return listenPort;
    }

    public boolean isRunning() {
        return running;
    }

    public void setRunning(boolean running) {
        this.running = running;
    }

    public ServerSocket getServerSocket() {
        return serverSocket;
    }

    public void setServerSocket(ServerSocket serverSocket) {
        this.serverSocket = serverSocket;
    }

    public Thread getAcceptThread() {
        return acceptThread;
    }

    public void setAcceptThread(Thread acceptThread) {
        this.acceptThread = acceptThread;
    }

    public Socks5ProxyServer(JavaPuppetNode javaPuppetNode, int listenPort) {
        this.javaPuppetNode = javaPuppetNode;
        this.listenPort = listenPort;
        this.statistics = new Socks5ProxyStatistics(listenPort);
    }

    public void start() throws Exception {
        if (running) return;
        serverSocket = new ServerSocket(listenPort);
        running = true;
        acceptThread = new Thread(this);
        acceptThread.setName("Socks5ProxyServer-" + listenPort);
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    public void stop() {
        running = false;
        try { if (serverSocket != null) serverSocket.close(); } catch (Exception ignored) {}
        if (statistics != null) {
            statistics.reset();
        }
    }
    
    /**
     * 获取统计信息
     */
    public Socks5ProxyStatistics getStatistics() {
        return statistics;
    }

    @Override
    public void run() {
        while (running) {
            try {
                Socket clientSocket = serverSocket.accept();
                // 连接数上限检查
                Socks5ProxyStatistics.StatisticsSnapshot snap = statistics.getSnapshot();
                if (snap.activeConnections >= MAX_CONNS) {
                    log.warn("SOCKS5 连接数已达上限 {}，拒绝新连接", MAX_CONNS);
                    try { clientSocket.close(); } catch (Exception ignored) {}
                    continue;
                }
                clientSocket.setTcpNoDelay(true);
                clientSocket.setSoTimeout(15000);
                clientSocket.setReuseAddress(true);
                Thread thread=new Thread(new HandleClientThread(clientSocket, javaPuppetNode, statistics));
                thread.start();

            } catch (Exception e) {
                if (running) {
                    log.error("Socks5 proxy accept error", e);
                }
            }
        }
    }

}


