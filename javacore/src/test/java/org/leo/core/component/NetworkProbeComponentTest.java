package org.leo.core.component;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.ServerSocket;
import java.net.SocketAddress;
import java.net.URI;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import static org.leo.core.component.ComponentTestSupport.code;
import static org.leo.core.component.ComponentTestSupport.invokeComponent;
import static org.leo.core.component.ComponentTestSupport.params;

class NetworkProbeComponentTest {

    @AfterEach
    void clearStaticState() throws Exception {
        Map<Object, Object> tasks = state("TASKS");
        for (Object id : tasks.keySet()) {
            invokeComponent(new NetworkProbeComponent(), params("action", "stopTask", "taskId", id));
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
            Map<String, Object> started = invokeComponent(new NetworkProbeComponent(), params(
                    "action", "startTask", "plan", plan(Collections.singletonList(target),
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
            invokeComponent(new NetworkProbeComponent(), params("action", "stopTask", "taskId", taskId));
            Map<?, ?> stopped = awaitTask(taskId, 1000L);
            assertEquals("COMPLETED", stopped.get("outcome"));
            assertEquals(snapshot.get("finishedAt"), stopped.get("finishedAt"));
            invokeComponent(new NetworkProbeComponent(), params("action", "queryTask", "taskId", taskId, "cursor", 1L, "ackCursor", 1L));
            Map<?, ?> remaining = awaitTask(taskId, 1000L); // cursor zero may lag the acknowledged offset
            assertEquals(1, ((List<?>) remaining.get("observations")).size());
            assertEquals(2L, remaining.get("nextCursor"));
            invokeComponent(new NetworkProbeComponent(), params("action", "ackTask", "taskId", taskId, "cursor", Long.MAX_VALUE));
            Map<?, ?> drained = awaitTask(taskId, 1000L);
            assertTrue(((List<?>) drained.get("observations")).isEmpty());
            assertEquals(2L, drained.get("nextCursor"));
            assertEquals(200, code(invokeComponent(new NetworkProbeComponent(), params(
                    "action", "releaseTask", "taskId", taskId))));
            assertEquals(404, code(invokeComponent(new NetworkProbeComponent(), params(
                    "action", "queryTask", "taskId", taskId,
                    "cursor", 0L, "maxItems", 128, "maxBytes", 524288,
                    "includeEvidence", true))));
            responseFuture.get(2, TimeUnit.SECONDS);
        } finally {
            responder.shutdownNow();
        }
    }

    @ParameterizedTest @ValueSource(strings = {"plain", "chunked", "gzip"})
    void keepsHttpSummaryAndRuleRequestEvidenceDistinct(String encoding) throws Exception {
        Map<String, Map<String, String>> received = new java.util.concurrent.ConcurrentHashMap<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().toString();
            received.put(path, Map.of("method", exchange.getRequestMethod(),
                    "header", String.valueOf(exchange.getRequestHeaders().getFirst("X-Test")),
                    "body", new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.ISO_8859_1)));
            boolean rule = path.equals("/rules/check");
            byte[] body = rule ? "réponse".getBytes(StandardCharsets.ISO_8859_1)
                    : "<html><head><title> 资产管理平台 </title></head><body>ok</body></html>".getBytes(StandardCharsets.UTF_16LE);
            if ("gzip".equals(encoding)) {
                var compressed = new java.io.ByteArrayOutputStream();
                try (var gzip = new java.util.zip.GZIPOutputStream(compressed)) { gzip.write(body); }
                body = compressed.toByteArray();
                exchange.getResponseHeaders().set("Content-Encoding", "gzip");
            }
            exchange.getResponseHeaders().set("Content-Type", rule ? "text/plain; charset=ISO-8859-1" : "text/html; charset=\"UTF-16LE\"");
            exchange.getResponseHeaders().set("Server", "probe-fixture");
            exchange.getResponseHeaders().set("Location", "/redirect");
            exchange.sendResponseHeaders(rule ? 404 : 302, "chunked".equals(encoding) ? 0 : body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            Map<String, Object> summaryTarget = target(port, "http");
            summaryTarget.put("baseUrl", "http://virtual.example.invalid:" + port + "/landing?scope=one");
            summaryTarget.put("headers", Map.of("X-Test", "summary"));
            Map<String, Object> ruleTarget = target(port, "http");
            ruleTarget.put("stage", "http-request");
            ruleTarget.put("baseUrl", "http://virtual.example.invalid:" + port + "/rules");
            ruleTarget.put("headers", Map.of("X-Test", "unused"));
            ruleTarget.put("httpRequest", Map.of("method", "post", "path", "check",
                    "headers", Map.of("X-Test", "rule"), "charset", "ISO-8859-1", "body", " café\r\n "));
            Map<String, Object> started = invokeComponent(new NetworkProbeComponent(), params(
                    "action", "startTask", "plan", plan(List.of(summaryTarget, ruleTarget), List.of("http-head"))));
            Map<?, ?> snapshot = awaitTask(String.valueOf(started.get("taskId")), 5000L);
            assertEquals(List.of(), snapshot.get("errors"));
            List<?> observations = (List<?>) snapshot.get("observations");
            assertEquals(2, observations.size());
            Map<?, ?> summary = (Map<?, ?>) observations.stream().map(value -> (Map<?, ?>) value)
                    .filter(value -> "http-head".equals(value.get("stage"))).findFirst().orElseThrow().get("evidence");
            Map<?, ?> rule = (Map<?, ?>) observations.stream().map(value -> (Map<?, ?>) value)
                    .filter(value -> "http-request".equals(value.get("stage"))).findFirst().orElseThrow().get("evidence");
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
                            && "probe-fixture".equals(entry.getValue())));
            assertEquals(Map.of(
                    "/landing?scope=one", Map.of("method", "GET", "header", "summary", "body", ""),
                    "/rules/check", Map.of("method", "POST", "header", "rule", "body", " café\r\n ")), received);
        } finally {
            server.stop(0);
        }
    }

