package org.leo.core.engine.proxy;

import org.leo.core.agent.capability.ComponentInvokeCapable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/** In-memory socket pair over the existing ordered proxy relay. Opens no local listener. */
public final class AgentRelaySocket extends Socket {
    private final BytePipe incoming = new BytePipe();
    private final BytePipe outgoing = new BytePipe();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean closeRequested = new AtomicBoolean();
    private final String host;
    private final int port;
    private final ProxyConnection connection;
    private final Executor cleanup;
    private final InputStream input;
    private final OutputStream output;
    private volatile int timeout;

    private AgentRelaySocket(ComponentInvokeCapable node, String host, int port,
                             ProxySessionResources resources, Executor cleanup) throws IOException {
        this.host = host;
        this.port = port;
        this.cleanup = cleanup;
        input = incoming.input(() -> timeout);
        output = outgoing.output();
        var permit = resources.tryAcquireConnection();
        if (permit == null) throw new IOException("Agent 中转连接已达上限");
        InputStream relayInput = outgoing.input(() -> 0);
        OutputStream relayOutput = incoming.output();
        Socket peer = new Socket() {
            @Override public InputStream getInputStream() { return relayInput; }
            @Override public OutputStream getOutputStream() { return relayOutput; }
            @Override public boolean isClosed() { return closed.get(); }
            @Override public void close() { closePipes(); }
            @Override public synchronized void setSoTimeout(int value) { }
        };
        connection = new ProxyConnection(peer, node, null, permit, resources.batches());
    }

    public static AgentRelaySocket open(ComponentInvokeCapable node, String host, int port,
                                        ProxySessionResources resources, Executor cleanup) throws Exception {
        AgentRelaySocket socket = new AgentRelaySocket(node, host, port, resources, cleanup);
        try {
            if (!socket.connection.open(host, port)) throw new IOException("Agent 到 SSH 主机的 TCP 连接失败");
            Thread relay = new Thread(() -> {
                try { socket.connection.relay(); }
                catch (IOException ignored) { }
                finally { socket.closePipes(); socket.connection.releaseReservation(); }
            }, "SSH-Agent-Relay");
            relay.setDaemon(true);
            relay.start();
            return socket;
        } catch (Exception | Error error) {
            socket.close();
            socket.connection.releaseReservation();
            throw error;
        }
    }

    @Override public InputStream getInputStream() { return input; }
    @Override public OutputStream getOutputStream() { return output; }
    @Override public boolean isConnected() { return !closed.get(); }
    @Override public boolean isClosed() { return closed.get(); }
    // A closed relay must never fall back to Socket's direct network connection.
    @Override public void connect(SocketAddress endpoint) throws IOException { throw new IOException("SSH 仅使用 Agent 中转连接"); }
    @Override public void connect(SocketAddress endpoint, int timeout) throws IOException { throw new IOException("SSH 仅使用 Agent 中转连接"); }
    @Override public void bind(SocketAddress endpoint) throws IOException { throw new IOException("Agent 中转无需本地监听端口"); }
    @Override public int getPort() { return port; }
    @Override public SocketAddress getRemoteSocketAddress() { return InetSocketAddress.createUnresolved(host, port); }
    @Override public synchronized void setSoTimeout(int value) {
        if (value < 0) throw new IllegalArgumentException("timeout must be nonnegative");
        timeout = value;
    }
    @Override public synchronized int getSoTimeout() { return timeout; }
    private void closePipes() {
        closed.set(true);
        incoming.close();
        outgoing.close();
    }
    @Override public void close() {
        closePipes();
        connection.closeLocal();
        // An offline parent must not block the browser's close/logout request.
        if (closeRequested.compareAndSet(false, true)) {
            try { cleanup.execute(connection::close); } catch (RejectedExecutionException ignored) { connection.close(); }
        }
    }

    /** Bounded byte pipe without PipedInputStream's writer-thread lifetime assumptions. */
    private static final class BytePipe {
        private final byte[] bytes = new byte[256 * 1024];
        private int head, size;
        private boolean closed;
        synchronized void close() { closed = true; notifyAll(); }
        InputStream input(java.util.function.IntSupplier timeout) {
            return new InputStream() {
                @Override public int read() throws IOException {
                    byte[] one = new byte[1];
                    return read(one, 0, 1) < 0 ? -1 : one[0] & 255;
                }
                @Override public int read(byte[] target, int offset, int length) throws IOException {
                    java.util.Objects.checkFromIndexSize(offset, length, target.length);
                    if (length == 0) return 0;
                    synchronized (BytePipe.this) {
                        int waitMillis = timeout.getAsInt();
                        long deadline = System.nanoTime() + waitMillis * 1_000_000L;
                        while (size == 0 && !closed) {
                            if (waitMillis == 0) { pause(0); continue; }
                            long remaining = deadline - System.nanoTime();
                            if (remaining <= 0) throw new SocketTimeoutException("SSH 中转读取超时");
                            pause(Math.max(1, remaining / 1_000_000L));
                        }
                        if (size == 0) return -1;
                        int count = Math.min(length, Math.min(size, bytes.length - head));
                        System.arraycopy(bytes, head, target, offset, count);
                        head = (head + count) % bytes.length;
                        size -= count;
                        BytePipe.this.notifyAll();
                        return count;
                    }
                }
                @Override public void close() { BytePipe.this.close(); }
            };
        }
        OutputStream output() {
            return new OutputStream() {
                @Override public void write(int value) throws IOException { write(new byte[]{(byte) value}); }
                @Override public void write(byte[] source, int offset, int length) throws IOException {
                    java.util.Objects.checkFromIndexSize(offset, length, source.length);
                    synchronized (BytePipe.this) {
                        while (length > 0) {
                            while (size == bytes.length && !closed) pause(0);
                            if (closed) throw new IOException("SSH 中转连接已关闭");
                            int tail = (head + size) % bytes.length;
                            int count = Math.min(length, Math.min(bytes.length - size, bytes.length - tail));
                            System.arraycopy(source, offset, bytes, tail, count);
                            size += count; offset += count; length -= count;
                            BytePipe.this.notifyAll();
                        }
                    }
                }
                @Override public void close() { BytePipe.this.close(); }
            };
        }
        private void pause(long millis) throws IOException {
            try { wait(millis); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IOException("SSH 中转已中断", error); }
        }
    }
}
