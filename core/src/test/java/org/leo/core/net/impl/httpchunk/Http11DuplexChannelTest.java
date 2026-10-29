package org.leo.core.net.impl.httpchunk;

import org.junit.jupiter.api.Test;
import org.leo.core.net.impl.HttpChunkedCommunication;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Http11DuplexChannelTest {

    @Test
    void reusesOneHttpConnectionForHeartbeatAndConcurrentRequests() throws Exception {
        try (EchoDuplexServer server = new EchoDuplexServer(false)) {
            HttpChunkedCommunication communication = new HttpChunkedCommunication(
                    server.url(), null, null, null);
            ExecutorService executor = Executors.newFixedThreadPool(4);
            try {
                assertArrayEquals(new byte[]{1, 2, 3},
                        communication.sendRequest(new byte[]{1, 2, 3}));
                communication.heartbeat();

                List<Callable<byte[]>> tasks = new ArrayList<Callable<byte[]>>();
                for (int i = 0; i < 8; i++) {
                    final byte value = (byte) i;
                    tasks.add(new Callable<byte[]>() {
                        @Override
                        public byte[] call() throws Exception {
                            return communication.sendRequest(new byte[]{value});
                        }
                    });
                }
                List<Future<byte[]>> responses = executor.invokeAll(tasks);
                for (int i = 0; i < responses.size(); i++) {
                    assertArrayEquals(new byte[]{(byte) i}, responses.get(i).get());
                }
                assertEquals(1, server.connectionCount.get());
                assertTrue(server.lastRequestHead.get().startsWith("POST /channel HTTP/1.1\r\n"));
                assertNull(server.failure.get());
            } finally {
                executor.shutdownNow();
                communication.close();
            }
        }
    }

    @Test
    void usesAbsoluteRequestTargetThroughHttpProxy() throws Exception {
        try (EchoDuplexServer proxyServer = new EchoDuplexServer(false)) {
            Map<String, String> headers = new HashMap<String, String>();
            headers.put("Proxy-Authorization", "Basic dGVzdA==");
            Proxy proxy = new Proxy(Proxy.Type.HTTP,
                    new InetSocketAddress("127.0.0.1", proxyServer.serverSocket.getLocalPort()));
            HttpChunkedCommunication communication = new HttpChunkedCommunication(
                    "http://example.invalid:8080/channel?q=1", "post", headers, proxy);
            try {
                assertArrayEquals(new byte[]{9}, communication.sendRequest(new byte[]{9}));
                String requestHead = proxyServer.lastRequestHead.get();
                assertTrue(requestHead.startsWith(
                        "POST http://example.invalid:8080/channel?q=1 HTTP/1.1\r\n"));
                assertTrue(requestHead.contains("Proxy-Authorization: Basic dGVzdA==\r\n"));
                assertEquals(1, headers.size(), "transport defaults must not mutate caller headers");
            } finally {
                communication.close();
            }
        }
    }

    @Test
    void reconnectsAfterPeerClosesTheFirstConnection() throws Exception {
        try (EchoDuplexServer server = new EchoDuplexServer(true)) {
            HttpChunkedCommunication communication = new HttpChunkedCommunication(
                    server.url(), "POST", null, Proxy.NO_PROXY);
            try {
                assertArrayEquals(new byte[]{4, 5}, communication.sendRequest(new byte[]{4, 5}));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while ((server.connectionCount.get() < 2
                        || communication.getState() != Http11DuplexChannel.State.OPEN)
                        && System.nanoTime() < deadline) {
                    Thread.sleep(10L);
                }
                assertTrue(server.connectionCount.get() >= 2);
                assertArrayEquals(new byte[]{6, 7}, communication.sendRequest(new byte[]{6, 7}));
                assertNull(server.failure.get());
            } finally {
                communication.close();
            }
        }
    }

    private static final class EchoDuplexServer implements AutoCloseable {
        private final ServerSocket serverSocket;
        private final Thread acceptThread;
        private final boolean closeFirstConnectionAfterData;
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicInteger connectionCount = new AtomicInteger();
        private final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        private final AtomicReference<String> lastRequestHead = new AtomicReference<String>();
        private final List<Socket> sockets = new ArrayList<Socket>();

        private EchoDuplexServer(boolean closeFirstConnectionAfterData) throws IOException {
            this.closeFirstConnectionAfterData = closeFirstConnectionAfterData;
            this.serverSocket = new ServerSocket();
            serverSocket.bind(new InetSocketAddress("127.0.0.1", 0));
            this.acceptThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    acceptLoop();
                }
            }, "Http11DuplexChannelTest-Server");
            acceptThread.setDaemon(true);
            acceptThread.start();
        }

        private String url() {
            return "http://127.0.0.1:" + serverSocket.getLocalPort() + "/channel";
        }

        private void acceptLoop() {
            while (!closed.get()) {
                try {
                    final Socket socket = serverSocket.accept();
                    synchronized (sockets) {
                        sockets.add(socket);
                    }
                    final int connectionNumber = connectionCount.incrementAndGet();
                    Thread worker = new Thread(new Runnable() {
                        @Override
                        public void run() {
                            serve(socket, connectionNumber);
                        }
                    }, "Http11DuplexChannelTest-Connection");
                    worker.setDaemon(true);
                    worker.start();
                } catch (IOException e) {
                    if (!closed.get()) failure.compareAndSet(null, e);
                }
            }
        }

        private void serve(Socket socket, int connectionNumber) {
            try {
                socket.setSoTimeout(10000);
                BufferedInputStream rawInput = new BufferedInputStream(socket.getInputStream());
                BufferedOutputStream rawOutput = new BufferedOutputStream(socket.getOutputStream());
                String requestHead = readHead(rawInput);
                lastRequestHead.set(requestHead);
                assertTrue(requestHead.startsWith("POST "));
                assertTrue(requestHead.toLowerCase().contains("transfer-encoding: chunked"));
                rawOutput.write(("HTTP/1.1 200 OK\r\n"
                        + "Content-Type: application/octet-stream\r\n"
                        + "Transfer-Encoding: chunked\r\n"
                        + "Connection: keep-alive\r\n\r\n")
                        .getBytes(StandardCharsets.ISO_8859_1));
                rawOutput.flush();

                DataInputStream input = new DataInputStream(new BufferedInputStream(
                        new HttpChunkedBodyInputStream(rawInput), 64 * 1024));
                HttpChunkedBodyOutputStream chunked = new HttpChunkedBodyOutputStream(rawOutput);
                DataOutputStream output = new DataOutputStream(new BufferedOutputStream(
                        chunked, 64 * 1024));
                while (!closed.get()) {
                    DuplexFrameCodec.Frame frame = DuplexFrameCodec.read(input);
                    if (frame.type == DuplexFrameCodec.TYPE_DATA) {
                        DuplexFrameCodec.write(output, DuplexFrameCodec.TYPE_DATA,
                                frame.transportId, frame.payload);
                        if (closeFirstConnectionAfterData && connectionNumber == 1) {
                            socket.close();
                            return;
                        }
                    } else if (frame.type == DuplexFrameCodec.TYPE_PING) {
                        DuplexFrameCodec.write(output, DuplexFrameCodec.TYPE_PONG,
                                frame.transportId, new byte[0]);
                    } else if (frame.type == DuplexFrameCodec.TYPE_CLOSE) {
                        chunked.finish();
                        return;
                    }
                }
            } catch (EOFException ignored) {
            } catch (Throwable error) {
                if (!closed.get() && !(error instanceof java.net.SocketException)) {
                    failure.compareAndSet(null, error);
                }
            }
        }

        private static String readHead(BufferedInputStream input) throws IOException {
            byte[] end = new byte[]{'\r', '\n', '\r', '\n'};
            byte[] collected = new byte[64 * 1024];
            int length = 0;
            int matched = 0;
            while (length < collected.length) {
                int value = input.read();
                if (value < 0) throw new EOFException();
                collected[length++] = (byte) value;
                if (value == end[matched]) {
                    matched++;
                    if (matched == end.length) {
                        return new String(collected, 0, length, StandardCharsets.ISO_8859_1);
                    }
                } else {
                    matched = value == end[0] ? 1 : 0;
                }
            }
            throw new IOException("request head too large");
        }

        @Override
        public void close() throws Exception {
            closed.set(true);
            serverSocket.close();
            synchronized (sockets) {
                for (Socket socket : sockets) {
                    try {
                        socket.close();
                    } catch (IOException ignored) {
                    }
                }
            }
            acceptThread.join(1000L);
            assertNull(failure.get(), failure.get() == null ? "" : failure.get().toString());
        }
    }
}
