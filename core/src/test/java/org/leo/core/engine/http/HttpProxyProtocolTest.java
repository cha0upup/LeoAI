package org.leo.core.engine.http;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.leo.core.puppet.capability.ComponentInvokeCapable;

import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class HttpProxyProtocolTest {
    private final StubNode node = new StubNode();
    private final HttpProxyServer server = new HttpProxyServer(node, 0);

    @AfterEach
    void stop() {
        server.stop();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "request-body"})
    void plainHttpPreservesHeaderBoundaryAndRequestBody(String body) throws Exception {
        server.start();
        try (Socket client = connect()) {
            String headers = (body.isEmpty() ? "GET" : "POST") + " http://example.test/path HTTP/1.1\r\n"
                    + "Host: example.test\r\nContent-Length: " + body.length() + "\r\n";
            String request = headers + "Proxy-Connection: keep-alive\r\nProxy-Authorization: secret\r\n\r\n" + body;
            client.getOutputStream().write(bytes(request));

            assertArrayEquals(bytes(headers + "\r\n"), node.uploads.poll(2, TimeUnit.SECONDS));
            if (!body.isEmpty()) assertArrayEquals(bytes(body), node.uploads.poll(2, TimeUnit.SECONDS));

            byte[] response = bytes("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nOK");
            node.downloads.add(response);
            assertArrayEquals(response, client.getInputStream().readNBytes(response.length));
            assertEquals(bytes(headers + "\r\n" + body).length, server.getStatistics().getSnapshot().uploadBytes);
        }
        assertTrue(node.closed.await(2, TimeUnit.SECONDS));
        assertEquals(1, node.closeCalls.get());
    }

    @Test
    void connectPreservesDataSentTogetherWithTheHandshake() throws Exception {
        server.start();
        try (Socket client = connect()) {
            byte[] greeting = bytes("HTTP/1.1 200 Connection Established\r\n\r\n");
            client.getOutputStream().write(bytes("CONNECT example.test:443 HTTP/1.1\r\n\r\nfirst-data"));
            assertArrayEquals(greeting, client.getInputStream().readNBytes(greeting.length));
            assertArrayEquals(bytes("first-data"), node.uploads.poll(2, TimeUnit.SECONDS));

            node.downloads.add(bytes("reply"));
            assertArrayEquals(bytes("reply"), client.getInputStream().readNBytes(5));
        }
        assertTrue(node.closed.await(2, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @ValueSource(strings = {"CONNECT example.test:443 HTTP/1.1\r\n\r\n", "GET / HTTP/1.1\r\nHost: example.test\r\n\r\n"})
    void failedOpenReturnsBadGatewayBeforeClosing(String request) throws Exception {
        node.failOpen = true;
        server.start();
        try (Socket client = connect()) {
            client.getOutputStream().write(bytes(request));
            assertEquals("HTTP/1.1 502 Bad Gateway\r\n\r\n", new String(client.getInputStream().readAllBytes(), StandardCharsets.US_ASCII));
        }
        assertTrue(node.closed.await(2, TimeUnit.SECONDS));
    }

    @Test
    void failedInitialHeaderWriteClosesTheRemoteConnection() throws Exception {
        node.failWrite = true;
        server.start();
        try (Socket client = connect()) {
            client.getOutputStream().write(bytes("GET / HTTP/1.1\r\nHost: example.test\r\n\r\n"));
            assertEquals("HTTP/1.1 502 Bad Gateway\r\n\r\n", new String(client.getInputStream().readAllBytes(), StandardCharsets.US_ASCII));
        }
        assertTrue(node.closed.await(2, TimeUnit.SECONDS));
        assertEquals(1, node.closeCalls.get());
        assertEquals(0, server.getStatistics().getSnapshot().uploadBytes);
    }

    private Socket connect() throws IOException {
        Socket client = new Socket("127.0.0.1", server.getListenPort());
        client.setSoTimeout(3000);
        return client;
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    private static final class StubNode implements ComponentInvokeCapable {
        final BlockingQueue<byte[]> uploads = new LinkedBlockingQueue<>();
        final BlockingQueue<byte[]> downloads = new LinkedBlockingQueue<>();
        final CountDownLatch closed = new CountDownLatch(1);
        final AtomicInteger closeCalls = new AtomicInteger();
        boolean failOpen;
        boolean failWrite;

        @Override
        public Map<String, Object> invokeComponent(String id, Map<String, Object> params) throws Exception {
            int operation = ((Number) params.get("op")).intValue();
            if (operation == 0 && failOpen) return Map.of("code", 404);
            if (operation == 1) {
                if (failWrite) throw new IOException("simulated write failure");
                byte[] data = (byte[]) params.get("data");
                uploads.add(data);
                return Map.of("code", 200, "bytesWritten", data.length);
            }
            if (operation == 2) {
                byte[] data = downloads.poll();
                return data == null ? Map.of("code", 204) : Map.of("code", 200, "data", data);
            }
            if (operation == 3) {
                closeCalls.incrementAndGet();
                closed.countDown();
            }
            return Map.of("code", 200);
        }
    }
}
