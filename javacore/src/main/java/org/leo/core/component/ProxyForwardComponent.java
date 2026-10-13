package org.leo.core.component;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 代理转发组件
 * 在被管理端发起到目标内网服务的 TCP 连接，并提供 open/batch/close 操作
 */
public class ProxyForwardComponent implements Runnable {

    // 操作类型常量
    private static final int OP_OPEN = 0;
    private static final int OP_CLOSE = 3;

    private static final int OP_BATCH = 5;

    // 缓冲区大小
    private static final int BUFFER_SIZE = 65536;
    private static final int MAX_FRAME_BYTES = 262144;
    private static final long IO_BUDGET_NANOS = 2000000L;
    private static final int DEFAULT_CONNECT_TIMEOUT_MS = 10000;
    private static final int MAX_CONNECT_TIMEOUT_MS = 300000;
    private static final int MAX_CONNECTIONS = 512;
    private static final long MAX_OFFSET = 9007199254740991L;
    private static final byte[] EMPTY = new byte[0];
    private static final int CHANNEL = 0, WRITE_ACK = 1, READ_BASE = 2, PENDING = 3,
            TERMINAL = 4, LAST_ACTIVITY = 5;
    // idle 超时（10 分钟）
    private static final long IDLE_TIMEOUT_MS = 10L * 60L * 1000L;

    private HashMap<String, Object> params;
    private HashMap<String, Object> results;
    // connId -> channel, write ACK, read base, retained bytes, terminal code, last activity
    private static final Map<String, Object[]> connMap = new ConcurrentHashMap<String, Object[]>();

    @Override
    public void run() {
        java.lang.reflect.InvocationHandler h = (java.lang.reflect.InvocationHandler) Thread.currentThread().getContextClassLoader();
        try {
            params = (java.util.HashMap) h.invoke(null, null, null);
            results = new java.util.HashMap();
            invoke();
        } catch (Throwable t) {
            if (results == null) results = new java.util.HashMap();
            status(results, 500, t.getMessage());
        }
        if (results != null) {
            try { h.invoke(null, null, new Object[]{results}); } catch (Throwable ignored) {}
        }
    }

    public void invoke() {
        sweepIdleConns();
        Object value = params.get("op");
        if (!(value instanceof Number)) {
            status(results, 400, "op must be a number");
            return;
        }
        int op = ((Number) value).intValue();
        if (((Number) value).doubleValue() != op || (op != OP_OPEN && op != OP_CLOSE && op != OP_BATCH)) {
            status(results, 400, "unknown op");
            return;
        }
        if (op == OP_BATCH) { handleBatch(); return; }
        String connId = getRequiredString("connId");
        if (connId == null) return;
        if (op == OP_OPEN) handleOpen(connId);
        else {
            closeConnection(connId, connMap.get(connId));
            results.put("code", Integer.valueOf(200));
        }
    }

    /**
     * 处理打开连接操作
     */
    private void handleOpen(String connId) {
        Object targetHostObj = params.get("targetHost");
        Object portObj = params.get("targetPort");
        if (!(targetHostObj instanceof String) || ((String) targetHostObj).length() == 0
                || !(portObj instanceof Number)) {
            status(results, 400, "targetHost and targetPort required");
            return;
        }
        String targetHost = (String) targetHostObj;
        int targetPort = ((Number) portObj).intValue();
        if (targetPort < 1 || targetPort > 65535) {
            status(results, 400, "targetPort out of range");
            return;
        }
        if (connMap.containsKey(connId)) {
            status(results, 409, "connId already exists");
            return;
        }
        int timeout = DEFAULT_CONNECT_TIMEOUT_MS;
        Object timeoutObj = params.get("connectTimeout");
        if (timeoutObj instanceof Number) timeout = ((Number) timeoutObj).intValue();
        if (timeout <= 0) timeout = DEFAULT_CONNECT_TIMEOUT_MS;
        if (timeout > MAX_CONNECT_TIMEOUT_MS) timeout = MAX_CONNECT_TIMEOUT_MS;
        SocketChannel socketChannel = null;
        try {
            socketChannel = SocketChannel.open();
            socketChannel.socket().connect(new InetSocketAddress(targetHost, targetPort), timeout);
            socketChannel.configureBlocking(false);
            int registration = registerConnection(connId, socketChannel);
            if (registration != 0) {
                closeQuietly(socketChannel);
                status(results, registration == 2 ? 429 : 409,
                        registration == 2 ? "connection limit reached" : "connId already exists");
                return;
            }
            status(results, 200, "opened");
        } catch (IOException e) {
            closeQuietly(socketChannel);
            status(results, 404, "建立连接失败: " + e.getMessage());
        }
    }

