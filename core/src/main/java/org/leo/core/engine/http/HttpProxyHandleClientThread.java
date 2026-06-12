package org.leo.core.engine.http;

import org.leo.core.engine.socks5.ReadDataThread;
import org.leo.core.engine.socks5.Socks5ProxyStatistics;
import org.leo.core.engine.socks5.WriteDataThread;
import org.leo.core.puppet.impl.JavaPuppetNode;
import org.leo.core.util.md5.MD5Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * HTTP 代理客户端处理线程。
 * 支持两种模式：
 * - CONNECT 隧道（HTTPS）：收到 CONNECT host:port 后建立 TCP 隧道
 * - 普通 HTTP 转发：将完整 HTTP 请求转发到目标主机
 */
public class HttpProxyHandleClientThread implements Runnable {

    private static final Logger logger = LoggerFactory.getLogger(HttpProxyHandleClientThread.class);
    private static final int MAX_HEADER_SIZE = 8192;

    private final Socket clientSocket;
    private final JavaPuppetNode javaPuppetNode;
    private final Socks5ProxyStatistics statistics;

    private String targetHost;
    private int targetPort;
    private String connId;

    public HttpProxyHandleClientThread(Socket clientSocket, JavaPuppetNode javaPuppetNode,
                                       Socks5ProxyStatistics statistics) {
        this.clientSocket = clientSocket;
        this.javaPuppetNode = javaPuppetNode;
        this.statistics = statistics;
    }

    @Override
    public void run() {
        try {
            InputStream in = clientSocket.getInputStream();
            OutputStream out = clientSocket.getOutputStream();

            // 读取并解析 HTTP 请求头
            byte[] headerBytes = readUntilHeaderEnd(in);
            if (headerBytes == null) {
                close(clientSocket);
                return;
            }

            String header = new String(headerBytes, StandardCharsets.ISO_8859_1);
            String firstLine = header.split("\r\n")[0];

            if (firstLine.startsWith("CONNECT ")) {
                handleConnect(firstLine, out);
            } else {
                handlePlainHttp(firstLine, header, headerBytes, out);
            }
        } catch (Exception e) {
            logger.debug("HTTP 代理处理异常: {}", e.getMessage());
            close(clientSocket);
        }
    }

    // ── CONNECT 隧道 ──────────────────────────────────────────────

