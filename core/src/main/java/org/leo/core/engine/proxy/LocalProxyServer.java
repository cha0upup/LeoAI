package org.leo.core.engine.proxy;

import org.leo.core.engine.socks5.Socks5ProxyStatistics;
import org.leo.core.puppet.capability.ComponentInvokeCapable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashSet;
import java.util.Set;

/** Shared listener lifecycle and capacity reservation, including clients still handshaking. */
public abstract class LocalProxyServer {
    private static final Logger log = LoggerFactory.getLogger(LocalProxyServer.class);
    private final ComponentInvokeCapable node;
    private final int port;
    private final String bindAddr;
    private final int handshakeTimeout;
    private final int maxConnections;
    private final Socks5ProxyStatistics statistics;
    private final Set<ProxyConnection> connections = new HashSet<>();
    private volatile boolean running;
    private ServerSocket serverSocket;

    protected LocalProxyServer(ComponentInvokeCapable node, int port, int handshakeTimeout) {
        this(node, port, handshakeTimeout, 200);
    }

    protected LocalProxyServer(ComponentInvokeCapable node, int port, int handshakeTimeout, int maxConnections) {
        this(node, port, ProxyBindAddress.DEFAULT, handshakeTimeout, maxConnections);
    }

    protected LocalProxyServer(ComponentInvokeCapable node, int port, String bindAddr, int handshakeTimeout) {
        this(node, port, bindAddr, handshakeTimeout, 200);
    }

    protected LocalProxyServer(ComponentInvokeCapable node, int port, String bindAddr,
                               int handshakeTimeout, int maxConnections) {
        if (maxConnections < 1) throw new IllegalArgumentException("maxConnections must be positive");
        this.node = node;
        this.port = port;
        this.bindAddr = ProxyBindAddress.normalize(bindAddr);
        this.handshakeTimeout = handshakeTimeout;
        this.maxConnections = maxConnections;
        this.statistics = new Socks5ProxyStatistics(port);
    }

    public synchronized void start() throws IOException {
        if (running) return;
        ServerSocket listener = new ServerSocket();
        try {
            listener.setReuseAddress(true);
            listener.bind(new InetSocketAddress(bindAddr, port));
            serverSocket = listener;
            running = true;
            Thread accept = new Thread(() -> acceptLoop(listener), getClass().getSimpleName() + "-" + getListenPort());
            accept.setDaemon(true);
            accept.start();
        } catch (IOException | RuntimeException error) {
            running = false;
            listener.close();
            throw error;
        }
        log.info("{} 已启动: {}", getClass().getSimpleName(), listener.getLocalSocketAddress());
    }

    public synchronized void stop() {
        running = false;
        try { if (serverSocket != null) serverSocket.close(); } catch (IOException ignored) { }
        // Keep reservations until their handlers finish, including any in-flight OPEN RPC.
        for (ProxyConnection connection : connections) connection.closeLocal();
        statistics.reset();
    }

    public boolean isRunning() {
        return running;
    }

    public synchronized int getListenPort() {
        return serverSocket == null ? port : serverSocket.getLocalPort();
    }

    public synchronized ServerSocket getServerSocket() {
        return serverSocket;
    }

    public synchronized String getBindAddr() {
        return serverSocket == null ? bindAddr : serverSocket.getInetAddress().getHostAddress();
    }

    public Socks5ProxyStatistics getStatistics() {
        return statistics;
    }

    protected abstract void handle(ProxyConnection connection) throws Exception;

    private void acceptLoop(ServerSocket listener) {
        try {
            while (!listener.isClosed()) {
                Socket client = listener.accept();
                ProxyConnection connection = new ProxyConnection(client, node, statistics);
                synchronized (this) {
                    if (!running || serverSocket != listener || connections.size() >= maxConnections) {
                        connection.closeLocal();
                        continue;
                    }
                    connections.add(connection);
                }
                try {
                    client.setTcpNoDelay(true);
                    client.setSoTimeout(handshakeTimeout);
                    Thread handler = new Thread(() -> serve(connection), getClass().getSimpleName() + "-Client");
                    handler.setDaemon(true);
                    handler.start();
                } catch (Exception error) {
                    connection.close();
                    release(connection);
                    if (running) log.debug("初始化代理连接失败", error);
                }
            }
        } catch (IOException error) {
            if (!listener.isClosed()) log.warn("代理监听异常", error);
        } finally {
            synchronized (this) {
                if (serverSocket == listener) stop();
            }
        }
    }

    private void serve(ProxyConnection connection) {
        try (connection) {
            if (!connection.isClosed()) handle(connection);
        } catch (Exception error) {
            log.debug("代理连接结束: {}", error.getMessage());
        } finally {
            release(connection);
        }
    }

    private synchronized void release(ProxyConnection connection) {
        connections.remove(connection);
    }
}
