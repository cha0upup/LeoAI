package org.leo.core.infrastructure.transport;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.leo.core.net.Communication;
import org.leo.core.net.TransportException;
import org.leo.core.net.TransportLimits;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.Proxy;
import java.net.URI;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/** WebSocket request/response transport with bounded application fragmentation. */
public class WebSocketCommunication extends WebSocketClient implements Communication {
    private static final Logger logger = LoggerFactory.getLogger(WebSocketCommunication.class);
    private static final long DEFAULT_REQUEST_TIMEOUT_MILLIS =
            TransportLimits.READ_TIMEOUT_MILLIS;

    private static volatile SSLSocketFactory trustAllSslSocketFactory;

    private final ConcurrentHashMap<Long, CompletableFuture<byte[]>> pendingRequests =
            new ConcurrentHashMap<Long, CompletableFuture<byte[]>>();
    private final ConcurrentHashMap<Long, WebSocketFrameCodec.Accumulator> inboundMessages =
            new ConcurrentHashMap<Long, WebSocketFrameCodec.Accumulator>();
    private final AtomicLong messageIdGenerator = new AtomicLong();
    private final Object connectionLock = new Object();
    private final long requestTimeoutMillis;
    private final Proxy proxy;

    private volatile CompletableFuture<Void> connectedFuture = new CompletableFuture<Void>();

    public WebSocketCommunication(String serverUri, Proxy proxy) throws Exception {
        this(serverUri, proxy, DEFAULT_REQUEST_TIMEOUT_MILLIS);
    }

    public WebSocketCommunication(String serverUri, Proxy proxy, long requestTimeoutMillis)
            throws Exception {
        super(new URI(serverUri));
        this.proxy = proxy;
        this.requestTimeoutMillis = requestTimeoutMillis > 0
                ? requestTimeoutMillis : DEFAULT_REQUEST_TIMEOUT_MILLIS;
    }

    @Override
    public void connect() {
        configureSocket();
        super.connect();
    }

    private void configureSocket() {
        if (proxy != null) {
            setProxy(proxy);
        }
        if ("wss".equalsIgnoreCase(getURI().getScheme())) {
            try {
                setSocketFactory(getTrustAllSslSocketFactory());
            } catch (Exception e) {
                logger.warn("Failed to configure WebSocket TLS socket", e);
            }
        }
    }

    @Override
    public void onOpen(ServerHandshake handshake) {
        logger.info("WebSocket connected: {}", getURI());
        CompletableFuture<Void> future = connectedFuture;
        if (!future.complete(null) && future.isCompletedExceptionally()) {
            connectedFuture = CompletableFuture.completedFuture(null);
        }
    }

    @Override
    public void reconnect() {
        synchronized (connectionLock) {
            connectedFuture = new CompletableFuture<Void>();
            configureSocket();
            super.reconnect();
        }
    }

    @Override
    public boolean reconnectBlocking() throws InterruptedException {
        synchronized (connectionLock) {
            connectedFuture = new CompletableFuture<Void>();
            configureSocket();
            return super.reconnectBlocking();
        }
    }

    @Override
    public void onMessage(String message) {
        logger.debug("Ignoring WebSocket text message of {} chars",
                message == null ? 0 : message.length());
    }

    @Override
    public void onMessage(ByteBuffer buffer) {
        Long recoverableMessageId = readMessageIdIfPresent(buffer);
        try {
            WebSocketFrameCodec.Frame frame = WebSocketFrameCodec.decode(buffer);
            CompletableFuture<byte[]> future = pendingRequests.get(frame.messageId);
            if (future == null) {
                inboundMessages.remove(frame.messageId);
                logger.debug("Dropping response for expired request messageId={}", frame.messageId);
                return;
            }

            WebSocketFrameCodec.Accumulator accumulator = inboundMessages.get(frame.messageId);
            if (accumulator == null) {
                WebSocketFrameCodec.Accumulator created =
                        new WebSocketFrameCodec.Accumulator(frame);
                WebSocketFrameCodec.Accumulator previous =
                        inboundMessages.putIfAbsent(frame.messageId, created);
                accumulator = previous == null ? created : previous;
            }

            byte[] completed = accumulator.accept(frame);
            if (completed != null) {
                inboundMessages.remove(frame.messageId, accumulator);
                CompletableFuture<byte[]> completedFuture = pendingRequests.remove(frame.messageId);
                if (completedFuture != null) {
                    completedFuture.complete(completed);
                }
            }
        } catch (Exception e) {
            logger.warn("Invalid WebSocket response frame", e);
            if (recoverableMessageId != null) {
                failRequest(recoverableMessageId.longValue(), e);
            }
        }
    }

