package org.leo.core.infrastructure.transport;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.junit.jupiter.api.Test;
import org.leo.core.net.TransportLimits;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebSocketCommunicationTest {

    @Test
    void fragmentedRequestAndResponseRoundTrip() throws Exception {
        EchoServer server = new EchoServer();
        server.start();
        assertTrue(server.started.await(5, TimeUnit.SECONDS));

        WebSocketCommunication client = new WebSocketCommunication(
                "ws://127.0.0.1:" + server.getPort(), Proxy.NO_PROXY, 5000L);
        client.connect();
        try {
            byte[] request = new byte[TransportLimits.MAX_FRAGMENT_PAYLOAD_BYTES * 2 + 123];
            for (int i = 0; i < request.length; i++) {
                request[i] = (byte) (i * 17);
            }
            assertTrue(Arrays.equals(request, client.sendRequest(request)));
            server.connection.get().close(1001, "reconnect test");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (!client.isClosed() && System.nanoTime() < deadline) {
                Thread.sleep(10L);
            }
            assertTrue(client.isClosed());
            assertTrue(Arrays.equals(new byte[]{7, 8, 9},
                    client.sendRequest(new byte[]{7, 8, 9})));
            assertNull(server.failure.get());
        } finally {
            client.closeBlocking();
            server.stop(1000);
        }
    }

    private static final class EchoServer extends WebSocketServer {
        private final CountDownLatch started = new CountDownLatch(1);
        private final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        private final AtomicReference<WebSocket> connection = new AtomicReference<WebSocket>();
        private final ConcurrentHashMap<Long, WebSocketFrameCodec.Accumulator> messages =
                new ConcurrentHashMap<Long, WebSocketFrameCodec.Accumulator>();

        private EchoServer() {
            super(new InetSocketAddress("127.0.0.1", 0));
        }

        @Override
        public void onOpen(WebSocket connection, ClientHandshake handshake) {
            this.connection.set(connection);
        }

        @Override
        public void onClose(WebSocket connection, int code, String reason, boolean remote) { }

        @Override
        public void onMessage(WebSocket connection, String message) { }

        @Override
        public void onMessage(WebSocket connection, ByteBuffer message) {
            try {
                WebSocketFrameCodec.Frame frame = WebSocketFrameCodec.decode(message);
                WebSocketFrameCodec.Accumulator accumulator = messages.get(frame.messageId);
                if (accumulator == null) {
                    WebSocketFrameCodec.Accumulator created =
                            new WebSocketFrameCodec.Accumulator(frame);
                    WebSocketFrameCodec.Accumulator previous =
                            messages.putIfAbsent(frame.messageId, created);
                    accumulator = previous == null ? created : previous;
                }
                byte[] completed = accumulator.accept(frame);
                if (completed != null) {
                    messages.remove(frame.messageId);
                    int count = WebSocketFrameCodec.fragmentCount(completed.length);
                    for (int i = 0; i < count; i++) {
                        connection.send(WebSocketFrameCodec.encode(frame.messageId, completed, i));
                    }
                }
            } catch (Throwable error) {
                failure.compareAndSet(null, error);
                connection.close(1011, "test server frame error");
            }
        }

        @Override
        public void onError(WebSocket connection, Exception error) {
            failure.compareAndSet(null, error);
        }

        @Override
        public void onStart() {
            started.countDown();
        }
    }
}
