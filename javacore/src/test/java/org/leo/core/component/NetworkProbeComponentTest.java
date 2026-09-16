package org.leo.core.component;

import com.sun.net.httpserver.HttpServer;
import java.io.InputStream;
import java.io.OutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.leo.core.util.javassist.CloneWithJavassist;

import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkProbeComponentTest {

    @AfterEach
    void clearStaticState() throws Exception {
        Map<Object, Object> tasks = state("TASKS");
        for (Object id : tasks.keySet()) {
            invoke(new NetworkProbeComponent(), params("methodName", "stopTask", "taskId", id));
        }
        tasks.clear();
    }

    @Test
    void reportsOpenConnectAndExchangeEvidence() throws Exception {
        ExecutorService responder = Executors.newSingleThreadExecutor();
        try (ServerSocket server = new ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"))) {
            Future<?> responseFuture = responder.submit(() -> {
                for (int i = 0; i < 2; i++) {
                    try (Socket client = server.accept()) {
                        client.getOutputStream().write("SSH-2.0-Leo\r\n".getBytes(StandardCharsets.ISO_8859_1));
                        client.getOutputStream().flush();
                    }
                }
                return null;
            });
            Map<String, Object> target = target(server.getLocalPort(), "tcp");
            Map<String, Object> started = invoke(new NetworkProbeComponent(), params(
                    "methodName", "startTask", "plan", plan(Collections.singletonList(target),
                            List.of("tcp-connect", "tcp-exchange"))));
            assertEquals(200, code(started));
            Map<?, ?> snapshot = awaitTask(String.valueOf(started.get("taskId")), 5000L);
            assertEquals("STOPPED", snapshot.get("status"));
            assertEquals("COMPLETED", snapshot.get("outcome"));
            assertEquals(1, snapshot.get("completed"));
            List<?> observations = (List<?>) snapshot.get("observations");
            assertTrue(observations.stream().anyMatch(value -> "tcp-connect".equals(((Map<?, ?>) value).get("stage"))));
            Map<?, ?> exchange = observations.stream().map(value -> (Map<?, ?>) value)
                    .filter(value -> "tcp-exchange".equals(value.get("stage"))).findFirst().orElseThrow();
            assertEquals("SSH-2.0-Leo", ((Map<?, ?>) exchange.get("evidence")).get("banner"));
            String taskId = String.valueOf(started.get("taskId"));
            invoke(new NetworkProbeComponent(), params("methodName", "stopTask", "taskId", taskId));
            Map<?, ?> stopped = awaitTask(taskId, 1000L);
            assertEquals("COMPLETED", stopped.get("outcome"));
            assertEquals(snapshot.get("finishedAt"), stopped.get("finishedAt"));
            invoke(new NetworkProbeComponent(), params("methodName", "ackTask", "taskId", taskId, "cursor", 1L));
            Map<?, ?> remaining = awaitTask(taskId, 1000L); // cursor zero may lag the acknowledged offset
            assertEquals(1, ((List<?>) remaining.get("observations")).size());
            assertEquals(2L, remaining.get("nextCursor"));
            invoke(new NetworkProbeComponent(), params("methodName", "ackTask", "taskId", taskId, "cursor", Long.MAX_VALUE));
            Map<?, ?> drained = awaitTask(taskId, 1000L);
            assertTrue(((List<?>) drained.get("observations")).isEmpty());
            assertEquals(2L, drained.get("nextCursor"));
            assertEquals(200, code(invoke(new NetworkProbeComponent(), params(
                    "methodName", "releaseTask", "taskId", taskId))));
            assertEquals(404, code(invoke(new NetworkProbeComponent(), params(
                    "methodName", "queryTask", "taskId", taskId,
                    "cursor", 0L, "maxItems", 128, "maxBytes", 524288,
                    "includeEvidence", true))));
            responseFuture.get(2, TimeUnit.SECONDS);
        } finally {
            responder.shutdownNow();
        }
    }

    @Test
    void keepsHttpSummaryAndRuleRequestEvidenceDistinct() throws Exception {
        Map<String, Map<String, String>> received = new java.util.concurrent.ConcurrentHashMap<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().toString();
            received.put(path, Map.of("method", exchange.getRequestMethod(),
                    "header", String.valueOf(exchange.getRequestHeaders().getFirst("X-Test")),
                    "body", new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.ISO_8859_1)));
            boolean rule = path.equals("/rules/check");
            byte[] body = rule ? "réponse".getBytes(StandardCharsets.ISO_8859_1)
                    : "<html><head><title> 资产管理平台 </title></head><body>ok</body></html>".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", rule ? "text/plain; charset=ISO-8859-1" : "text/html; charset=UTF-8");
            exchange.getResponseHeaders().set("Server", "probe-fixture");
            exchange.getResponseHeaders().set("Location", "/redirect");
            exchange.sendResponseHeaders(rule ? 404 : 302, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            Map<String, Object> summaryTarget = target(port, "http");
            summaryTarget.put("baseUrl", "http://127.0.0.1:" + port + "/landing?scope=one");
            summaryTarget.put("headers", Map.of("X-Test", "summary"));
            Map<String, Object> ruleTarget = target(port, "http");
            ruleTarget.put("stage", "http-request");
            ruleTarget.put("baseUrl", "http://127.0.0.1:" + port + "/rules");
            ruleTarget.put("headers", Map.of("X-Test", "unused"));
            ruleTarget.put("httpRequest", Map.of("method", "post", "path", "check",
                    "headers", Map.of("X-Test", "rule"), "charset", "ISO-8859-1", "body", " café\r\n "));
            Map<String, Object> started = invoke(new NetworkProbeComponent(), params(
                    "methodName", "startTask", "plan", plan(List.of(summaryTarget, ruleTarget), List.of("http-head"))));
            Map<?, ?> snapshot = awaitTask(String.valueOf(started.get("taskId")), 5000L);
            assertEquals(List.of(), snapshot.get("errors"));
            List<?> observations = (List<?>) snapshot.get("observations");
            assertEquals(2, observations.size());
            Map<?, ?> summary = (Map<?, ?>) ((Map<?, ?>) observations.get(0)).get("evidence");
            Map<?, ?> rule = (Map<?, ?>) ((Map<?, ?>) observations.get(1)).get("evidence");
            assertEquals("资产管理平台", summary.get("title"));
            assertEquals(302, summary.get("statusCode"));
            assertEquals("probe-fixture", summary.get("server"));
            assertEquals("/redirect", summary.get("location"));
            assertFalse(summary.containsKey("body"));
            assertFalse(summary.containsKey("responseHeaders"));
            assertEquals(404, rule.get("statusCode"));
            assertEquals("réponse", rule.get("body"));
            assertTrue(((Map<?, ?>) rule.get("responseHeaders")).entrySet().stream()
                    .anyMatch(entry -> "server".equalsIgnoreCase(String.valueOf(entry.getKey()))
                            && "[probe-fixture]".equals(entry.getValue())));
            assertEquals(Map.of(
                    "/landing?scope=one", Map.of("method", "GET", "header", "summary", "body", ""),
                    "/rules/check", Map.of("method", "POST", "header", "rule", "body", " café\r\n ")), received);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void bypassesJvmSocksProxyForTcpConnect() throws Exception {
        String previousProxyHost = System.getProperty("socksProxyHost");
        String previousProxyPort = System.getProperty("socksProxyPort");
        String previousNonProxyHosts = System.getProperty("socksNonProxyHosts");
        ExecutorService proxyExecutor = Executors.newSingleThreadExecutor();
        ServerSocket proxy = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        try {
            int proxyPort = proxy.getLocalPort();
            Future<?> proxyFuture = proxyExecutor.submit(() -> {
                try (Socket client = proxy.accept()) {
                    InputStream input = client.getInputStream();
                    OutputStream output = client.getOutputStream();
                    int version = input.read();
                    int methodCount = input.read();
                    if (version != 5 || methodCount < 0) return null;
                    for (int i = 0; i < methodCount; i++) input.read();
                    output.write(new byte[]{5, 0});
                    output.flush();
                    int requestVersion = input.read();
                    int command = input.read();
                    int reserved = input.read();
                    int addressType = input.read();
                    if (requestVersion != 5 || command != 1 || reserved != 0) return null;
                    int addressLength = addressType == 1 ? 4 : addressType == 3 ? input.read() : 16;
                    for (int i = 0; i < addressLength + 2; i++) input.read();
                    output.write(new byte[]{5, 0, 0, 1, 127, 0, 0, 1, 0, 1});
                    output.flush();
                    Thread.sleep(100L);
                }
                return null;
            });
            System.setProperty("socksProxyHost", "127.0.0.1");
            System.setProperty("socksProxyPort", String.valueOf(proxyPort));
            System.setProperty("socksNonProxyHosts", "");

            Map<String, Object> directTarget = new HashMap<>(Map.of(
                    "host", "198.51.100.1", "port", 65000, "protocol", "tcp"));
            Map<String, Object> started = invoke(new NetworkProbeComponent(), params(
                    "methodName", "startTask", "plan", plan(
                            Collections.singletonList(directTarget),
                            Collections.singletonList("tcp-connect"))));
            Map<?, ?> snapshot = awaitTask(String.valueOf(started.get("taskId")), 5000L);
            Map<?, ?> observation = ((List<?>) snapshot.get("observations")).stream()
                    .map(value -> (Map<?, ?>) value)
                    .filter(value -> "tcp-connect".equals(value.get("stage")))
                    .findFirst().orElseThrow();
            assertEquals("closed", observation.get("state"));

            proxy.close();
            proxyFuture.cancel(true);
        } finally {
            if (previousProxyHost == null) System.clearProperty("socksProxyHost");
            else System.setProperty("socksProxyHost", previousProxyHost);
            if (previousProxyPort == null) System.clearProperty("socksProxyPort");
            else System.setProperty("socksProxyPort", previousProxyPort);
            if (previousNonProxyHosts == null) System.clearProperty("socksNonProxyHosts");
            else System.setProperty("socksNonProxyHosts", previousNonProxyHosts);
            proxy.close();
            proxyExecutor.shutdownNow();
        }
    }

    @Test
    void keepsProbeErrorsAsPartialResults() throws Exception {
        Map<String, Object> started = invoke(new NetworkProbeComponent(), params(
                "methodName", "startTask", "plan", plan(
                        Collections.singletonList(target(80, "tcp")),
                        Collections.singletonList("http-request"))));

        Map<?, ?> snapshot = awaitTask(String.valueOf(started.get("taskId")), 5000L);

        assertEquals("COMPLETED", snapshot.get("outcome"));
        assertTrue(((List<?>) snapshot.get("errors")).size() > 0);
    }

    @Test
    void preservesTcpRequestTerminators() throws Exception {
        ExecutorService responder = Executors.newSingleThreadExecutor();
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            Future<String> received = responder.submit(() -> {
                try (Socket client = server.accept()) {
                    client.setSoTimeout(1000);
                    byte[] bytes = client.getInputStream().readNBytes(6);
                    client.getOutputStream().write("+PONG\r\n".getBytes(StandardCharsets.ISO_8859_1));
                    return new String(bytes, StandardCharsets.ISO_8859_1);
                }
            });
            Map<String, Object> target = target(server.getLocalPort(), "tcp");
            target.put("request", "PING\r\n");
            Map<String, Object> started = invoke(new NetworkProbeComponent(), params(
                    "methodName", "startTask", "plan", plan(List.of(target), List.of("tcp-exchange"))));
            Map<?, ?> snapshot = awaitTask(String.valueOf(started.get("taskId")), 5000L);
            assertEquals("PING\r\n", received.get(2, TimeUnit.SECONDS));
            Map<?, ?> observation = (Map<?, ?>) ((List<?>) snapshot.get("observations")).get(0);
            assertEquals("+PONG", ((Map<?, ?>) observation.get("evidence")).get("banner"));
        } finally {
            responder.shutdownNow();
        }
    }

    @Test
    void emptyPlanCompletesWithoutSchedulingWorkers() throws Exception {
        Map<String, Object> started = invoke(new NetworkProbeComponent(), params(
                "methodName", "startTask", "plan", plan(List.of(), List.of("tcp-connect"))));
        Map<?, ?> snapshot = awaitTask(String.valueOf(started.get("taskId")), 1000L);
        assertEquals("COMPLETED", snapshot.get("outcome"));
        assertEquals(0, snapshot.get("completed"));
    }

    @Test
    void transformedPayloadInitializesAfterMethodRandomization() throws Exception {
        String className = "org.leo.generated.NetworkProbe" + System.nanoTime();
        byte[] bytecode = CloneWithJavassist.cloneClass("NetworkProbeComponent", className);
        Class<?> transformed = new BytecodeLoader().define(className, bytecode);
        assertTrue(Runnable.class.isAssignableFrom(transformed));
        assertTrue(transformed.getDeclaredConstructor().newInstance() instanceof Runnable);
    }

    private Map<String, Object> invoke(NetworkProbeComponent component, HashMap<String, Object> params) throws Exception {
        HashMap<String, Object> results = new HashMap<>();
        setField(component, "params", params); setField(component, "results", results);
        component.getClass().getDeclaredMethod("invoke").invoke(component);
        return results;
    }

    private HashMap<String, Object> params(Object... values) {
        HashMap<String, Object> result = new HashMap<>();
        for (int i = 0; i < values.length; i += 2) result.put((String) values[i], values[i + 1]);
        return result;
    }

    private Map<String, Object> plan(List<Map<String, Object>> targets, List<String> stages) {
        return new HashMap<>(Map.of("targets", targets, "stages", stages,
                "limits", new HashMap<>(Map.of("threads", 1, "timeout", 1000, "maxReadBytes", 1024))));
    }

    private Map<String, Object> target(int port, String protocol) {
        return new HashMap<>(Map.of("host", "127.0.0.1", "port", port, "protocol", protocol));
    }

    private int code(Map<String, Object> response) { return ((Number) response.get("code")).intValue(); }

    private Map<?, ?> awaitTask(String taskId, long timeoutMillis) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        Map<?, ?> snapshot = Collections.emptyMap();
        while (System.currentTimeMillis() < deadline) {
            Map<String, Object> response = invoke(new NetworkProbeComponent(), params(
                    "methodName", "queryTask", "taskId", taskId,
                    "cursor", 0L, "maxItems", 128, "maxBytes", 524288,
                    "includeEvidence", true));
            snapshot = (Map<?, ?>) response.get("result");
            if (snapshot != null && "STOPPED".equals(snapshot.get("status"))) return snapshot;
            Thread.sleep(20L);
        }
        throw new AssertionError("network probe task did not finish: " + snapshot);
    }

    @SuppressWarnings("unchecked")
    private Map<Object, Object> state(String name) throws Exception {
        Field field = NetworkProbeComponent.class.getDeclaredField(name); field.setAccessible(true);
        return (Map<Object, Object>) field.get(null);
    }

    private void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); field.set(target, value);
    }

    private static final class BytecodeLoader extends ClassLoader {
        private Class<?> define(String name, byte[] bytecode) { return defineClass(name, bytecode, 0, bytecode.length); }
    }
}
