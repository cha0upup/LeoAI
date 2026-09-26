package org.leo.core.net.impl.httpchunk;

import org.leo.core.net.TransportException;
import org.leo.core.net.TransportLimits;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A dedicated HTTP/1.1 connection containing one long-lived chunked request
 * body and one concurrently consumed chunked response body.
 */
public final class Http11DuplexChannel implements Closeable {
    private static final Logger logger = LoggerFactory.getLogger(Http11DuplexChannel.class);
    private static volatile SSLSocketFactory trustAllSslSocketFactory;

    private static final int MAX_RECONNECT_ATTEMPTS = 5;
    private static final long RECONNECT_BASE_DELAY_MILLIS = 500L;
    private static final long HEARTBEAT_IDLE_MILLIS = 5000L;
    private static final long HEARTBEAT_INTERVAL_MILLIS = 6000L;
    private static final long HEARTBEAT_TIMEOUT_MILLIS = 10000L;
    private static final int MAX_PENDING_REQUESTS = 256;
    private static final long MAX_IN_FLIGHT_REQUEST_BYTES = TransportLimits.MAX_MESSAGE_BYTES;

    public enum State {
        NEW, CONNECTING, OPEN, RECONNECTING, CLOSING, CLOSED
    }

    private final URI endpoint;
    private final String method;
    private final Map<String, String> headers;
    private final Proxy proxy;
    private final Object connectionLock = new Object();
    private final Object writeLock = new Object();
    private final AtomicLong transportIds = new AtomicLong();
    private final AtomicLong epochs = new AtomicLong();
    private final AtomicLong lastActivity = new AtomicLong(System.currentTimeMillis());
    private final AtomicBoolean recoveryRunning = new AtomicBoolean();
    private final ConcurrentHashMap<Long, CompletableFuture<byte[]>> pendingRequests =
            new ConcurrentHashMap<Long, CompletableFuture<byte[]>>();
    private final ConcurrentHashMap<CompletableFuture<byte[]>, Integer> pendingRequestSizes =
            new ConcurrentHashMap<CompletableFuture<byte[]>, Integer>();
    private final AtomicLong inFlightRequestBytes = new AtomicLong();
    private final ConcurrentHashMap<Long, CompletableFuture<Void>> pendingPings =
            new ConcurrentHashMap<Long, CompletableFuture<Void>>();

    private volatile State state = State.NEW;
    private volatile boolean userClosed;
    private volatile Socket socket;
    private volatile DataInputStream frameInput;
    private volatile DataOutputStream frameOutput;
    private volatile HttpChunkedBodyOutputStream chunkedOutput;
    private volatile Thread readerThread;
    private volatile Thread heartbeatThread;

