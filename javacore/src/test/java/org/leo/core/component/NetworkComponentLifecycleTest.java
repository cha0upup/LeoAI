package org.leo.core.component;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import static org.leo.core.component.ComponentTestSupport.invokeComponent;
import static org.leo.core.component.ComponentTestSupport.params;

class NetworkComponentLifecycleTest {

    @AfterEach
    void clearComponentState() throws Exception {
        clearMap(ProxyForwardComponent.class, "connMap", true);
        clearMap(ReverseTunnelComponent.class, "listenMap", true);
        clearMap(ReverseTunnelComponent.class, "listenInfoMap", false);
        clearMap(ReverseTunnelComponent.class, "connMap", true);
    }

    @Test
    void rejectsMalformedProtocolParameters() throws Exception {
        Map<String, Object> proxy = invokeComponent(new ProxyForwardComponent(), params("op", "open"));
        assertEquals(400, ((Number) proxy.get("code")).intValue());

        Map<String, Object> reverse = invokeComponent(new ReverseTunnelComponent(), params("op", "start"));
        assertEquals(400, ((Number) reverse.get("code")).intValue());

        Map<String, Object> reverseWrite = invokeComponent(new ReverseTunnelComponent(),
                params("op", 4, "connId", "missing", "data", "text"));
        assertEquals(400, ((Number) reverseWrite.get("code")).intValue());
    }

    @Test
    void proxyRoundTripClosesAllConnectionState() throws Exception {
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        ServerSocket server = new ServerSocket(0, 1, loopback);
        AtomicReference<Throwable> peerFailure = new AtomicReference<>();
        Thread peer = new Thread(() -> {
            try (Socket socket = server.accept()) {
                assertArrayEquals("ping".getBytes(StandardCharsets.UTF_8), readExactly(socket.getInputStream(), 4));
                socket.getOutputStream().write("pong".getBytes(StandardCharsets.UTF_8));
                socket.getOutputStream().flush();
            } catch (Throwable error) {
                peerFailure.set(error);
            }
        });
        peer.start();

        String connId = "proxy-test";
        try {
            Map<String, Object> opened = invokeComponent(new ProxyForwardComponent(), params(
                    "op", 0, "connId", connId, "targetHost", "127.0.0.1",
                    "targetPort", server.getLocalPort(), "connectTimeout", 2000));
            assertEquals(200, ((Number) opened.get("code")).intValue());
            Map<String, Object> duplicate = invokeComponent(new ProxyForwardComponent(), params(
                    "op", 0, "connId", connId, "targetHost", "127.0.0.1", "targetPort", 1));
            assertEquals(409, ((Number) duplicate.get("code")).intValue());

            Map<String, Object> written = exchange(new ProxyForwardComponent(), 5, connId,
                    "ping".getBytes(StandardCharsets.UTF_8), 0, 0);
            assertEquals(4, ((Number) written.get("writeAck")).intValue());

            Map<String, Object> read = pollRead(new ProxyForwardComponent(), connId, 5, 4, written);
            assertEquals(200, ((Number) read.get("code")).intValue());
            assertArrayEquals("pong".getBytes(StandardCharsets.UTF_8), (byte[]) read.get("data"));

            Map<String, Object> closed = invokeComponent(new ProxyForwardComponent(),
                    params("op", 3, "connId", connId));
            assertEquals(200, ((Number) closed.get("code")).intValue());
            assertFalse(staticMap(ProxyForwardComponent.class, "connMap").containsKey(connId));
        } finally {
            server.close();
            peer.join(2000L);
        }
        if (peerFailure.get() != null) throw new AssertionError(peerFailure.get());
    }

