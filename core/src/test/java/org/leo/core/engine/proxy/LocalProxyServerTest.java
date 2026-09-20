package org.leo.core.engine.proxy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.leo.core.engine.forward.LocalForwardServer;
import org.leo.core.engine.http.HttpProxyServer;
import org.leo.core.engine.socks5.Socks5ProxyServer;
import org.leo.core.puppet.capability.ComponentInvokeCapable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

class LocalProxyServerTest {
    private LocalProxyServer server;
    private final StubNode node = new StubNode();

    enum Mode { SOCKS, HTTP, FORWARD }

    @AfterEach
    void stop() {
        node.openGate.countDown();
        if (server != null) server.stop();
    }

    @ParameterizedTest
    @EnumSource(Mode.class)
    void stopClosesEstablishedConnectionsAndRemoteState(Mode mode) throws Exception {
        start(mode);
        assertTrue(server.getServerSocket().getInetAddress().isAnyLocalAddress());
        try (Socket client = connect()) {
            handshake(client, mode);
            await(() -> server.getStatistics().getSnapshot().activeConnections == 1);
            client.getOutputStream().write(new byte[]{42});
            assertArrayEquals(new byte[]{42}, node.uploads.poll(2, TimeUnit.SECONDS));

            server.stop();

            assertEquals(-1, client.getInputStream().read());
            assertTrue(node.closed.await(2, TimeUnit.SECONDS));
            assertEquals(1, node.closeCalls.get());
            assertEquals(0, server.getStatistics().getSnapshot().activeConnections);
            assertFalse(server.isRunning());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"500", "404", "429", "null", "malformed", "exception"})
    void readFailuresCloseTheBlockedUploadDirection(String failure) throws Exception {
        start(Mode.FORWARD);
        try (Socket client = connect()) {
            await(() -> server.getStatistics().getSnapshot().activeConnections == 1);
            node.readFailure = failure;

            assertEquals(-1, client.getInputStream().read());
            assertTrue(node.closed.await(2, TimeUnit.SECONDS));
            assertEquals(1, node.closeCalls.get());
            assertEquals(0, server.getStatistics().getSnapshot().activeConnections);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"500", "404", "429", "null", "partial", "exception"})
    void writeFailuresCloseBothEndsWithoutCountingRejectedBytes(String failure) throws Exception {
        node.writeFailure = failure;
        start(Mode.FORWARD);
        try (Socket client = connect()) {
            client.getOutputStream().write(new byte[]{1, 2, 3});

            assertEquals(-1, client.getInputStream().read());
            assertTrue(node.closed.await(2, TimeUnit.SECONDS));
            assertEquals(1, node.closeCalls.get());
            assertEquals(0, server.getStatistics().getSnapshot().uploadBytes);
        }
    }

    @Test
    void stopDuringOpenClosesTheLateRemoteConnection() throws Exception {
        node.openGate = new CountDownLatch(1);
        start(Mode.FORWARD);
        try (Socket client = connect()) {
            assertTrue(node.opened.await(2, TimeUnit.SECONDS));
            server.stop();
            assertEquals(-1, client.getInputStream().read());

            node.openGate.countDown();

            assertTrue(node.closed.await(2, TimeUnit.SECONDS));
            assertEquals(1, node.closeCalls.get());
            assertEquals(0, server.getStatistics().getSnapshot().activeConnections);
            assertEquals(0, server.getStatistics().getSnapshot().totalConnections);
        }
    }

