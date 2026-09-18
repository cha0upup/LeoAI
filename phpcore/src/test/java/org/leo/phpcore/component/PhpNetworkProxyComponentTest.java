package org.leo.phpcore.component;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.leo.core.util.json.PortableJsonCodec;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import static org.leo.phpcore.PhpTestSupport.code;
import static org.leo.phpcore.PhpTestSupport.phpAvailable;
import static org.leo.phpcore.PhpTestSupport.runJson;

class PhpNetworkProxyComponentTest {

    private Path forwardComponent;
    private Path reverseComponent;
    private String forwardConnId;
    private String reverseListenId;
    private String reverseConnId;

    @BeforeEach
    void setUp() throws Exception {
        Assumptions.assumeTrue(phpAvailable(), "PHP CLI is not installed");
        forwardComponent = component("ProxyForwardComponent.php");
        reverseComponent = component("ReverseTunnelComponent.php");
        forwardConnId = "forward-" + UUID.randomUUID().toString().replace("-", "");
        reverseListenId = "reverse-" + UUID.randomUUID().toString().replace("-", "");
    }

    @AfterEach
    void tearDown() throws Exception {
        if (forwardConnId != null) invoke(forwardComponent,
                Map.of("op", 3, "connId", forwardConnId));
        if (reverseConnId != null) invoke(reverseComponent,
                Map.of("op", 5, "connId", reverseConnId));
        if (reverseListenId != null) invoke(reverseComponent,
                Map.of("op", 1, "listenId", reverseListenId));
    }

    @Test
    void forwardsBidirectionalTcpDataAcrossIndependentPhpRequests() throws Exception {
        try (ServerSocket target = new ServerSocket(0, 8, InetAddress.getLoopbackAddress())) {
            CompletableFuture<Void> echo = CompletableFuture.runAsync(() -> {
                try (Socket socket = target.accept()) {
                    byte[] request = socket.getInputStream().readNBytes(12);
                    socket.getOutputStream().write(("echo:" + new String(request, StandardCharsets.UTF_8))
                            .getBytes(StandardCharsets.UTF_8));
                    socket.getOutputStream().flush();
                } catch (IOException error) {
                    throw new RuntimeException(error);
                }
            });

            Map<String, Object> opened = invoke(forwardComponent, Map.of(
                    "op", 0, "connId", forwardConnId, "targetHost", "127.0.0.1",
                    "targetPort", target.getLocalPort(), "connectTimeout", 3000));
            assertEquals(200, code(opened), String.valueOf(opened));

            byte[] payload = "proxy-worker".getBytes(StandardCharsets.UTF_8);
            Map<String, Object> written = invoke(forwardComponent, Map.of(
                    "op", 1, "connId", forwardConnId, "data", payload));
            assertEquals(payload.length, ((Number) written.get("bytesWritten")).intValue());

            byte[] response = readUntil(forwardComponent,
                    Map.of("op", 2, "connId", forwardConnId), "echo:proxy-worker".length());
            assertArrayEquals("echo:proxy-worker".getBytes(StandardCharsets.UTF_8), response);
            echo.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void acceptsAndRelaysReverseTunnelConnections() throws Exception {
        Map<String, Object> started = invoke(reverseComponent, Map.of(
                "op", 0, "listenId", reverseListenId, "listenPort", 0, "bindAddr", "127.0.0.1"));
        assertEquals(200, code(started), String.valueOf(started));
        int port = ((Number) started.get("listenPort")).intValue();
        assertTrue(port > 0);

        try (Socket client = new Socket("127.0.0.1", port)) {
            Map<String, Object> accepted = pollAccepted();
            List<?> connections = (List<?>) accepted.get("newConns");
            assertFalse(connections.isEmpty());
            reverseConnId = String.valueOf(((Map<?, ?>) connections.get(0)).get("connId"));

            client.getOutputStream().write("from-client".getBytes(StandardCharsets.UTF_8));
            client.getOutputStream().flush();
            assertArrayEquals("from-client".getBytes(StandardCharsets.UTF_8), readUntil(reverseComponent,
                    Map.of("op", 3, "connId", reverseConnId), "from-client".length()));

            Map<String, Object> written = invoke(reverseComponent, Map.of(
                    "op", 4, "connId", reverseConnId,
                    "data", "from-platform".getBytes(StandardCharsets.UTF_8)));
            assertEquals(200, code(written), String.valueOf(written));
            assertArrayEquals("from-platform".getBytes(StandardCharsets.UTF_8),
                    client.getInputStream().readNBytes("from-platform".length()));
        }
    }

    private Map<String, Object> pollAccepted() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        do {
            Map<String, Object> response = invoke(reverseComponent,
                    Map.of("op", 2, "listenId", reverseListenId));
            if (response.get("newConns") instanceof List<?> list && !list.isEmpty()) return response;
            Thread.sleep(40);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("reverse listener did not report the accepted connection");
    }

    private byte[] readUntil(Path component, Map<String, Object> request, int expectedBytes) throws Exception {
        byte[] result = new byte[0];
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        do {
            Map<String, Object> response = invoke(component, request);
            if (code(response) == 200 && response.get("data") instanceof byte[] chunk) {
                byte[] merged = new byte[result.length + chunk.length];
                System.arraycopy(result, 0, merged, 0, result.length);
                System.arraycopy(chunk, 0, merged, result.length, chunk.length);
                result = merged;
            }
            if (result.length >= expectedBytes) return result;
            Thread.sleep(40);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("timed out waiting for proxy data: " + result.length + "/" + expectedBytes);
    }

    private Map<String, Object> invoke(Path component, Map<String, Object> params) throws Exception {
        Map<String, Object> request = new LinkedHashMap<>(params);
        String encoded = Base64.getEncoder().encodeToString(PortableJsonCodec.encode(request));
        String script = "function leo_binary($value){return array('$leoBinary'=>base64_encode($value));}"
                + "function leo_decode($value){if(is_array($value)&&count($value)===1&&isset($value['$leoBinary']))"
                + "{return base64_decode($value['$leoBinary']);}if(is_array($value)){foreach($value as $key=>$item)"
                + "{$value[$key]=leo_decode($item);}}return $value;}"
                + "$component=require $argv[1];"
                + "$params=leo_decode(json_decode(base64_decode($argv[2]),true));"
                + "echo json_encode(call_user_func($component['handle'],'',$params));";
        return runJson(15, "php", "-r", script, component.toString(), encoded);
    }

    private Path component(String name) throws Exception {
        URL resource = Objects.requireNonNull(getClass().getResource("/components/" + name));
        return Paths.get(resource.toURI());
    }
}
