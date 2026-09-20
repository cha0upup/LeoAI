package org.leo.core.engine.proxy;

import org.leo.core.engine.socks5.ReadDataThread;
import org.leo.core.engine.socks5.Socks5ProxyStatistics;
import org.leo.core.engine.socks5.WriteDataThread;
import org.leo.core.puppet.capability.ComponentInvokeCapable;
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
    private final String connId = UUID.randomUUID().toString();
    private boolean closed;
    private boolean opening;
    private boolean openAttempted;
    private boolean remoteCloseSent;

    public ProxyConnection(Socket client, ComponentInvokeCapable node, Socks5ProxyStatistics statistics) {
        this.client = client;
        this.node = node;
        this.statistics = statistics;
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
        boolean opened = false;
        try {
            Map<String, Object> params = params(0);
            params.put("targetHost", host);
            params.put("targetPort", port);
            opened = code(node.invokeComponent("ProxyForwardComponent", params)) == 200;
            synchronized (this) {
                if (!opened || isClosed()) return false;
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

    /** null means no data yet; all other unsuccessful replies end the connection. */
    public byte[] read() throws Exception {
        Map<String, Object> response = node.invokeComponent("ProxyForwardComponent", params(2));
        int code = code(response);
        if (code == 204) return null;
        if (code != 200 || !(response.get("data") instanceof byte[] data)) {
            throw new IOException("proxy read failed: " + code);
        }
        return data;
    }

    public void write(byte[] data) throws Exception {
        if (isClosed()) throw new IOException("proxy connection closed");
        Map<String, Object> params = params(1);
        params.put("data", data);
        Map<String, Object> response = node.invokeComponent("ProxyForwardComponent", params);
        if (code(response) != 200
                || (response.get("bytesWritten") instanceof Number written && written.longValue() != data.length)) {
            throw new IOException("proxy write failed: " + code(response));
        }
        synchronized (this) {
            if (!isClosed() && statistics != null) statistics.addUploadBytes(connId, data.length);
        }
    }

    public synchronized void recordDownload(int bytes) {
        if (!isClosed() && statistics != null) statistics.addDownloadBytes(connId, bytes);
    }

    /** The handler carries uploads; only the download direction needs an extra thread. */
    public void relay() throws IOException {
        if (isClosed()) return;
        client.setSoTimeout(0);
        Thread reader = new Thread(new ReadDataThread(this), "Proxy-Read-" + connId);
        reader.setDaemon(true);
        reader.start();
        try {
            new WriteDataThread(this).run();
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
            node.invokeComponent("ProxyForwardComponent", params(3));
        } catch (Exception error) {
            log.debug("关闭远程代理连接失败: connId={}", connId, error);
        }
    }

    private Map<String, Object> params(int operation) {
        Map<String, Object> params = new HashMap<>();
        params.put("op", operation);
        params.put("connId", connId);
        return params;
    }

    private static int code(Map<String, Object> response) {
        return response != null && response.get("code") instanceof Number code ? code.intValue() : -1;
    }
}
