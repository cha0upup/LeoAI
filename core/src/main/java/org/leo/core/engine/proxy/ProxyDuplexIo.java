package org.leo.core.engine.proxy;

import org.leo.core.agent.capability.ComponentInvokeCapable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;

/** One owner orders batch responses and writes them to the local socket. */
public final class ProxyDuplexIo {
    private static final Logger log = LoggerFactory.getLogger(ProxyDuplexIo.class);
    private static final long MAX_OFFSET = 9007199254740991L;
    private static final long WRITE_STALL_NANOS = TimeUnit.SECONDS.toNanos(5);
    private final ComponentInvokeCapable node;
    private final String componentId;
    private final String connId;
    private final Socket socket;
    private final BooleanSupplier active;
    private final IntConsumer onWrite;
    private final IntConsumer onRead;
    private final ReentrantLock readOwner = new ReentrantLock(true);
    private final Condition readChanged = readOwner.newCondition();
    private final long baseDelay;
    private long idleDelay;
    private long nextRead;
    private boolean readEnded;
    private final ProxyBatchDispatcher batches;
    private final ProxySessionResources.ConnectionPermit batchPermit;
    private long writeAck;
    private long readAck;
    private boolean readWindowFull;

    public ProxyDuplexIo(ComponentInvokeCapable node, String componentId, String connId, Socket socket,
                         ProxyBatchDispatcher batches, ProxySessionResources.ConnectionPermit permit,
                         BooleanSupplier active, IntConsumer onWrite, IntConsumer onRead) {
        this.node = node;
        this.componentId = componentId;
        this.connId = connId;
        this.socket = socket;
        this.batches = Objects.requireNonNull(batches);
        this.batchPermit = permit;
        this.active = active;
        this.onWrite = onWrite;
        this.onRead = onRead;
        baseDelay = "ReverseTunnelComponent".equals(componentId) ? 50L : 100L;
        idleDelay = baseDelay;
    }

    public void readLoop() { relay(false); }
    public void writeLoop() { relay(true); }

    private void relay(boolean writing) {
        try {
            if (writing) {
                InputStream input = socket.getInputStream();
                byte[] buffer = new byte[ProxyIoBatch.BUFFER_BYTES];
                while (isActive()) {
                    byte[] data = ProxyIoBatch.read(input, buffer);
                    if (data == null) break;
                    if (data.length > 0) write(data);
                }
            } else {
                while (isActive()) pollRead();
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        } catch (Exception error) {
            if (isActive()) log.debug("代理中继结束: {} connId={} {}", componentId, connId, error.getMessage());
        } finally {
            try { socket.close(); } catch (IOException ignored) { }
            if (readOwner.tryLock()) {
                try { readChanged.signalAll(); } finally { readOwner.unlock(); }
            }
        }
    }

    public void write(byte[] data) throws Exception {
        if (!isActive()) throw new IOException("proxy connection closed");
        int offset = 0;
        int backoff = 2;
        long deadline = System.nanoTime() + WRITE_STALL_NANOS;
        while (offset < data.length) {
            int written;
            int received;
            readOwner.lockInterruptibly();
            try {
                ensureReadable();
                int end = Math.min(data.length, offset + ProxyIoBatch.MAX_BYTES);
                byte[] remaining = offset == 0 && end == data.length ? data : Arrays.copyOfRange(data, offset, end);
                long previousAck = writeAck;
                received = exchangeBatch(remaining);
                written = (int) (writeAck - previousAck);
                offset += written;
                if (written > 0) deadline = System.nanoTime() + WRITE_STALL_NANOS;
                scheduleRead(readWindowFull ? 0 : baseDelay);
                if (readEnded && offset < data.length) throw new IOException("proxy peer closed during BATCH");
            } finally {
                readOwner.unlock();
            }
            if (offset >= data.length) return;
            if (System.nanoTime() >= deadline) throw new IOException("proxy BATCH write stalled");
            if (written == 0 && received == 0) {
                Thread.sleep(backoff);
                backoff = Math.min(backoff * 2, 32);
            } else backoff = 2;
        }
    }

    private void pollRead() throws Exception {
        readOwner.lockInterruptibly();
        try {
            long delay;
            while (isActive() && !readEnded && (delay = nextRead - System.nanoTime()) > 0) {
                readChanged.awaitNanos(delay);
            }
            ensureReadable();
            int received = exchangeBatch(ProxyIoBatch.EMPTY);
            nextRead = received > 0 ? 0 : System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(idleDelay);
            idleDelay = received > 0 ? baseDelay : Math.min(idleDelay * 2, 800L);
        } finally {
            readOwner.unlock();
        }
    }

    /** Offsets advance only on cumulative write ACK and successful local delivery. */
    private int exchangeBatch(byte[] data) throws Exception {
        Map<String, Object> response = batches.exchange(node, componentId, Map.of(
                "connId", connId, "data", data, "writeOffset", writeAck, "readAck", readAck), batchPermit);
        if (code(response, "code") != 200) throw new IOException("proxy BATCH connection failed");
        long acknowledged = boundedLong(response.get("writeAck"), MAX_OFFSET);
        long readOffset = boundedLong(response.get("readOffset"), MAX_OFFSET);
        if (acknowledged < writeAck || acknowledged > writeAck + data.length || readOffset > readAck) {
            throw new IOException("proxy BATCH returned invalid offset");
        }
        Object value = response.get("data");
        if (!(value instanceof byte[] received) || received.length > ProxyIoBatch.MAX_BYTES
                || readAck - readOffset > received.length) throw new IOException("proxy BATCH returned invalid data");
        int readCode = code(response, "readCode");
        if (readCode != 200 && readCode != 204 && readCode != 404 && readCode != 500
                || readCode == 204 && received.length > 0) throw new IOException("proxy BATCH returned invalid readCode");
        int written = (int) (acknowledged - writeAck);
        writeAck = acknowledged;
        if (written > 0) onWrite.accept(written);
        int skip = (int) (readAck - readOffset);
        int length = received.length - skip;
        readWindowFull = received.length == boundedLong(response.get("readLimit"), ProxyIoBatch.MAX_BYTES);
        if (length > 0) {
            socket.getOutputStream().write(received, skip, length);
            socket.getOutputStream().flush();
            readAck += length;
            onRead.accept(length);
        }
        readEnded = readCode == 404 || readCode == 500;
        return length;
    }

    private static long boundedLong(Object value, long maximum) throws IOException {
        if (value instanceof Number number) {
            long result = number.longValue();
            if (result >= 0 && result <= maximum && number.doubleValue() == result) return result;
        }
        throw new IOException("proxy returned invalid number");
    }

    private void scheduleRead(long delay) {
        idleDelay = baseDelay;
        nextRead = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(delay);
        readChanged.signalAll();
    }

    private boolean isActive() { return active.getAsBoolean() && !socket.isClosed(); }

    private void ensureReadable() throws IOException {
        if (!isActive() || readEnded) throw new IOException("proxy connection closed");
    }

    private static int code(Map<String, Object> response, String key) {
        return response != null && response.get(key) instanceof Number value ? value.intValue() : -1;
    }
}