    private Long readMessageIdIfPresent(ByteBuffer source) {
        if (source == null || source.remaining() < 1 + Long.BYTES) {
            return null;
        }
        ByteBuffer copy = source.slice();
        copy.get();
        return Long.valueOf(copy.getLong());
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        logger.info("WebSocket closed: code={}, reason={}, remote={}", code, reason, remote);
        TransportException exception = new TransportException(
                TransportException.Reason.CONNECTION_CLOSED,
                "WebSocket closed: " + reason);
        failAllPendingRequests(exception);
        connectedFuture.completeExceptionally(exception);
    }

    @Override
    public void onError(Exception error) {
        logger.error("WebSocket error: {}", error.getMessage(), error);
        TransportException exception = new TransportException(
                TransportException.Reason.READ_FAILED, "WebSocket transport error", error);
        failAllPendingRequests(exception);
        connectedFuture.completeExceptionally(exception);
    }

    @Override
    public byte[] sendRequest(byte[] data) throws Exception {
        byte[] request = data == null ? new byte[0] : data;
        TransportLimits.requireMessageSize(request);
        ensureOpen();

        long messageId = messageIdGenerator.incrementAndGet();
        CompletableFuture<byte[]> future = new CompletableFuture<byte[]>();
        pendingRequests.put(messageId, future);
        try {
            int fragmentCount = WebSocketFrameCodec.fragmentCount(request.length);
            for (int fragmentIndex = 0; fragmentIndex < fragmentCount; fragmentIndex++) {
                if (!isOpen()) {
                    throw new TransportException(TransportException.Reason.CONNECTION_CLOSED,
                            "WebSocket closed while sending request");
                }
                send(WebSocketFrameCodec.encode(messageId, request, fragmentIndex));
            }
            try {
                return future.get(requestTimeoutMillis, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                throw new TransportException(TransportException.Reason.READ_TIMEOUT,
                        "WebSocket response timed out for messageId=" + messageId, e);
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof Exception) {
                    throw (Exception) cause;
                }
                throw e;
            }
        } finally {
            pendingRequests.remove(messageId);
            inboundMessages.remove(messageId);
        }
    }

    private void ensureOpen() throws Exception {
        if (isOpen()) {
            return;
        }
        synchronized (connectionLock) {
            if (isOpen()) {
                return;
            }
            if (isClosed() || isClosing()) {
                connectedFuture = new CompletableFuture<Void>();
                configureSocket();
                if (!super.reconnectBlocking()) {
                    throw new TransportException(TransportException.Reason.CONNECT_FAILED,
                            "WebSocket reconnect failed");
                }
                return;
            }

            CompletableFuture<Void> future = connectedFuture;
            try {
                future.get(requestTimeoutMillis, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                throw new TransportException(TransportException.Reason.CONNECT_FAILED,
                        "WebSocket connection timed out", e);
            } catch (ExecutionException e) {
                throw new TransportException(TransportException.Reason.CONNECT_FAILED,
                        "WebSocket connection failed", e.getCause());
            }
            if (!isOpen()) {
                throw new TransportException(TransportException.Reason.CONNECT_FAILED,
                        "WebSocket connection is not open");
            }
        }
    }

    private void failRequest(long messageId, Exception error) {
        inboundMessages.remove(messageId);
        CompletableFuture<byte[]> future = pendingRequests.remove(messageId);
        if (future != null) {
            future.completeExceptionally(error);
        }
    }

    private void failAllPendingRequests(Exception error) {
        for (Map.Entry<Long, CompletableFuture<byte[]>> entry : pendingRequests.entrySet()) {
            entry.getValue().completeExceptionally(error);
        }
        pendingRequests.clear();
        inboundMessages.clear();
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
                            public void checkClientTrusted(X509Certificate[] chain, String authType) { }

                            @Override
                            public void checkServerTrusted(X509Certificate[] chain, String authType) { }

                            @Override
                            public X509Certificate[] getAcceptedIssuers() {
                                return new X509Certificate[0];
                            }
                        }
                };
                SSLContext context = SSLContext.getInstance("TLS");
                context.init(null, trustAllCerts, new SecureRandom());
                trustAllSslSocketFactory = context.getSocketFactory();
            }
            return trustAllSslSocketFactory;
        }
    }
}
