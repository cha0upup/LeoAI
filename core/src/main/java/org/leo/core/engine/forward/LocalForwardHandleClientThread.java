package org.leo.core.engine.forward;

import org.leo.core.engine.socks5.ReadDataThread;
import org.leo.core.engine.socks5.Socks5ProxyStatistics;
import org.leo.core.engine.socks5.WriteDataThread;
import org.leo.core.puppet.impl.JavaPuppetNode;
import org.leo.core.util.md5.MD5Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;

/**
 * 本地端口转发客户端处理线程。
 * 将本地连接透明地转发到 puppet 端的 targetHost:targetPort。
 */
public class LocalForwardHandleClientThread implements Runnable {

    private static final Logger logger = LoggerFactory.getLogger(LocalForwardHandleClientThread.class);

    private final Socket clientSocket;
    private final JavaPuppetNode javaPuppetNode;
    private final String targetHost;
    private final int targetPort;
    private final Socks5ProxyStatistics statistics;

    public LocalForwardHandleClientThread(Socket clientSocket, JavaPuppetNode javaPuppetNode,
                                          String targetHost, int targetPort,
                                          Socks5ProxyStatistics statistics) {
        this.clientSocket = clientSocket;
        this.javaPuppetNode = javaPuppetNode;
        this.targetHost = targetHost;
        this.targetPort = targetPort;
        this.statistics = statistics;
    }

    @Override
    public void run() {
        String connId = MD5Utils.getMD5Hash(
                clientSocket.getInetAddress() + ":" + clientSocket.getPort()
                + "-" + targetHost + ":" + targetPort + "-" + System.nanoTime());

        try {
            // 在 puppet 端打开到目标的连接
            Map<String, Object> params = new HashMap<String, Object>();
            params.put("op", 0);
            params.put("targetHost", targetHost);
            params.put("targetPort", targetPort);
            params.put("connId", connId);
            Map<String, Object> res = javaPuppetNode.invokeComponent("ProxyForwardComponent", params);

            if (!Integer.valueOf(200).equals(res.get("code"))) {
                logger.debug("本地转发打开远程连接失败: {}:{}", targetHost, targetPort);
                close(clientSocket);
                return;
            }

            if (statistics != null) {
                statistics.addConnection(connId, targetHost, targetPort,
                        clientSocket.getInetAddress().getHostAddress());
            }

            Thread r = new Thread(new ReadDataThread(clientSocket, connId, javaPuppetNode, statistics));
            Thread w = new Thread(new WriteDataThread(clientSocket, connId, javaPuppetNode, statistics));
            r.setDaemon(true);
            w.setDaemon(true);
            r.start();
            w.start();
            r.join();
            w.join();

        } catch (Exception e) {
            logger.debug("本地转发处理异常: {}", e.getMessage());
        } finally {
            if (statistics != null) statistics.removeConnection(connId);
            close(clientSocket);
        }
    }

    private void close(Socket s) {
        try { if (s != null && !s.isClosed()) s.close(); } catch (IOException ignored) { }
    }
}