    @Test
    void capacityIncludesClientsThatHaveNotSentAHandshake() throws Exception {
        CountDownLatch accepted = new CountDownLatch(1);
        server = new LocalProxyServer(node, 0, 15000, 1) {
            @Override
            protected void handle(ProxyConnection connection) throws Exception {
                accepted.countDown();
                int value = connection.socket().getInputStream().read();
                if (value >= 0) connection.socket().getOutputStream().write(value);
            }
        };
        server.start();
        try (Socket first = connect()) {
            assertTrue(accepted.await(2, TimeUnit.SECONDS));
            assertEquals(0, server.getStatistics().getSnapshot().activeConnections);
            try (Socket excess = connect()) {
                assertEquals(-1, excess.getInputStream().read());
            }
        }
        // The reservation must become reusable after the unfinished handshake disconnects.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        do {
            try (Socket next = connect()) {
                next.getOutputStream().write(37);
                if (next.getInputStream().read() == 37) return;
            }
            Thread.sleep(5);
        } while (System.nanoTime() < deadline);
        fail("connection capacity was not released");
    }

    @ParameterizedTest
    @EnumSource(value = Mode.class, names = {"SOCKS", "HTTP"})
    void stopClosesPendingHandshakes(Mode mode) throws Exception {
        start(mode);
        try (Socket client = connect()) {
            if (mode == Mode.SOCKS) {
                client.getOutputStream().write(new byte[]{5, 1, 0});
                assertArrayEquals(new byte[]{5, 0}, client.getInputStream().readNBytes(2));
            } else {
                client.getOutputStream().write("CONNECT ".getBytes(StandardCharsets.US_ASCII));
            }
            server.stop();
            try {
                assertEquals(-1, client.getInputStream().read());
            } catch (java.net.SocketException reset) {
                // A client still in the accept backlog can receive a reset on listener shutdown.
            }
            assertEquals(1, node.opened.getCount());
        }
    }

    @Test
    void allLocalModesHonorExplicitBindAddresses() throws Exception {
        for (String address : new String[]{"127.0.0.1", "0.0.0.0"}) {
            for (LocalProxyServer candidate : new LocalProxyServer[]{
                    new Socks5ProxyServer(node, 0, address),
                    new HttpProxyServer(node, 0, address),
                    new LocalForwardServer(node, 0, address, "test.invalid", 80)}) {
                try {
                    candidate.start();
                    assertEquals(address, candidate.getBindAddr());
                    assertEquals("0.0.0.0".equals(address), candidate.getServerSocket().getInetAddress().isAnyLocalAddress());
                    try (Socket client = new Socket("127.0.0.1", candidate.getListenPort())) {
                        assertTrue(client.isConnected());
                    }
                } finally {
                    candidate.stop();
                }
            }
        }
    }

    @Test
    void socksRejectsAuthenticationMethodsItDoesNotSupport() throws Exception {
        start(Mode.SOCKS);
        try (Socket client = connect()) {
            client.getOutputStream().write(new byte[]{5, 1, 2});
            assertArrayEquals(new byte[]{5, (byte) 255}, client.getInputStream().readNBytes(2));
            assertEquals(-1, client.getInputStream().read());
            assertEquals(1, node.opened.getCount());
        }
    }

    private void start(Mode mode) throws Exception {
        server = switch (mode) {
            case SOCKS -> new Socks5ProxyServer(node, 0);
            case HTTP -> new HttpProxyServer(node, 0);
            case FORWARD -> new LocalForwardServer(node, 0, "test.invalid", 80);
        };
        server.start();
    }

    private Socket connect() throws IOException {
        Socket socket = new Socket("127.0.0.1", server.getListenPort());
        socket.setSoTimeout(3000);
        return socket;
    }

    private void handshake(Socket client, Mode mode) throws Exception {
        if (mode == Mode.SOCKS) {
            client.getOutputStream().write(new byte[]{5, 1, 0});
            assertArrayEquals(new byte[]{5, 0}, client.getInputStream().readNBytes(2));
            client.getOutputStream().write(new byte[]{5, 1, 0, 1, 127, 0, 0, 1, 0, 80});
            assertArrayEquals(new byte[]{5, 0, 0, 1, 0, 0, 0, 0, 0, 0}, client.getInputStream().readNBytes(10));
        } else if (mode == Mode.HTTP) {
            client.getOutputStream().write("CONNECT test.invalid:443 HTTP/1.1\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            ByteArrayOutputStream reply = new ByteArrayOutputStream();
            while (!reply.toString(StandardCharsets.US_ASCII).endsWith("\r\n\r\n")) {
                int value = client.getInputStream().read();
                assertTrue(value >= 0);
                reply.write(value);
            }
            assertTrue(reply.toString(StandardCharsets.US_ASCII).startsWith("HTTP/1.1 200"));
        }
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5);
        assertTrue(condition.getAsBoolean());
    }

    private static final class StubNode implements ComponentInvokeCapable {
        final CountDownLatch opened = new CountDownLatch(1);
        final CountDownLatch closed = new CountDownLatch(1);
        final AtomicInteger closeCalls = new AtomicInteger();
        final BlockingQueue<byte[]> uploads = new LinkedBlockingQueue<>();
        volatile CountDownLatch openGate = new CountDownLatch(0);
        volatile String readFailure;
        volatile String writeFailure;

        @Override
        public Map<String, Object> invokeComponent(String id, Map<String, Object> params) throws Exception {
            int operation = ((Number) params.get("op")).intValue();
            if (operation == 0) {
                opened.countDown();
                if (!openGate.await(3, TimeUnit.SECONDS)) throw new IOException("test OPEN timed out");
            } else if (operation == 1) {
                if (writeFailure != null) return failure(writeFailure);
                uploads.add((byte[]) params.get("data"));
            } else if (operation == 2) {
                return readFailure == null ? Map.of("code", 204) : failure(readFailure);
            } else if (operation == 3) {
                closeCalls.incrementAndGet();
                closed.countDown();
            }
            return Map.of("code", 200);
        }

        private Map<String, Object> failure(String failure) throws IOException {
            return switch (failure) {
                case "exception" -> throw new IOException("simulated RPC failure");
                case "null" -> null;
                case "malformed" -> Map.of("code", 200, "data", "not bytes");
                case "partial" -> Map.of("code", 200, "bytesWritten", 1);
                default -> Map.of("code", Integer.parseInt(failure));
            };
        }
    }
}
