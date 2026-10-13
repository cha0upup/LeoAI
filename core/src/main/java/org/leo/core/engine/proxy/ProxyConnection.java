package org.leo.core.engine.proxy;

import org.leo.core.engine.socks5.Socks5ProxyStatistics;
import org.leo.core.agent.capability.ComponentInvokeCapable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Owns both ends of one connection, including an OPEN racing with server shutdown. */
public final class ProxyConnection implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(ProxyConnection.class);
    private final Socket client;
    private final ComponentInvokeCapable node;
    private final Socks5ProxyStatistics statistics;
    private final ProxySessionResources.ConnectionPermit permit;
    private final String connId = UUID.randomUUID().toString();
    private final ProxyDuplexIo io;
    private boolean closed;
    private boolean opening;
    private boolean openAttempted;
    private boolean remoteCloseSent;

    ProxyConnection(Socket client, ComponentInvokeCapable node, Socks5ProxyStatistics statistics,
                    ProxySessionResources.ConnectionPermit permit, ProxyBatchDispatcher batches) {
        this.permit = permit;
        this.client = client;
        this.node = node;
        this.statistics = statistics;
        io = new ProxyDuplexIo(node, "ProxyForwardComponent", connId, client, batches, permit,
                () -> !isClosed(), this::recordUpload, this::recordDownload);
    }

    public Socket socket() {
        return client;
    }

    public synchronized boolean isClosed() {
        return closed || client.isClosed();
    }

    public boolean open(String host, int port) throws Exception {
        synchronized (this) {
            if (isClosed()) return false;
            if (openAttempted) throw new IllegalStateException("connection already opened");
            openAttempted = true;
            opening = true;
        }
        try {
            Map<String, Object> params = new HashMap<>(Map.of("op", 0, "connId", connId));
            params.put("targetHost", host);
            params.put("targetPort", port);
            Map<String, Object> response = node.invokeComponent("ProxyForwardComponent", params);
            synchronized (this) {
                if (code(response) != 200 || isClosed()) return false;
                if (statistics != null) {
                    statistics.addConnection(connId, host, port, client.getInetAddress().getHostAddress());
                }
                return true;
            }
        } finally {
            synchronized (this) {
                opening = false;
            }
            // A late OPEN result must not leave a remote socket behind after stop().
            if (isClosed()) close();
        }
    }

    public void write(byte[] data) throws Exception {
        io.write(data);
    }

    private synchronized void recordUpload(int bytes) {
        if (!isClosed() && statistics != null) statistics.addUploadBytes(connId, bytes);
    }

    private synchronized void recordDownload(int bytes) {
        if (!isClosed() && statistics != null) statistics.addDownloadBytes(connId, bytes);
    }

    /** The handler carries uploads; only the download direction needs an extra thread. */
    public void relay() throws IOException {
        if (isClosed()) return;
        client.setSoTimeout(0);
        ProxySessionResources.ConnectionPermit readerPermit = permit == null ? null : permit.retain();
        Thread reader = new Thread(() -> {
            try {
                io.readLoop();
            } finally {
                if (readerPermit != null) readerPermit.close();
            }
        }, "Proxy-Read-" + connId);
        reader.setDaemon(true);
        try {
            reader.start();
        } catch (RuntimeException | Error error) {
            if (readerPermit != null) readerPermit.close();
            throw error;
        }
        try {
            io.writeLoop();
        } finally {
            closeLocal();
            reader.interrupt();
            close();
        }
    }

    /** No RPC here: shutdown must close every local socket even when the node is unreachable. */
    public synchronized void closeLocal() {
        if (closed) return;
        closed = true;
        try { client.close(); } catch (IOException ignored) { }
        if (statistics != null) statistics.removeConnection(connId);
    }

    @Override
    public void close() {
        synchronized (this) {
            closeLocal();
            if (!openAttempted || opening || remoteCloseSent) return;
            remoteCloseSent = true;
        }
        try {
            node.invokeComponent("ProxyForwardComponent", Map.of("op", 3, "connId", connId));
        } catch (Exception error) {
            log.debug("关闭远程代理连接失败: connId={}", connId, error);
        }
    }

    void releaseReservation() {
        if (permit != null) permit.close();
    }

    private static int code(Map<String, Object> response) {
        return response != null && response.get("code") instanceof Number code ? code.intValue() : -1;
    }
}
