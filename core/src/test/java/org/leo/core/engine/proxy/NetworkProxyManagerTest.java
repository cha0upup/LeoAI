package org.leo.core.engine.proxy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.leo.core.puppet.capability.ComponentInvokeCapable;

import java.net.ServerSocket;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkProxyManagerTest {

    private final NetworkProxyManager manager = new NetworkProxyManager(new StubComponentNode());

    @AfterEach
    void tearDown() {
        manager.close();
    }

    @Test
    void managesAllFourProxyModesThroughTheSharedComponentContract() throws Exception {
        int socksPort = freePort();
        int httpPort = freePort();
        int forwardPort = freePort();
        int reversePort = freePort();

        assertEquals(200, code(manager.startSocks5Proxy(socksPort)));
        assertEquals(Boolean.TRUE, manager.getSocks5ProxyStatus().get("enabled"));
        assertEquals(socksPort, manager.getSocks5ProxyStatistics().port);

        assertEquals(200, code(manager.startHttpProxy(httpPort)));
        assertEquals(Boolean.TRUE, manager.getHttpProxyStatus().get("running"));
        assertEquals(httpPort, manager.getHttpProxyStatistics().port);

        assertEquals(200, code(manager.startLocalForward(forwardPort, "target.internal", 8080)));
        List<Map<String, Object>> forwards = manager.listLocalForwards();
        assertEquals(1, forwards.size());
        assertEquals("target.internal", forwards.get(0).get("targetHost"));
        assertNotNull(manager.getLocalForwardStatistics(forwardPort));

        Map<String, Object> reverse = manager.startReverseTunnel(
                reversePort, "127.0.0.1", "127.0.0.1", 9000);
        assertEquals(200, code(reverse));
        String listenId = String.valueOf(reverse.get("listenId"));
        assertFalse(listenId.isBlank());
        assertEquals(1, manager.listReverseTunnels().size());
        assertNotNull(manager.getReverseTunnelStatistics(listenId));

        assertEquals(200, code(manager.stopSocks5Proxy()));
        assertEquals(200, code(manager.stopHttpProxy()));
        assertEquals(200, code(manager.stopLocalForward(forwardPort)));
        assertEquals(200, code(manager.stopReverseTunnel(listenId)));
        assertEquals(Boolean.FALSE, manager.getSocks5ProxyStatus().get("enabled"));
        assertEquals(Boolean.FALSE, manager.getHttpProxyStatus().get("running"));
        assertTrue(manager.listLocalForwards().isEmpty());
        assertTrue(manager.listReverseTunnels().isEmpty());
    }

    @Test
    void reportsBoundAddressesAndRequiresStoppingBeforeChangingListeners() throws Exception {
        int socksPort = freePort();
        Map<String, Object> socks = manager.startSocks5Proxy(socksPort, " 0.0.0.0 ");
        assertEquals("0.0.0.0", socks.get("bindAddr"));
        assertEquals("0.0.0.0", manager.getSocks5ProxyStatus().get("bindAddr"));
        assertEquals(200, code(manager.startSocks5Proxy(socksPort, "0.0.0.0")));
        assertEquals(409, code(manager.startSocks5Proxy(socksPort, "127.0.0.1")));
        assertEquals("0.0.0.0", manager.getSocks5ProxyStatus().get("bindAddr"));
        manager.stopSocks5Proxy();
        assertEquals("0.0.0.0", manager.startSocks5Proxy(socksPort).get("bindAddr"));

        int httpPort = freePort();
        assertEquals("0.0.0.0", manager.startHttpProxy(httpPort, "0.0.0.0").get("bindAddr"));
        assertEquals("0.0.0.0", manager.getHttpProxyStatus().get("bindAddr"));
        assertEquals(409, code(manager.startHttpProxy(httpPort, "127.0.0.1")));

        int forwardPort = freePort();
        Map<String, Object> forward = manager.startLocalForward(forwardPort, "0.0.0.0", "target.internal", 80);
        assertEquals("0.0.0.0", forward.get("bindAddr"));
        assertEquals("0.0.0.0", manager.listLocalForwards().get(0).get("bindAddr"));
        assertEquals(409, code(manager.startLocalForward(forwardPort, "127.0.0.1", "target.internal", 80)));
    }

    @Test
    void validatesPortsAndTargetsBeforeOpeningListeners() {
        assertThrows(IllegalArgumentException.class, () -> manager.startSocks5Proxy(0));
        assertThrows(IllegalArgumentException.class, () -> manager.startHttpProxy(65536));
        assertThrows(IllegalArgumentException.class, () -> manager.startLocalForward(12345, " ", 80));
        assertThrows(IllegalArgumentException.class,
                () -> manager.startReverseTunnel(12345, null, "host", -1));
    }

    private int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private int code(Map<String, Object> response) {
        return ((Number) response.get("code")).intValue();
    }

    private static final class StubComponentNode implements ComponentInvokeCapable {
        @Override
        public Map<String, Object> invokeComponent(String componentId, Map<String, Object> params) {
            if ("ReverseTunnelComponent".equals(componentId)
                    && ((Number) params.get("op")).intValue() == 2) {
                return Map.of("code", 200, "newConns", List.of());
            }
            return Map.of("code", 200);
        }
    }
}
