package org.leo.core.net.impl;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.leo.core.net.Communication;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.Proxy;
import java.net.URI;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class WebSocketCommunication extends WebSocketClient implements Communication {

    private static final Logger logger = LoggerFactory.getLogger(WebSocketCommunication.class);

    // SSL trust-all 缓存
    private static volatile SSLSocketFactory trustAllSslSocketFactory;

    // 存放每条请求对应的Future
    private final ConcurrentHashMap<Long, CompletableFuture<byte[]>> pendingRequests = new ConcurrentHashMap<Long, CompletableFuture<byte[]>>();

    // 消息ID生成器
    private final AtomicLong messageIdGenerator = new AtomicLong(0);

    // 请求超时时间（毫秒）
    private static final long DEFAULT_REQUEST_TIMEOUT_MILLIS = 15000;
    private final long requestTimeoutMillis;

    // 异步连接等待（volatile 以支持 reconnect 时替换）
    private volatile CompletableFuture<Void> connectedFuture = new CompletableFuture<Void>();

    private final Proxy proxy;

    public WebSocketCommunication(String serverUri, Proxy proxy) throws Exception {
        this(serverUri, proxy, DEFAULT_REQUEST_TIMEOUT_MILLIS);
    }

    public WebSocketCommunication(String serverUri, Proxy proxy, long requestTimeoutMillis) throws Exception {
        super(new URI(serverUri));
        this.proxy = proxy;
        this.requestTimeoutMillis = requestTimeoutMillis > 0 ? requestTimeoutMillis : DEFAULT_REQUEST_TIMEOUT_MILLIS;
    }

    @Override
    public void connect() {
        if (proxy != null) {
            setProxy(proxy);
        }
        // wss:// 忽略证书验证
        if ("wss".equalsIgnoreCase(getURI().getScheme())) {
            try {
                setSocketFactory(getTrustAllSslSocketFactory());
            } catch (Exception e) {
                logger.warn("Failed to set trust-all SSL for wss://, falling back to default", e);
            }
        }
        super.connect();
    }

    @Override
    public void onOpen(ServerHandshake handshake) {
        logger.info("WebSocket connected: {}", getURI());
        connectedFuture.complete(null);
    }

    /**
     * 重连时重置 connectedFuture，使后续 sendRequest 能正常等待新连接
     */
    @Override
    public void reconnect() {
        connectedFuture = new CompletableFuture<Void>();
        super.reconnect();
    }

    @Override
    public boolean reconnectBlocking() throws InterruptedException {
        connectedFuture = new CompletableFuture<Void>();
        return super.reconnectBlocking();
    }

    @Override
    public void onMessage(String s) {
        logger.debug("WebSocket text message received: {}", s);
    }

    @Override
    public void onMessage(ByteBuffer buffer) {
        if (buffer.remaining() < Long.BYTES) {
            logger.warn("Invalid message received, remaining bytes: {}", buffer.remaining());
            return;
        }
        long messageId = buffer.getLong();
        byte[] data = new byte[buffer.remaining()];
        buffer.get(data);
        CompletableFuture<byte[]> future = pendingRequests.remove(messageId);
        if (future != null) {
            future.complete(data);
        } else {
            logger.warn("No pending request for messageId={}", messageId);
        }
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        logger.info("WebSocket closed: code={}, reason={}, remote={}", code, reason, remote);
        Exception ex = new IllegalStateException("WebSocket closed: " + reason);
        failAllPendingRequests(ex);
        connectedFuture.completeExceptionally(ex);
    }

    @Override
    public void onError(Exception ex) {
        logger.error("WebSocket error: {}", ex.getMessage(), ex);
        failAllPendingRequests(ex);
        connectedFuture.completeExceptionally(ex);
    }

    /**
     * 线程安全的同步发送请求方法
     */
    @Override
    public byte[] sendRequest(byte[] data) throws Exception {

        // 等待连接完成
        connectedFuture.get(requestTimeoutMillis, TimeUnit.MILLISECONDS);
        long messageId = messageIdGenerator.incrementAndGet();
        CompletableFuture<byte[]> future = new CompletableFuture<byte[]>();
        pendingRequests.put(messageId, future);

        try {
            ByteBuffer buffer = ByteBuffer.wrap(wrapMessage(messageId, data));
            // 发送消息，如果连接断开则尝试重连
            if (isOpen()) {
                send(buffer);
            } else {
                if (reconnectBlocking()) {
                    send(buffer);
                } else {
                    throw new IllegalStateException("WebSocket reconnect failed");
                }
            }

            // 超时处理
            return future.get(requestTimeoutMillis, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            pendingRequests.remove(messageId);
            throw e;
        }
    }

    /**
     * 将 messageId + 数据拼接成完整消息
     */
    private byte[] wrapMessage(long messageId, byte[] body) {
        ByteBuffer buffer = ByteBuffer.allocate(Long.BYTES + body.length);
        buffer.putLong(messageId);
        buffer.put(body);
        return buffer.array();
    }

    /**
     * 连接异常或关闭时，失败所有 pending 请求
     */
    private void failAllPendingRequests(Exception ex) {
        for (Map.Entry<Long, CompletableFuture<byte[]>> entry : pendingRequests.entrySet()) {
            entry.getValue().completeExceptionally(ex);
        }
        pendingRequests.clear();
    }

    private static SSLSocketFactory getTrustAllSslSocketFactory() throws Exception {
        SSLSocketFactory current = trustAllSslSocketFactory;
        if (current != null) {
            return current;
        }
        synchronized (WebSocketCommunication.class) {
            if (trustAllSslSocketFactory == null) {
                TrustManager[] trustAllCerts = new TrustManager[]{
                        new X509TrustManager() {
                            @Override
                            public void checkClientTrusted(X509Certificate[] chain, String authType) {
                            }

                            @Override
                            public void checkServerTrusted(X509Certificate[] chain, String authType) {
                            }

                            @Override
                            public X509Certificate[] getAcceptedIssuers() {
                                return new X509Certificate[0];
                            }
                        }
                };
                SSLContext ctx = SSLContext.getInstance("TLS");
                ctx.init(null, trustAllCerts, new SecureRandom());
                trustAllSslSocketFactory = ctx.getSocketFactory();
            }
            return trustAllSslSocketFactory;
        }
    }
}
