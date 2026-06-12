package org.leo.core.engine.reverse;

import org.leo.core.engine.socks5.Socks5ProxyStatistics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.Socket;

/**
 * 反向隧道：拨号 + 启动中继线程的任务。
 * 在 dialerExecutor 中执行，避免阻塞 AcceptPollLoop。
 */
class DialerTask implements Runnable {

    private static final Logger logger = LoggerFactory.getLogger(DialerTask.class);

    private final ReverseTunnelServer server;
    private final String connId;
    private final String clientAddr;

    DialerTask(ReverseTunnelServer server, String connId, String clientAddr) {
        this.server = server;
        this.connId = connId;
        this.clientAddr = clientAddr;
    }

    public void run() {
        Socket local = null;
        try {
            local = new Socket(server.getForwardHost(), server.getForwardPort());
            local.setTcpNoDelay(true);
            server.getLocalConns().put(connId, local);

            Socks5ProxyStatistics stats = server.getStatistics();
            if (stats != null) {
                stats.addConnection(connId, server.getForwardHost(), server.getForwardPort(),
                        clientAddr != null ? clientAddr : "");
            }

            Thread r = new Thread(new ReverseReadThread(server, local, connId),
                    "ReverseTunnel-Read-" + connId);
            Thread w = new Thread(new ReverseWriteThread(server, local, connId),
                    "ReverseTunnel-Write-" + connId);
            r.setDaemon(true);
            w.setDaemon(true);
            r.start();
            w.start();
            r.join();
            w.join();
        } catch (Exception e) {
            logger.warn("反向隧道拨号失败 {}:{} connId={}: {}",
                    server.getForwardHost(), server.getForwardPort(), connId, e.getMessage());
            // 让 puppet 释放该连接
            closeRemote();
        } finally {
            Socks5ProxyStatistics stats = server.getStatistics();
            if (stats != null) stats.removeConnection(connId);
            server.getLocalConns().remove(connId);
            if (local != null) {
                try { local.close(); } catch (IOException ignored) {}
            }
        }
    }

    private void closeRemote() {
        try {
            java.util.HashMap<String, Object> params = new java.util.HashMap<String, Object>();
            params.put("op", Integer.valueOf(ReverseTunnelServer.OP_CLOSE));
            params.put("connId", connId);
            server.getJavaPuppetNode().invokeComponent("ReverseTunnelComponent", params);
        } catch (Exception ignored) {}
    }
}