    public Http11DuplexChannel(String url, String method, Map<String, String> headers, Proxy proxy)
            throws Exception {
        this.endpoint = URI.create(url);
        String scheme = endpoint.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw new IllegalArgumentException("HTTP chunked endpoint requires http:// or https://");
        }
        if (endpoint.getHost() == null || endpoint.getHost().isEmpty()) {
            throw new IllegalArgumentException("HTTP chunked endpoint host is missing");
        }
        this.method = method == null || method.isEmpty() ? "POST" : method.toUpperCase(Locale.ROOT);
        if (!this.method.matches("[A-Z]+")) {
            throw new IllegalArgumentException("Invalid HTTP method");
        }
        this.headers = headers == null
                ? new ConcurrentHashMap<String, String>() : headers;
        this.proxy = proxy == null ? Proxy.NO_PROXY : proxy;
        establishInitialConnection();
        startHeartbeat();
    }

    public byte[] sendRequest(byte[] data) throws Exception {
        return await(sendAsync(data), TransportLimits.READ_TIMEOUT_MILLIS);
    }

    private CompletableFuture<byte[]> sendAsync(byte[] data) throws Exception {
        byte[] payload = data == null ? new byte[0] : data;
        TransportLimits.requireMessageSize(payload);
        ensureOpen();

        if (pendingRequests.size() >= MAX_PENDING_REQUESTS) {
            throw new TransportException(TransportException.Reason.OVERLOADED,
                    "HTTP duplex pending request limit reached");
        }
        long totalBytes = inFlightRequestBytes.addAndGet(payload.length);
        if (totalBytes > MAX_IN_FLIGHT_REQUEST_BYTES) {
            inFlightRequestBytes.addAndGet(-payload.length);
            throw new TransportException(TransportException.Reason.OVERLOADED,
                    "HTTP duplex in-flight request bytes exceed limit");
        }

        long transportId = transportIds.incrementAndGet();
        CompletableFuture<byte[]> future = new CompletableFuture<byte[]>();
        pendingRequestSizes.put(future, Integer.valueOf(payload.length));
        pendingRequests.put(transportId, future);
        long epoch = epochs.get();
        try {
            writeFrame(DuplexFrameCodec.TYPE_DATA, transportId, payload);
            return future;
        } catch (IOException e) {
            pendingRequests.remove(transportId);
            releaseRequest(future);
            future.completeExceptionally(e);
            handleConnectionFailure(e, epoch);
            throw new TransportException(TransportException.Reason.WRITE_FAILED,
                    "HTTP duplex request write failed", e);
        }
    }

    private byte[] await(CompletableFuture<byte[]> future, long timeoutMillis) throws Exception {
        try {
            return future.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            pendingRequests.values().remove(future);
            releaseRequest(future);
            throw new TransportException(TransportException.Reason.READ_TIMEOUT,
                    "HTTP duplex response timed out", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            throw e;
        }
    }

    public void pingNow() throws Exception {
        ensureOpen();
        if (!pendingRequests.isEmpty()) return;
        long transportId = transportIds.incrementAndGet();
        CompletableFuture<Void> future = new CompletableFuture<Void>();
        pendingPings.put(transportId, future);
        long epoch = epochs.get();
        try {
            writeFrame(DuplexFrameCodec.TYPE_PING, transportId, new byte[0]);
            future.get(HEARTBEAT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            pendingPings.remove(transportId);
            handleConnectionFailure(e, epoch);
            if (e instanceof ExecutionException && e.getCause() instanceof Exception) {
                throw (Exception) e.getCause();
            }
            throw e;
        }
    }

    public State getState() {
        return state;
    }

    public boolean isClosed() {
        return userClosed || state == State.CLOSED;
    }

    private void establishInitialConnection() throws Exception {
        synchronized (connectionLock) {
            establishConnection(State.CONNECTING);
        }
    }

    private void ensureOpen() throws Exception {
        if (state == State.OPEN) return;
        if (userClosed) {
            throw new TransportException(TransportException.Reason.CONNECTION_CLOSED,
                    "HTTP duplex channel is closed");
        }
        if (!reconnectWithBackoff()) {
            throw new TransportException(TransportException.Reason.CONNECT_FAILED,
                    "HTTP duplex channel is unavailable");
        }
    }

    private boolean reconnectWithBackoff() throws Exception {
        synchronized (connectionLock) {
            if (state == State.OPEN) return true;
            Exception lastFailure = null;
            for (int attempt = 1; attempt <= MAX_RECONNECT_ATTEMPTS && !userClosed; attempt++) {
                if (attempt > 1) {
                    long delay = Math.min(8000L,
                            RECONNECT_BASE_DELAY_MILLIS << Math.min(attempt - 2, 4));
                    long jitter = Math.abs(transportIds.incrementAndGet() % Math.max(1L, delay / 2L));
                    Thread.sleep(delay + jitter);
                }
                try {
                    establishConnection(State.RECONNECTING);
                    return true;
                } catch (Exception e) {
                    lastFailure = e;
                    logger.warn("HTTP duplex reconnect attempt {}/{} failed",
                            attempt, MAX_RECONNECT_ATTEMPTS, e);
                }
            }
            if (lastFailure != null) throw lastFailure;
            return false;
        }
    }

    private void establishConnection(State connectingState) throws Exception {
        if (userClosed) {
            throw new TransportException(TransportException.Reason.CONNECTION_CLOSED,
                    "HTTP duplex channel is closed");
        }
        state = connectingState;
        long epoch = epochs.incrementAndGet();
        closeSocketOnly();

        Socket openedSocket = null;
        try {
            openedSocket = openSocket();
            openedSocket.setTcpNoDelay(true);
            openedSocket.setKeepAlive(true);
            openedSocket.setSoTimeout(TransportLimits.READ_TIMEOUT_MILLIS);

            BufferedInputStream rawInput = new BufferedInputStream(openedSocket.getInputStream(), 8192);
            BufferedOutputStream rawOutput = new BufferedOutputStream(openedSocket.getOutputStream(), 8192);
            writeRequestHead(rawOutput);
            HttpResponseHead response = HttpResponseHead.readFinal(rawInput);
            if (!response.hasToken("Transfer-Encoding", "chunked")) {
                throw new TransportException(TransportException.Reason.FRAME_INVALID,
                        "HTTP duplex response is not chunked, status=" + response.statusCode);
            }
            String contentEncoding = response.header("Content-Encoding");
            if (contentEncoding != null && !contentEncoding.isEmpty()
                    && !"identity".equalsIgnoreCase(contentEncoding)) {
                throw new TransportException(TransportException.Reason.FRAME_INVALID,
                        "HTTP duplex response Content-Encoding must be identity");
            }

            HttpChunkedBodyInputStream bodyInput = new HttpChunkedBodyInputStream(rawInput);
            HttpChunkedBodyOutputStream bodyOutput = new HttpChunkedBodyOutputStream(rawOutput);
            DataInputStream newFrameInput = new DataInputStream(
                    new BufferedInputStream(bodyInput, 64 * 1024));
            DataOutputStream newFrameOutput = new DataOutputStream(
                    new BufferedOutputStream(bodyOutput, 64 * 1024));

            socket = openedSocket;
            frameInput = newFrameInput;
            frameOutput = newFrameOutput;
            chunkedOutput = bodyOutput;
            lastActivity.set(System.currentTimeMillis());
            state = State.OPEN;
            startReader(epoch, newFrameInput);
            logger.info("HTTP duplex channel connected: {} status={}", endpoint, response.statusCode);
        } catch (Exception e) {
            state = userClosed ? State.CLOSED : State.RECONNECTING;
            closeQuietly(openedSocket);
            throw e;
        }
    }

    private Socket openSocket() throws Exception {
        boolean secure = "https".equalsIgnoreCase(endpoint.getScheme());
        String host = endpoint.getHost();
        int port = endpoint.getPort() >= 0 ? endpoint.getPort() : secure ? 443 : 80;
        InetSocketAddress target = new InetSocketAddress(host, port);
        Socket base;

        if (proxy.type() == Proxy.Type.SOCKS) {
            base = new Socket(proxy);
            base.connect(target, TransportLimits.CONNECT_TIMEOUT_MILLIS);
        } else if (proxy.type() == Proxy.Type.HTTP) {
            if (!(proxy.address() instanceof InetSocketAddress)) {
                throw new IOException("HTTP proxy address is invalid");
            }
            base = new Socket();
            base.connect((InetSocketAddress) proxy.address(), TransportLimits.CONNECT_TIMEOUT_MILLIS);
            if (secure) establishHttpProxyTunnel(base, host, port);
        } else {
            base = new Socket();
            base.connect(target, TransportLimits.CONNECT_TIMEOUT_MILLIS);
        }

        if (!secure) return base;
        base.setSoTimeout(TransportLimits.CONNECT_TIMEOUT_MILLIS);
        SSLSocketFactory factory = getTrustAllSslSocketFactory();
        SSLSocket tlsSocket = (SSLSocket) factory.createSocket(base, host, port, true);
        tlsSocket.setUseClientMode(true);
        tlsSocket.startHandshake();
        return tlsSocket;
    }

    private void establishHttpProxyTunnel(Socket proxySocket, String host, int port) throws IOException {
        proxySocket.setSoTimeout(TransportLimits.CONNECT_TIMEOUT_MILLIS);
        InputStream input = new BufferedInputStream(proxySocket.getInputStream(), 4096);
        OutputStream output = new BufferedOutputStream(proxySocket.getOutputStream(), 4096);
        String authority = formatAuthority(host, port);
        StringBuilder request = new StringBuilder();
        request.append("CONNECT ").append(authority).append(" HTTP/1.1\r\n")
                .append("Host: ").append(authority).append("\r\n");
        String proxyAuthorization = findHeader("Proxy-Authorization");
        if (proxyAuthorization != null) {
            appendHeader(request, "Proxy-Authorization", proxyAuthorization);
        }
        request.append("\r\n");
        output.write(request.toString().getBytes(StandardCharsets.ISO_8859_1));
        output.flush();
        HttpResponseHead response = HttpResponseHead.readFinal(input);
        if (response.statusCode != 200) {
            throw new IOException("HTTP proxy CONNECT failed with status " + response.statusCode);
        }
        proxySocket.setSoTimeout(TransportLimits.READ_TIMEOUT_MILLIS);
    }

    private void writeRequestHead(OutputStream output) throws IOException {
        boolean plainHttpProxy = proxy.type() == Proxy.Type.HTTP
                && "http".equalsIgnoreCase(endpoint.getScheme());
        int port = endpoint.getPort() >= 0 ? endpoint.getPort()
                : "https".equalsIgnoreCase(endpoint.getScheme()) ? 443 : 80;
        String requestTarget = plainHttpProxy
                ? endpoint.getScheme() + "://" + formatAuthority(endpoint.getHost(), port)
                    + originForm(endpoint)
                : originForm(endpoint);
        StringBuilder request = new StringBuilder();
        request.append(method).append(' ').append(requestTarget).append(" HTTP/1.1\r\n");
        appendHeader(request, "Host", formatAuthority(endpoint.getHost(), port));
        appendHeader(request, "Transfer-Encoding", "chunked");
        appendHeader(request, "Accept-Encoding", "identity");
        appendHeader(request, "Connection", "keep-alive");
        appendHeader(request, "Content-Type", "application/octet-stream");
        if (plainHttpProxy) {
            String proxyAuthorization = findHeader("Proxy-Authorization");
            if (proxyAuthorization != null) {
                appendHeader(request, "Proxy-Authorization", proxyAuthorization);
            }
        }

        for (Map.Entry<String, String> entry : headers.entrySet()) {
            String name = entry.getKey();
            String value = entry.getValue();
            if (name == null || value == null || isReservedHeader(name)) continue;
            appendHeader(request, name, value);
        }
        request.append("\r\n");
        output.write(request.toString().getBytes(StandardCharsets.ISO_8859_1));
        output.flush();
    }

    private void startReader(final long epoch, final DataInputStream input) {
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                readFrames(epoch, input);
            }
        }, "Http11DuplexChannel-Reader");
        thread.setDaemon(true);
        readerThread = thread;
        thread.start();
    }

    private void readFrames(long epoch, DataInputStream input) {
        try {
            while (!userClosed && state == State.OPEN && epochs.get() == epoch) {
                DuplexFrameCodec.Frame frame = DuplexFrameCodec.read(input);
                lastActivity.set(System.currentTimeMillis());
                if (frame.type == DuplexFrameCodec.TYPE_DATA) {
                    CompletableFuture<byte[]> future = pendingRequests.remove(frame.transportId);
                    if (future != null) {
                        releaseRequest(future);
                        future.complete(frame.payload);
                    }
                } else if (frame.type == DuplexFrameCodec.TYPE_PONG) {
                    CompletableFuture<Void> future = pendingPings.remove(frame.transportId);
                    if (future != null) future.complete(null);
                } else if (frame.type == DuplexFrameCodec.TYPE_PING) {
                    writeFrame(DuplexFrameCodec.TYPE_PONG, frame.transportId, new byte[0]);
                } else if (frame.type == DuplexFrameCodec.TYPE_CLOSE) {
                    throw new TransportException(TransportException.Reason.CONNECTION_CLOSED,
                            "HTTP duplex peer closed the channel");
                }
            }
        } catch (Exception e) {
            handleConnectionFailure(e, epoch);
        }
    }

    private void writeFrame(int type, long transportId, byte[] payload) throws IOException {
        synchronized (writeLock) {
            if (state != State.OPEN || frameOutput == null) {
                throw new TransportException(TransportException.Reason.CONNECTION_CLOSED,
                        "HTTP duplex channel is not open");
            }
            DuplexFrameCodec.write(frameOutput, type, transportId, payload);
            lastActivity.set(System.currentTimeMillis());
        }
    }

    private void handleConnectionFailure(Throwable failure, long epoch) {
        if (userClosed || epochs.get() != epoch) return;
        logger.warn("HTTP duplex channel failed: {}", failure.getMessage());
        synchronized (connectionLock) {
            if (userClosed || epochs.get() != epoch) return;
            epochs.incrementAndGet();
            state = State.RECONNECTING;
            closeSocketOnly();
        }
        TransportException exception = classifyReadFailure(failure);
        failAllPending(exception);
        startRecovery();
    }

    private void startRecovery() {
        if (userClosed || !recoveryRunning.compareAndSet(false, true)) return;
        Thread recovery = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    reconnectWithBackoff();
                } catch (Exception e) {
                    logger.warn("HTTP duplex background reconnect failed", e);
                } finally {
                    recoveryRunning.set(false);
                }
            }
        }, "Http11DuplexChannel-Reconnect");
        recovery.setDaemon(true);
        recovery.start();
    }

    private void startHeartbeat() {
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (!userClosed) {
                    try {
                        Thread.sleep(HEARTBEAT_INTERVAL_MILLIS);
                        if (state == State.OPEN && pendingRequests.isEmpty()
                                && System.currentTimeMillis() - lastActivity.get()
                                >= HEARTBEAT_IDLE_MILLIS) {
                            pingNow();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    } catch (Exception e) {
                        logger.debug("HTTP duplex heartbeat failed", e);
                    }
                }
            }
        }, "Http11DuplexChannel-Heartbeat");
        thread.setDaemon(true);
        heartbeatThread = thread;
        thread.start();
    }

    private void failAllPending(Exception error) {
        for (CompletableFuture<byte[]> future : pendingRequests.values()) {
            releaseRequest(future);
            future.completeExceptionally(error);
        }
        pendingRequests.clear();
        for (CompletableFuture<Void> future : pendingPings.values()) {
            future.completeExceptionally(error);
        }
        pendingPings.clear();
    }

    private void releaseRequest(CompletableFuture<byte[]> future) {
        Integer size = pendingRequestSizes.remove(future);
        if (size != null) inFlightRequestBytes.addAndGet(-size.intValue());
    }

    @Override
    public void close() {
        if (userClosed) return;
        userClosed = true;
        state = State.CLOSING;
        Thread heartbeat = heartbeatThread;
        if (heartbeat != null) heartbeat.interrupt();
        synchronized (connectionLock) {
            long transportId = transportIds.incrementAndGet();
            try {
                if (frameOutput != null && socket != null && !socket.isClosed()) {
                    synchronized (writeLock) {
                        DuplexFrameCodec.write(frameOutput, DuplexFrameCodec.TYPE_CLOSE,
                                transportId, new byte[0]);
                        frameOutput.flush();
                        if (chunkedOutput != null) chunkedOutput.finish();
                    }
                }
            } catch (Exception ignored) {
            }
            epochs.incrementAndGet();
            closeSocketOnly();
            state = State.CLOSED;
        }
        failAllPending(new TransportException(TransportException.Reason.CONNECTION_CLOSED,
                "HTTP duplex channel is closed"));
    }

    private void closeSocketOnly() {
        Socket current = socket;
        socket = null;
        frameInput = null;
        frameOutput = null;
        chunkedOutput = null;
        closeQuietly(current);
    }

    private String findHeader(String expectedName) {
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey() != null && expectedName.equalsIgnoreCase(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static boolean isReservedHeader(String name) {
        return "host".equalsIgnoreCase(name)
                || "transfer-encoding".equalsIgnoreCase(name)
                || "content-length".equalsIgnoreCase(name)
                || "connection".equalsIgnoreCase(name)
                || "accept-encoding".equalsIgnoreCase(name)
                || "content-type".equalsIgnoreCase(name)
                || "proxy-authorization".equalsIgnoreCase(name)
                || "expect".equalsIgnoreCase(name);
    }

    private static void appendHeader(StringBuilder request, String name, String value) {
        validateHeader(name, value);
        request.append(name).append(": ").append(value).append("\r\n");
    }

    private static void validateHeader(String name, String value) {
        if (name.isEmpty() || name.indexOf('\r') >= 0 || name.indexOf('\n') >= 0
                || name.indexOf(':') >= 0 || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("Invalid HTTP header");
        }
    }

    private static String originForm(URI uri) {
        String path = uri.getRawPath();
        if (path == null || path.isEmpty()) path = "/";
        return uri.getRawQuery() == null ? path : path + "?" + uri.getRawQuery();
    }

    private static String formatAuthority(String host, int port) {
        String formattedHost = host.indexOf(':') >= 0 && !host.startsWith("[")
                ? "[" + host + "]" : host;
        return formattedHost + ":" + port;
    }

    private static TransportException classifyReadFailure(Throwable failure) {
        if (failure instanceof TransportException) return (TransportException) failure;
        TransportException.Reason reason = failure instanceof SocketTimeoutException
                ? TransportException.Reason.READ_TIMEOUT
                : TransportException.Reason.READ_FAILED;
        return new TransportException(reason, "HTTP duplex channel read failed", failure);
    }

    private static void closeQuietly(Closeable closeable) {
        if (closeable == null) return;
        try {
            closeable.close();
        } catch (IOException ignored) {
        }
    }

    private static SSLSocketFactory getTrustAllSslSocketFactory() throws Exception {
        SSLSocketFactory current = trustAllSslSocketFactory;
        if (current != null) return current;
        synchronized (Http11DuplexChannel.class) {
            if (trustAllSslSocketFactory == null) {
                TrustManager[] trustAll = new TrustManager[]{new X509TrustManager() {
                    @Override
                    public void checkClientTrusted(X509Certificate[] chain, String authType) { }

                    @Override
                    public void checkServerTrusted(X509Certificate[] chain, String authType) { }

                    @Override
                    public X509Certificate[] getAcceptedIssuers() {
                        return new X509Certificate[0];
                    }
                }};
                SSLContext context = SSLContext.getInstance("TLS");
                context.init(null, trustAll, new SecureRandom());
                trustAllSslSocketFactory = context.getSocketFactory();
            }
            return trustAllSslSocketFactory;
        }
    }
}