    private void handleConnect(String firstLine, OutputStream out) throws Exception {
        // CONNECT host:port HTTP/1.1
        String[] parts = firstLine.split(" ");
        if (parts.length < 2) { close(clientSocket); return; }

        String hostPort = parts[1];
        parseHostPort(hostPort, 443);
        connId = generateConnId();

        if (!openRemoteSocket()) {
            out.write("HTTP/1.1 502 Bad Gateway\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            close(clientSocket);
            return;
        }

        out.write("HTTP/1.1 200 Connection Established\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
        // 握手完成后移除读超时，避免 relay 阶段长连接被误断
        try { clientSocket.setSoTimeout(0); } catch (Exception ignored) {}

        recordAndRelay();
    }

    // ── 普通 HTTP 转发 ─────────────────────────────────────────────

    private void handlePlainHttp(String firstLine, String header, byte[] headerBytes,
                                  OutputStream out) throws Exception {
        // GET http://host:port/path HTTP/1.1  or  GET /path HTTP/1.1
        String hostHeader = extractHeader(header, "Host");
        if (hostHeader == null) { close(clientSocket); return; }

        parseHostPort(hostHeader, 80);
        connId = generateConnId();

        if (!openRemoteSocket()) {
            out.write("HTTP/1.1 502 Bad Gateway\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            close(clientSocket);
            return;
        }

        // 将请求头转发到目标（去掉 Proxy-* 头）
        String cleaned = removProxyHeaders(header);
        byte[] toSend = cleaned.getBytes(StandardCharsets.ISO_8859_1);
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("op", 1);
        params.put("connId", connId);
        params.put("data", toSend);
        Map<String, Object> writeRes = javaPuppetNode.invokeComponent("ProxyForwardComponent", params);
        if (!Integer.valueOf(200).equals(writeRes.get("code"))) {
            out.write("HTTP/1.1 502 Bad Gateway\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            // 关闭 puppet 端连接
            Map<String, Object> closeParams = new HashMap<String, Object>();
            closeParams.put("op", 3);
            closeParams.put("connId", connId);
            try { javaPuppetNode.invokeComponent("ProxyForwardComponent", closeParams); } catch (Exception ignored) {}
            close(clientSocket);
            return;
        }

        recordAndRelay();
    }

    // ── 工具方法 ──────────────────────────────────────────────────

    private void recordAndRelay() throws InterruptedException {
        if (statistics != null) {
            statistics.addConnection(connId, targetHost, targetPort,
                    clientSocket.getInetAddress().getHostAddress());
        }
        try {
            Thread r = new Thread(new ReadDataThread(clientSocket, connId, javaPuppetNode, statistics));
            Thread w = new Thread(new WriteDataThread(clientSocket, connId, javaPuppetNode, statistics));
            r.setDaemon(true);
            w.setDaemon(true);
            r.start(); w.start();
            r.join(); w.join();
        } finally {
            if (statistics != null) statistics.removeConnection(connId);
            close(clientSocket);
        }
    }

    private boolean openRemoteSocket() {
        try {
            Map<String, Object> params = new HashMap<String, Object>();
            params.put("op", 0);
            params.put("targetHost", targetHost);
            params.put("targetPort", targetPort);
            params.put("connId", connId);
            Map<String, Object> res = javaPuppetNode.invokeComponent("ProxyForwardComponent", params);
            return Integer.valueOf(200).equals(res.get("code"));
        } catch (Exception e) {
            logger.debug("HTTP 代理打开远程连接失败: {}:{} - {}", targetHost, targetPort, e.getMessage());
            return false;
        }
    }

    private void parseHostPort(String hostPort, int defaultPort) {
        int colon = hostPort.lastIndexOf(':');
        if (colon > 0 && colon < hostPort.length() - 1) {
            try {
                this.targetPort = Integer.parseInt(hostPort.substring(colon + 1).trim());
                this.targetHost = hostPort.substring(0, colon).trim();
                return;
            } catch (NumberFormatException ignored) { }
        }
        this.targetHost = hostPort.trim();
        this.targetPort = defaultPort;
    }

    private String extractHeader(String headers, String name) {
        for (String line : headers.split("\r\n")) {
            int idx = line.indexOf(':');
            if (idx > 0 && line.substring(0, idx).trim().equalsIgnoreCase(name)) {
                return line.substring(idx + 1).trim();
            }
        }
        return null;
    }

    private String removProxyHeaders(String header) {
        StringBuilder sb = new StringBuilder();
        for (String line : header.split("\r\n")) {
            if (line.toLowerCase().startsWith("proxy-")) continue;
            sb.append(line).append("\r\n");
        }
        return sb.toString();
    }

    /** 读取 HTTP 请求头（直到 \r\n\r\n），最多 MAX_HEADER_SIZE 字节 */
    private byte[] readUntilHeaderEnd(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int b;
        int[] last = new int[4];
        int count = 0;
        while ((b = in.read()) != -1) {
            buf.write(b);
            last[count % 4] = b;
            count++;
            if (count >= 4) {
                // 检查最近4字节是否为 \r\n\r\n
                if (last[(count - 4) % 4] == '\r' && last[(count - 3) % 4] == '\n'
                        && last[(count - 2) % 4] == '\r' && last[(count - 1) % 4] == '\n') {
                    return buf.toByteArray();
                }
            }
            if (buf.size() > MAX_HEADER_SIZE) return null;
        }
        return null;
    }

    private String generateConnId() {
        return MD5Utils.getMD5Hash(clientSocket.getInetAddress() + ":" + clientSocket.getPort()
                + "-" + targetHost + ":" + targetPort + "-" + System.nanoTime());
    }

    private void close(Socket s) {
        try { if (s != null && !s.isClosed()) s.close(); } catch (IOException ignored) { }
    }
}
