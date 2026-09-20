package org.leo.core.engine.http;

import org.leo.core.engine.proxy.ProxyConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

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
    private final ProxyConnection connection;
    private String targetHost;
    private int targetPort;

    public HttpProxyHandleClientThread(ProxyConnection connection) {
        this.connection = connection;
        this.clientSocket = connection.socket();
    }

    @Override
    public void run() {
        try (connection) {
            InputStream in = clientSocket.getInputStream();
            OutputStream out = clientSocket.getOutputStream();

            // 读取并解析 HTTP 请求头
            byte[] headerBytes = readUntilHeaderEnd(in);
            if (headerBytes == null) {
                return;
            }

            String header = new String(headerBytes, StandardCharsets.ISO_8859_1);
            String firstLine = header.split("\r\n")[0];

            if (firstLine.startsWith("CONNECT ")) {
                handleConnect(firstLine, out);
            } else {
                handlePlainHttp(header, out);
            }
        } catch (Exception e) {
            logger.debug("HTTP 代理处理异常: {}", e.getMessage());
        }
    }

    // ── CONNECT 隧道 ──────────────────────────────────────────────

    private void handleConnect(String firstLine, OutputStream out) throws Exception {
        // CONNECT host:port HTTP/1.1
        String[] parts = firstLine.split(" ");
        if (parts.length < 2) { return; }

        String hostPort = parts[1];
        parseHostPort(hostPort, 443);

        if (!connection.open(targetHost, targetPort)) {
            out.write("HTTP/1.1 502 Bad Gateway\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            return;
        }

        out.write("HTTP/1.1 200 Connection Established\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
        connection.relay();
    }

    // ── 普通 HTTP 转发 ─────────────────────────────────────────────

    private void handlePlainHttp(String header, OutputStream out) throws Exception {
        // GET http://host:port/path HTTP/1.1  or  GET /path HTTP/1.1
        String hostHeader = extractHeader(header, "Host");
        if (hostHeader == null) { return; }

        parseHostPort(hostHeader, 80);

        if (!connection.open(targetHost, targetPort)) {
            out.write("HTTP/1.1 502 Bad Gateway\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            return;
        }

        // 将请求头转发到目标（去掉 Proxy-* 头）
        byte[] toSend = removeProxyHeaders(header).getBytes(StandardCharsets.ISO_8859_1);
        try {
            connection.write(toSend);
        } catch (Exception error) {
            out.write("HTTP/1.1 502 Bad Gateway\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            return;
        }

        connection.relay();
    }

    // ── 工具方法 ──────────────────────────────────────────────────

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

    private String removeProxyHeaders(String header) {
        StringBuilder sb = new StringBuilder();
        for (String line : header.split("\r\n")) {
            if (line.toLowerCase(Locale.ROOT).startsWith("proxy-")) continue;
            sb.append(line).append("\r\n");
        }
        return sb.append("\r\n").toString();
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
            if (count > MAX_HEADER_SIZE) return null;
            if (count >= 4) {
                // 检查最近4字节是否为 \r\n\r\n
                if (last[(count - 4) % 4] == '\r' && last[(count - 3) % 4] == '\n'
                        && last[(count - 2) % 4] == '\r' && last[(count - 1) % 4] == '\n') {
                    return buf.toByteArray();
                }
            }
        }
        return null;
    }
}
