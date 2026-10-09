package org.leo.core.engine.reverse;

import org.leo.core.engine.proxy.ProxySessionResources.ConnectionPermit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicBoolean;

/** A queued dial owns its connection slot until all relay/RPC work has finished. */
class DialerTask implements Runnable {
    private static final Logger logger = LoggerFactory.getLogger(DialerTask.class);
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private final ReverseTunnelServer server;
    private final String connId;
    private final String clientAddr;
    private final ConnectionPermit permit;
    private final AtomicBoolean claimed = new AtomicBoolean();
    private volatile boolean cancelled;

    DialerTask(ReverseTunnelServer server, String connId, String clientAddr, ConnectionPermit permit) {
        this.server = server;
        this.connId = connId;
        this.clientAddr = clientAddr;
        this.permit = permit;
    }

    void cancel() {
        cancelled = true;
        if (claimed.compareAndSet(false, true)) {
            if (permit != null) permit.close();
            server.dialerCompleted(connId, this);
        }
    }

    @Override
    public void run() {
        if (!claimed.compareAndSet(false, true)) return;
        Socket local = null;
        boolean relaysStarted = false;
        try {
            local = new Socket();
            if (cancelled || !server.registerPendingConnection(connId, local, this)) return;
            local.connect(new InetSocketAddress(server.getForwardHost(), server.getForwardPort()), CONNECT_TIMEOUT_MS);
            local.setTcpNoDelay(true);
            if (cancelled || !server.registerLocalConnection(connId, local, clientAddr, this)) return;
            relaysStarted = true;
            startRelays(local);
        } catch (Exception error) {
            if (!cancelled && server.isRunning()) {
                logger.warn("反向隧道拨号失败 {}:{} connId={}: {}",
                        server.getForwardHost(), server.getForwardPort(), connId, error.getMessage());
            }
        } finally {
            try {
                if (!relaysStarted) {
                    server.completeLocalConnection(connId, local);
                    if (!cancelled && server.isRunning()) server.closeRemoteConnection(connId);
                }
            } finally {
                server.dialerCompleted(connId, this);
                if (permit != null) permit.close();
            }
        }
    }

    private void startRelays(Socket local) {
        AtomicBoolean cleaned = new AtomicBoolean();
        Runnable cleanup = () -> {
            if (!cleaned.compareAndSet(false, true)) return;
            server.completeLocalConnection(connId, local);
            if (!cancelled && server.isRunning()) server.closeRemoteConnection(connId);
        };
        try {
            startRelay(new ReverseReadThread(server, local, connId), cleanup, "Read");
            startRelay(new ReverseWriteThread(server, local, connId), cleanup, "Write");
        } catch (RuntimeException | Error error) {
            cleanup.run();
            throw error;
        }
    }

    private void startRelay(Runnable relay, Runnable cleanup, String direction) {
        ConnectionPermit relayPermit = permit == null ? null : permit.retain();
        try {
            Thread thread = new Thread(() -> {
                try {
                    relay.run();
                } finally {
                    try {
                        cleanup.run();
                    } finally {
                        if (relayPermit != null) relayPermit.close();
                    }
                }
            }, "ReverseTunnel-" + direction + "-" + connId);
            thread.setDaemon(true);
            thread.start();
        } catch (RuntimeException | Error error) {
            if (relayPermit != null) relayPermit.close();
            throw error;
        }
    }
}