    @ParameterizedTest @ValueSource(strings = {"HEAD", "GET"})
    void keepsGzipHeadAndTruncatedPrefixEvidence(String method) throws Exception {
        byte[] body = new byte[256];
        new java.util.Random(1).nextBytes(body);
        var compressed = new java.io.ByteArrayOutputStream();
        try (var gzip = new java.util.zip.GZIPOutputStream(compressed)) { gzip.write(body); }
        byte[] encoded = compressed.toByteArray();
        assertTrue(encoded.length > 256);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.getResponseHeaders().set("Content-Encoding", "gzip");
            exchange.getResponseHeaders().set("Content-Length", String.valueOf(encoded.length));
            exchange.sendResponseHeaders(200, "HEAD".equals(method) ? -1 : encoded.length);
            if (!"HEAD".equals(method)) exchange.getResponseBody().write(encoded);
            exchange.close();
        });
        server.start();
        try {
            Map<String, Object> target = target(server.getAddress().getPort(), "http");
            target.put("httpRequest", Map.of("method", method, "charset", "ISO-8859-1"));
            target.put("maxReadBytes", 256);
            Map<?, ?> started = invokeComponent(new NetworkProbeComponent(), params("action", "startTask",
                    "plan", plan(List.of(target), List.of("http-request"))));
            Map<?, ?> result = awaitTask(String.valueOf(started.get("taskId")), 5000L);
            assertEquals(List.of(), result.get("errors"));
            Map<?, ?> evidence = (Map<?, ?>) ((Map<?, ?>) ((List<?>) result.get("observations")).get(0)).get("evidence");
            String prefix = String.valueOf(evidence.get("body"));
            assertEquals("GET".equals(method), evidence.get("truncated"));
            if ("HEAD".equals(method)) assertEquals("", prefix);
            else {
                assertTrue(!prefix.isEmpty() && prefix.length() <= 256);
                assertEquals(new String(body, StandardCharsets.ISO_8859_1).substring(0, prefix.length()), prefix);
            }
        } finally { server.stop(0); }
    }

    @Test
    void bypassesJvmSocksProxyForTcpConnect() throws Exception {
        ProxySelector previousSelector = ProxySelector.getDefault();
        AtomicInteger proxySelections = new AtomicInteger();
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        try (ServerSocket target = new ServerSocket(0, 1, loopback);
             ServerSocket proxy = new ServerSocket(0, 1, loopback)) {
            ProxySelector.setDefault(new ProxySelector() {
                @Override
                public List<Proxy> select(URI uri) {
                    proxySelections.incrementAndGet();
                    return List.of(new Proxy(Proxy.Type.SOCKS,
                            new InetSocketAddress(loopback, proxy.getLocalPort())));
                }

                @Override
                public void connectFailed(URI uri, SocketAddress address, IOException failure) {}
            });

            Map<String, Object> started = invokeComponent(new NetworkProbeComponent(), params(
                    "action", "startTask", "plan", plan(
                            Collections.singletonList(target(target.getLocalPort(), "tcp")),
                            Collections.singletonList("tcp-connect"))));
            Map<?, ?> snapshot = awaitTask(String.valueOf(started.get("taskId")), 5000L);
            Map<?, ?> observation = ((List<?>) snapshot.get("observations")).stream()
                    .map(value -> (Map<?, ?>) value)
                    .filter(value -> "tcp-connect".equals(value.get("stage")))
                    .findFirst().orElseThrow();
            assertEquals("open", observation.get("state"));
            assertEquals(0, proxySelections.get(), "Direct probes must bypass the JVM proxy selector");
        } finally {
            ProxySelector.setDefault(previousSelector);
        }
    }

    @Test
    void keepsProbeErrorsAsPartialResults() throws Exception {
        Map<String, Object> started = invokeComponent(new NetworkProbeComponent(), params(
                "action", "startTask", "plan", plan(
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
            Map<String, Object> started = invokeComponent(new NetworkProbeComponent(), params(
                    "action", "startTask", "plan", plan(List.of(target), List.of("tcp-exchange"))));
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
        Map<String, Object> started = invokeComponent(new NetworkProbeComponent(), params(
                "action", "startTask", "plan", plan(List.of(), List.of("tcp-connect"))));
        Map<?, ?> snapshot = awaitTask(String.valueOf(started.get("taskId")), 1000L);
        assertEquals("COMPLETED", snapshot.get("outcome"));
        assertEquals(0, snapshot.get("completed"));
    }

    private Map<String, Object> plan(List<Map<String, Object>> targets, List<String> stages) {
        return new HashMap<>(Map.of("targets", targets, "stages", stages,
                "limits", new HashMap<>(Map.of("threads", 1, "timeout", 1000, "maxReadBytes", 1024))));
    }

    private Map<String, Object> target(int port, String protocol) {
        return new HashMap<>(Map.of("host", "127.0.0.1", "port", port, "protocol", protocol));
    }

    private Map<?, ?> awaitTask(String taskId, long timeoutMillis) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        Map<?, ?> snapshot = Collections.emptyMap();
        while (System.currentTimeMillis() < deadline) {
            Map<String, Object> response = invokeComponent(new NetworkProbeComponent(), params(
                    "action", "queryTask", "taskId", taskId,
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
}
