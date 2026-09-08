package org.leo.core.component;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.leo.core.util.javassist.CloneWithJavassist;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkProbeComponentTest {

    @AfterEach
    void clearStaticState() throws Exception {
        Map<?, ?> tasks = state("TASKS");
        for (Object value : tasks.values()) {
            Object executor = ((Map<?, ?>) value).get("executor");
            if (executor instanceof ExecutorService service) service.shutdownNow();
        }
        tasks.clear();
        state("TASK_LOCKS").clear();
    }

    @Test
    void advertisesBoundedAtomicStages() throws Exception {
        Map<String, Object> response = invoke(new NetworkProbeComponent(), params("methodName", "capabilities"));
        assertEquals(200, code(response));
        assertEquals("NetworkProbeComponent", response.get("component"));
        assertTrue(((List<?>) response.get("stages")).contains("tcp-connect"));
        assertTrue(((List<?>) response.get("stages")).contains("http-request"));
        assertEquals(8192, response.get("maxReadBytes"));
    }

    @Test
    void rejectsInvalidTargetsBeforeRegistration() throws Exception {
        InvocationTargetException error = assertThrows(InvocationTargetException.class,
                () -> invoke(new NetworkProbeComponent(), params("methodName", "startTask", "plan",
                        plan(Collections.singletonList(Collections.singletonMap("host", "bad host")),
                                Collections.singletonList("tcp-connect")))));
        assertTrue(error.getCause() instanceof IllegalArgumentException);
        assertTrue(state("TASKS").isEmpty());
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