    /** Validate the whole envelope before any socket is touched. Each frame owns one bounded window. */
    private void handleBatch() {
        Object value = params.get("frames");
        if (!(value instanceof java.util.List) || ((java.util.List) value).size() < 1
                || ((java.util.List) value).size() > 16) {
            status(results, 400, "BATCH requires 1..16 frames");
            return;
        }
        java.util.List frames = (java.util.List) value;
        Map ids = new HashMap();
        long bytes = 0;
        for (int i = 0; i < frames.size(); i++) {
            if (!(frames.get(i) instanceof Map)) { results.put("code", Integer.valueOf(400)); return; }
            Map frame = (Map) frames.get(i);
            Object id = frame.get("connId");
            Object data = frame.get("data");
            long limit = batchOffset(frame.get("maxBytes"));
            long writeOffset = batchOffset(frame.get("writeOffset"));
            if (!(id instanceof String) || ((String) id).length() == 0 || ids.put(id, Boolean.TRUE) != null
                    || !(data instanceof byte[]) || ((byte[]) data).length > MAX_FRAME_BYTES
                    || limit < 1 || limit > MAX_FRAME_BYTES || writeOffset < 0
                    || writeOffset > MAX_OFFSET - ((byte[]) data).length
                    || batchOffset(frame.get("readAck")) < 0) {
                status(results, 400, "invalid BATCH frame");
                return;
            }
            bytes += ((byte[]) data).length + limit;
        }
        if (bytes > 1048576L) { results.put("code", Integer.valueOf(400)); return; }
        java.util.List replies = new java.util.ArrayList();
        for (int i = 0; i < frames.size(); i++) replies.add(exchangeFrame((Map) frames.get(i)));
        results.put("code", Integer.valueOf(200));
        results.put("frames", replies);
    }

    private static long batchOffset(Object value) {
        if (!(value instanceof Number)) return -1;
        Number number = (Number) value;
        long offset = number.longValue();
        return offset >= 0 && offset <= MAX_OFFSET && number.doubleValue() == offset ? offset : -1;
    }