    @Test
    void reverseTunnelRoundTripAndStopCleanAllState() throws Exception {
        String listenId = "reverse-test";
        Map<String, Object> started = invokeComponent(new ReverseTunnelComponent(), params(
                "op", 0, "listenId", listenId, "listenPort", 0, "bindAddr", "127.0.0.1"));
        assertEquals(200, ((Number) started.get("code")).intValue());
        Map<String, Object> duplicate = invokeComponent(new ReverseTunnelComponent(), params(
                "op", 0, "listenId", listenId, "listenPort", 0, "bindAddr", "127.0.0.1"));
        assertEquals(409, ((Number) duplicate.get("code")).intValue());

        Socket client = new Socket("127.0.0.1", ((Number) started.get("listenPort")).intValue());
        client.setSoTimeout(2000);
        try {
            String connId = pollAcceptedConnection(listenId);
            client.getOutputStream().write("from-client".getBytes(StandardCharsets.UTF_8));
            client.getOutputStream().flush();

            Map<String, Object> read = pollRead(new ReverseTunnelComponent(), connId, 8, 0,
                    exchange(new ReverseTunnelComponent(), 8, connId, new byte[0], 0, 0));
            assertArrayEquals("from-client".getBytes(StandardCharsets.UTF_8), (byte[]) read.get("data"));

            Map<String, Object> written = exchange(new ReverseTunnelComponent(), 8, connId,
                    "to-client".getBytes(StandardCharsets.UTF_8), 0, 11);
            assertEquals(9, ((Number) written.get("writeAck")).intValue());
            assertArrayEquals("to-client".getBytes(StandardCharsets.UTF_8),
                    readExactly(client.getInputStream(), 9));

            Map<String, Object> stopped = invokeComponent(new ReverseTunnelComponent(),
                    params("op", 1, "listenId", listenId));
            assertEquals(200, ((Number) stopped.get("code")).intValue());
            assertEquals(1, ((Number) stopped.get("closedConns")).intValue());
            assertTrue(staticMap(ReverseTunnelComponent.class, "listenMap").isEmpty());
            assertTrue(staticMap(ReverseTunnelComponent.class, "connMap").isEmpty());
        } finally {
            client.close();
        }
    }

    private String pollAcceptedConnection(String listenId) throws Exception {
        long deadline = System.currentTimeMillis() + 2000L;
        do {
            Map<String, Object> accepted = invokeComponent(new ReverseTunnelComponent(),
                    params("op", 2, "listenId", listenId));
            ArrayList<?> connections = (ArrayList<?>) accepted.get("newConns");
            if (connections != null && !connections.isEmpty()) {
                return String.valueOf(((Map<?, ?>) connections.get(0)).get("connId"));
            }
            Thread.sleep(10L);
        } while (System.currentTimeMillis() < deadline);
        throw new AssertionError("reverse tunnel did not accept the client");
    }

    private Map<String, Object> pollRead(Object component, String connId, int operation,
                                         long writeOffset, Map<String, Object> response) throws Exception {
        long deadline = System.currentTimeMillis() + 2000L;
        while (((byte[]) response.get("data")).length == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10L);
            response = exchange(component, operation, connId, new byte[0], writeOffset, 0);
        }
        return response;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> exchange(Object component, int operation, String connId,
                                          byte[] data, long writeOffset, long readAck) throws Exception {
        Map<String, Object> response = invokeComponent(component, params("op", operation, "frames", List.of(params(
                "connId", connId, "data", data, "writeOffset", writeOffset, "readAck", readAck, "maxBytes", 65536))));
        assertEquals(200, ((Number) response.get("code")).intValue());
        return (Map<String, Object>) ((List<?>) response.get("frames")).get(0);
    }

    private byte[] readExactly(InputStream input, int expected) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream(expected);
        while (output.size() < expected) {
            int value = input.read();
            if (value < 0) break;
            output.write(value);
        }
        return output.toByteArray();
    }

    private void clearMap(Class<?> type, String fieldName, boolean closeValues) throws Exception {
        Map<?, ?> map = staticMap(type, fieldName);
        if (closeValues) {
            for (Object value : map.values()) {
                if (value instanceof Object[] state) value = state[0];
                if (value instanceof Closeable resource) resource.close();
            }
        }
        map.clear();
    }

    private Map<?, ?> staticMap(Class<?> type, String fieldName) throws Exception {
        Field field = type.getDeclaredField(fieldName);
        field.setAccessible(true);
        return (Map<?, ?>) field.get(null);
    }
}
