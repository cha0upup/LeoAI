package org.leo.core.engine.proxy;

import org.junit.jupiter.api.Test;
import org.leo.core.agent.capability.ComponentInvokeCapable;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class AgentRelaySocketTest {
    @Test void failedOpenReleasesItsReservationAndSendsRemoteClose() throws Exception {
        AtomicInteger closes = new AtomicInteger();
        ComponentInvokeCapable node = (component, params) -> {
            if (params.get("op").equals(3)) closes.incrementAndGet();
            return Map.of("code", params.get("op").equals(0) ? 500 : 200);
        };
        try (var resources = new ProxySessionResources(1, 1)) {
            assertThrows(IOException.class, () -> AgentRelaySocket.open(node, "agent-only.invalid", 22, resources, Runnable::run));
            assertEquals(1, closes.get());
            var slot = resources.tryAcquireConnection(); assertNotNull(slot); slot.close();
        }
    }
    @Test void readTimeoutAndCloseWakeReadersWithoutOpeningLocalListeners() throws Exception {
        AtomicInteger closes = new AtomicInteger();
        ComponentInvokeCapable node = (component, params) -> {
            if (params.get("op").equals(3)) closes.incrementAndGet();
            if (!params.get("op").equals(5)) return Map.of("code", 200);
            @SuppressWarnings("unchecked") var frames = (List<Map<String, Object>>) params.get("frames");
            return Map.of("code", 200, "frames", frames.stream().map(f -> Map.of(
                    "connId", f.get("connId"), "code", 200, "readCode", 204, "readOffset", f.get("readAck"),
                    "writeAck", ((Number) f.get("writeOffset")).longValue() + ((byte[]) f.get("data")).length,
                    "data", new byte[0])).toList());
        };
        try (var resources = new ProxySessionResources(1, 1)) {
            var socket = AgentRelaySocket.open(node, "agent-only.invalid", 22, resources, Runnable::run);
            assertTrue(socket.isConnected()); assertFalse(socket.isBound()); assertEquals(-1, socket.getLocalPort());
            assertSame(socket.getInputStream(), socket.getInputStream());
            assertSame(socket.getOutputStream(), socket.getOutputStream());
            var direct = java.net.InetSocketAddress.createUnresolved("direct.invalid", 22);
            assertThrows(IOException.class, () -> socket.connect(direct));
            assertThrows(IOException.class, () -> socket.connect(direct, 10));
            assertThrows(IOException.class, () -> socket.bind(null));
            socket.setSoTimeout(20);
            assertThrows(SocketTimeoutException.class, () -> socket.getInputStream().read());
            socket.setSoTimeout(0);
            var waiting = CompletableFuture.supplyAsync(() -> {
                try { return socket.getInputStream().read(); } catch (IOException e) { throw new RuntimeException(e); }
            });
            socket.close(); socket.close();
            assertEquals(-1, waiting.get(2, TimeUnit.SECONDS)); assertTrue(socket.isClosed());
            assertThrows(IOException.class, () -> socket.connect(direct));
            for (int i = 0; i < 100 && closes.get() == 0; i++) Thread.sleep(10);
            assertEquals(1, closes.get());
        }
    }
}