    private Map exchangeFrame(Map frame) {
        String connId = (String) frame.get("connId");
        HashMap reply = new HashMap();
        reply.put("connId", connId);
        Object[] state = connMap.get(connId);
        if (state == null) { reply.put("code", Integer.valueOf(404)); return reply; }
        SocketChannel sc = (SocketChannel) state[CHANNEL];
        synchronized (state) {
            if (connMap.get(connId) != state) { reply.put("code", Integer.valueOf(404)); return reply; }
            long writeAck = ((Long) state[WRITE_ACK]).longValue();
            long readBase = ((Long) state[READ_BASE]).longValue();
            byte[] pending = (byte[]) state[PENDING];
            long writeOffset = batchOffset(frame.get("writeOffset"));
            long readAck = batchOffset(frame.get("readAck"));
            byte[] data = (byte[]) frame.get("data");
            if (writeOffset > writeAck || readAck > readBase + pending.length) {
                status(reply, 409, "offset gap or invalid read ACK");
                return reply;
            }
            // Stale retries never rewind an ACK. A lost response retains its read bytes here.
            int acknowledged = (int) Math.max(0L, readAck - readBase);
            if (acknowledged > 0) {
                byte[] rest = acknowledged == pending.length ? EMPTY : new byte[pending.length - acknowledged];
                System.arraycopy(pending, acknowledged, rest, 0, rest.length);
                pending = rest;
                readBase += acknowledged;
                state[READ_BASE] = Long.valueOf(readBase);
                state[PENDING] = pending;
            }
            int skip = (int) Math.min((long) data.length, writeAck - writeOffset);
            ByteBuffer outgoing = ByteBuffer.wrap(data, skip, data.length - skip);
            int limit = (int) batchOffset(frame.get("maxBytes"));
            ByteBuffer incoming = ByteBuffer.allocate(Math.max(pending.length, Math.min(BUFFER_SIZE, limit)));
            incoming.put(pending);
            int terminal = ((Integer) state[TERMINAL]).intValue();
            long deadline = System.nanoTime() + IO_BUDGET_NANOS;
            try {
                while (terminal == 0) {
                    if (!incoming.hasRemaining() && incoming.position() < limit) {
                        ByteBuffer larger = ByteBuffer.allocate(Math.min(limit, incoming.capacity() * 2));
                        incoming.flip(); larger.put(incoming); incoming = larger;
                    }
                    int written = outgoing.hasRemaining() ? sc.write(outgoing) : 0;
                    if (written > 0) { writeAck += written; state[WRITE_ACK] = Long.valueOf(writeAck); }
                    int read = incoming.position() < limit ? sc.read(incoming) : 0;
                    if (read < 0) { terminal = 404; break; }
                    if ((written == 0 && read == 0) || System.nanoTime() >= deadline
                            || (!outgoing.hasRemaining() && incoming.position() >= limit)) break;
                }
            } catch (IOException error) { terminal = 500; }
            pending = incoming.position() == 0 ? EMPTY : new byte[incoming.position()];
            incoming.flip(); incoming.get(pending);
            state[PENDING] = pending;
            state[TERMINAL] = Integer.valueOf(terminal);
            if (terminal != 0) closeQuietly(sc);
            ((AtomicLong) state[LAST_ACTIVITY]).set(System.currentTimeMillis());
            int size = Math.min(limit, pending.length);
            byte[] received = size == 0 ? EMPTY : new byte[size];
            System.arraycopy(pending, 0, received, 0, size);
            reply.put("code", Integer.valueOf(200));
            reply.put("writeAck", Long.valueOf(writeAck));
            reply.put("readOffset", Long.valueOf(readBase));
            reply.put("readCode", Integer.valueOf(terminal != 0 && size == pending.length
                    ? terminal : size == 0 ? 204 : 200));
            reply.put("data", received);
            return reply;
        }
    }

    /** 0=success, 1=duplicate, 2=capacity reached. */
    private static int registerConnection(String connId, SocketChannel socketChannel) {
        synchronized (connMap) {
            if (connMap.containsKey(connId)) return 1;
            if (connMap.size() >= MAX_CONNECTIONS) return 2;
            connMap.put(connId, new Object[]{socketChannel, Long.valueOf(0), Long.valueOf(0), EMPTY,
                    Integer.valueOf(0), new AtomicLong(System.currentTimeMillis())});
            return 0;
        }
    }

    private static boolean closeConnection(String connId, Object[] state) {
        if (state == null) return false;
        synchronized (connMap) {
            if (connMap.get(connId) != state) return false;
            connMap.remove(connId);
        }
        closeQuietly((SocketChannel) state[CHANNEL]);
        return true;
    }

    /**
     * 请求入口清理过期连接；连接锁保护活动时间，身份检查保护同 ID 的新连接。
     */
    private static void sweepIdleConns() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Object[]> entry : connMap.entrySet()) {
            Object[] state = entry.getValue();
            AtomicLong activity = (AtomicLong) state[LAST_ACTIVITY];
            if (now - activity.get() > IDLE_TIMEOUT_MS) {
                synchronized (state) {
                    if (now - activity.get() > IDLE_TIMEOUT_MS) closeConnection(entry.getKey(), state);
                }
            }
        }
    }

    private String getRequiredString(String key) {
        Object value = params.get(key);
        if (value instanceof String && ((String) value).length() > 0) return (String) value;
        status(results, 400, key + " required");
        return null;
    }

    private static void status(Map response, int code, String message) {
        response.put("code", Integer.valueOf(code));
        response.put("msg", message);
    }

    private static void closeQuietly(Closeable resource) {
        try { if (resource != null) resource.close(); } catch (IOException ignored) { }
    }
}
