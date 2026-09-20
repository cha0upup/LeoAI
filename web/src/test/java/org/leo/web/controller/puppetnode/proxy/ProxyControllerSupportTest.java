package org.leo.web.controller.puppetnode.proxy;

import org.junit.jupiter.api.Test;
import org.leo.core.engine.socks5.Socks5ProxyStatistics;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class ProxyControllerSupportTest {

    @Test
    void acceptsIntegerAndNumericStringPorts() {
        assertEquals(1080, ProxyControllerSupport.requirePort(Map.of("port", 1080), "port"));
        assertEquals(8080, ProxyControllerSupport.requirePort(Map.of("port", " 8080 "), "port"));
    }

    @Test
    void invalidPortsProduceBadRequestResponses() {
        HashMap<String, Object> response = ProxyControllerSupport.call(
                "启动失败", Map.of(),
                () -> ProxyControllerSupport.requirePort(Map.of("port", 65_536), "port"));

        assertEquals(400, response.get("code"));
        assertEquals("port必须在1到65535之间", response.get("msg"));
    }

    @Test
    void preservesBusinessFailuresAtTheTopLevel() {
        HashMap<String, Object> response = ProxyControllerSupport.call("启动失败", Map.of(),
                () -> Map.of("code", 409, "msg", "forward already exists", "localPort", 1080));

        assertEquals(409, response.get("code"));
        assertEquals("forward already exists", response.get("msg"));
    }

    @Test
    void keepsSuccessfulResultPayloadsCompatible() {
        Map<String, Object> payload = Map.of("code", 200, "msg", "started", "port", 1080);
        HashMap<String, Object> response = ProxyControllerSupport.call("启动失败", Map.of(), () -> payload);

        assertEquals(200, response.get("code"));
        assertEquals(payload, response.get("data"));
    }

    @Test
    void mapsStatisticsSnapshotsInOneStableShape() {
        Socks5ProxyStatistics statistics = new Socks5ProxyStatistics(1080);
        statistics.addConnection("connection-1", "target.internal", 443, "127.0.0.1");
        statistics.addUploadBytes("connection-1", 9);

        HashMap<String, Object> response = ProxyControllerSupport.statistics(
                "读取失败", "未启动", statistics::getSnapshot);

        assertEquals(200, response.get("code"));
        Map<?, ?> data = assertInstanceOf(Map.class, response.get("data"));
        assertEquals(1080, data.get("port"));
        List<?> connections = assertInstanceOf(List.class, data.get("connections"));
        Map<?, ?> connection = assertInstanceOf(Map.class, connections.get(0));
        assertEquals("connection-1", connection.get("connId"));
        assertEquals(9L, connection.get("uploadBytes"));
    }
}
