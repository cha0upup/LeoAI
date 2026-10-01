package org.leo.core.agent.http;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpSenderEngineTest {

    private final RecordingEngine engine = new RecordingEngine();

    @AfterEach
    void tearDown() {
        engine.close();
    }

    @Test
    void parsesNormalizesAndForwardsRawRequest() throws Exception {
        String raw = "PATCH /api/items?id=1 HTTP/1.1\r\n"
                + "host: example.test:1234\r\n"
                + "content-length: 999\r\n"
                + "X-Test: yes\r\n\r\n"
                + "hello世界";

        Map<String, Object> result = engine.sendRawHttp(raw, null, 9090,
                false, true, 321, 654);

        assertEquals("PATCH", engine.method);
        assertEquals("http://example.test:9090/api/items?id=1", engine.url);
        assertEquals("example.test:9090", header(engine.headers, "Host"));
        assertEquals("11", header(engine.headers, "Content-Length"));
        assertEquals("yes", header(engine.headers, "X-Test"));
        assertEquals("hello世界", engine.body);
        assertEquals(321, engine.connectTimeout);
        assertEquals(654, engine.readTimeout);
        assertTrue(engine.followRedirects);
        assertEquals(engine.url, result.get("requestUrl"));
        assertEquals("PATCH", result.get("requestMethod"));
    }

    @Test
    void runsFuzzerAndEvaluatesMatchRules() throws Exception {
        Map<String, List<String>> payloads = new LinkedHashMap<>();
        payloads.put("user", List.of("admin", "guest"));
        payloads.put("id", List.of("1", "2"));
        String raw = "POST /lookup HTTP/1.1\r\nHost: example.test\r\n\r\n{{user}}-{{id}}";

        Map<String, Object> started = engine.startFuzz(raw, payloads, "example.test", 80,
                false, 2, 0, Map.of("statusCode", 200, "bodyContains", "ok-"));
        String taskId = String.valueOf(started.get("taskId"));
        Map<String, Object> state = waitUntilFinished(taskId);

        assertEquals("FINISHED", state.get("status"));
        assertEquals(4, ((Number) state.get("total")).intValue());
        assertEquals(4, ((Number) state.get("completed")).intValue());
        List<?> results = (List<?>) state.get("results");
        assertEquals(4, results.size());
        assertTrue(results.stream().allMatch(item -> Boolean.TRUE.equals(((Map<?, ?>) item).get("success"))));
        assertTrue(results.stream().allMatch(item -> Boolean.TRUE.equals(((Map<?, ?>) item).get("matched"))));
        assertEquals(List.of("admin-1", "admin-2", "guest-1", "guest-2"),
                engine.requestBodies.stream().sorted().toList());
    }

    @Test
    void rejectsInvalidTargetsAndCombinationExplosion() {
        String raw = "GET / HTTP/1.1\r\nHost: example.test\r\n\r\n";
        assertThrows(IllegalArgumentException.class,
                () -> engine.sendRawHttp(raw, "example.test", 70000, false, false, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> engine.startFuzz(raw, Map.of("id", List.of("1")),
                        "example.test", 0, false, 1, 0, null));

        List<String> values = new ArrayList<>();
        for (int i = 0; i < 101; i++) values.add(String.valueOf(i));
        Map<String, List<String>> payloads = new LinkedHashMap<>();
        payloads.put("a", values);
        payloads.put("b", values);
        assertThrows(IllegalArgumentException.class, () -> HttpSenderEngine.generateCombinations(payloads));
    }

    @Test
    void matchesBinaryLengthAndResponseHeaders() {
        Map<String, Object> response = Map.of(
                "statusCode", 206,
                "body", new byte[]{1, 2, 3, 4},
                "responseHeaders", Map.of("X-Marker", List.of("one", "header-ok")));

        assertTrue(HttpSenderEngine.evaluateMatch(response,
                Map.of("statusCode", List.of(200L, 206L), "bodyLengthMin", 4,
                        "bodyLengthMax", 4, "headerContains", "header-ok")));
    }

    @Test
    void stopInterruptsRunningWorkAndKeepsTerminalStateStable() throws Exception {
        BlockingEngine blockingEngine = new BlockingEngine();
        try {
            String raw = "POST / HTTP/1.1\r\nHost: example.test\r\n\r\n{{value}}";
            Map<String, Object> started = blockingEngine.startFuzz(raw,
                    Map.of("value", List.of("one", "two", "three")),
                    "example.test", 80, false, 1, 0, null);
            String taskId = String.valueOf(started.get("taskId"));

            assertTrue(blockingEngine.requestStarted.await(2, TimeUnit.SECONDS));
            assertEquals("fuzzer stopped", blockingEngine.stopFuzz(taskId).get("msg"));
            blockingEngine.releaseRequest.countDown();

            Map<String, Object> state = waitUntilCompleted(blockingEngine, taskId, 1);
            assertEquals("STOPPED", state.get("status"));
            assertEquals(1, ((Number) state.get("completed")).intValue());
            assertEquals(1, blockingEngine.calls.get());
            assertEquals("fuzzer already completed", blockingEngine.stopFuzz(taskId).get("msg"));
            assertEquals("STOPPED", blockingEngine.queryFuzz(taskId).get("status"));
        } finally {
            blockingEngine.releaseRequest.countDown();
            blockingEngine.close();
        }
    }

    @Test
    void closeIsIdempotentAndRejectsNewFuzzerTasks() {
        engine.close();
        engine.close();
        String raw = "GET / HTTP/1.1\r\nHost: example.test\r\n\r\n";
        assertThrows(IllegalStateException.class,
                () -> engine.startFuzz(raw, Map.of("id", List.of("1")),
                        "example.test", 80, false, 1, 0, null));
    }

    @Test
    void boundsTheSharedFuzzExecutorAcrossTasks() throws Exception {
        SaturatedEngine saturated = new SaturatedEngine();
        String raw = "POST / HTTP/1.1\r\nHost: example.test\r\n\r\n{{value}}";
        try {
            Map<String, Object> first = saturated.startFuzz(raw,
                    Map.of("value", List.of("one")),
                    "example.test", 80, false, 1, 0, null);
            assertTrue(saturated.requestStarted.await(2, TimeUnit.SECONDS));
            Map<String, Object> second = saturated.startFuzz(raw,
                    Map.of("value", List.of("two")),
                    "example.test", 80, false, 1, 0, null);

            assertThrows(RejectedExecutionException.class,
                    () -> saturated.startFuzz(raw, Map.of("value", List.of("three")),
                            "example.test", 80, false, 1, 0, null));

            saturated.stopFuzz(String.valueOf(first.get("taskId")));
            saturated.stopFuzz(String.valueOf(second.get("taskId")));
        } finally {
            saturated.releaseRequest.countDown();
            saturated.close();
        }
    }

    private Map<String, Object> waitUntilFinished(String taskId) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        Map<String, Object> state;
        do {
            state = engine.queryFuzz(taskId);
            if ("FINISHED".equals(state.get("status"))) return state;
            Thread.sleep(10);
        } while (System.nanoTime() < deadline);
        return state;
    }

    private Map<String, Object> waitUntilCompleted(HttpSenderEngine target, String taskId,
                                                    int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        Map<String, Object> state;
        do {
            state = target.queryFuzz(taskId);
            if (((Number) state.get("completed")).intValue() >= expected) return state;
            Thread.sleep(10);
        } while (System.nanoTime() < deadline);
        return state;
    }

    private String header(Map<String, String> headers, String expected) {
        return headers.entrySet().stream()
                .filter(entry -> expected.equalsIgnoreCase(entry.getKey()))
                .map(Map.Entry::getValue)
                .findFirst().orElse(null);
    }

    private static final class RecordingEngine extends HttpSenderEngine {
        private volatile String method;
        private volatile String url;
        private volatile Map<String, String> headers;
        private volatile String body;
        private volatile int connectTimeout;
        private volatile int readTimeout;
        private volatile boolean followRedirects;
        private final List<String> requestBodies = java.util.Collections.synchronizedList(new ArrayList<>());

        @Override
        protected Map<String, Object> executeRequest(String method, String url,
                                                     Map<String, String> headers, String body,
                                                     int connectTimeout, int readTimeout,
                                                     boolean followRedirects) {
            this.method = method;
            this.url = url;
            this.headers = headers;
            this.body = body;
            this.connectTimeout = connectTimeout;
            this.readTimeout = readTimeout;
            this.followRedirects = followRedirects;
            requestBodies.add(body);
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("code", 200);
            response.put("statusCode", 200);
            response.put("bodyType", "text");
            response.put("body", "ok-" + body);
            response.put("responseHeaders", Map.of("Content-Type", "text/plain"));
            return response;
        }
    }

    private static final class BlockingEngine extends HttpSenderEngine {
        private final CountDownLatch requestStarted = new CountDownLatch(1);
        private final CountDownLatch releaseRequest = new CountDownLatch(1);
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        protected Map<String, Object> executeRequest(String method, String url,
                                                     Map<String, String> headers, String body,
                                                     int connectTimeout, int readTimeout,
                                                     boolean followRedirects) throws Exception {
            calls.incrementAndGet();
            requestStarted.countDown();
            releaseRequest.await();
            return Map.of("code", 200, "statusCode", 200, "body", "ok");
        }
    }

    private static final class SaturatedEngine extends HttpSenderEngine {
        private final CountDownLatch requestStarted = new CountDownLatch(1);
        private final CountDownLatch releaseRequest = new CountDownLatch(1);

        private SaturatedEngine() {
            super(1, 1);
        }

        @Override
        protected Map<String, Object> executeRequest(String method, String url,
                                                     Map<String, String> headers, String body,
                                                     int connectTimeout, int readTimeout,
                                                     boolean followRedirects) throws Exception {
            requestStarted.countDown();
            releaseRequest.await();
            return Map.of("code", 200, "statusCode", 200, "body", "ok");
        }
    }
}
