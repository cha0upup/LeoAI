package org.leo.core.component;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.leo.core.util.javassist.CloneWithJavassist;

import java.lang.reflect.Field;
import java.lang.reflect.Constructor;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Lifecycle coverage for the unified probe task registry. */
class NetworkProbeLifecycleTest {

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void unexpectedWorkerErrorsAreFailuresRatherThanUserCancellations(boolean payload) throws Exception {
        Object component = ComponentTestSupport.component("NetworkProbeComponent", payload);
        Map<String, Object> task = new HashMap<>();
        task.put("taskId", "broken-worker");
        task.put("status", "RUNNING");
        task.put("outcome", "RUNNING");
        task.put("total", 1);
        task.put("targets", List.of(Map.of("host", "127.0.0.1", "port", 1)));
        task.put("plan", Map.of("stages", List.of())); // Malformed internal state triggers a worker exception.
        task.put("errors", new ArrayList<>());
        Field registry = component.getClass().getDeclaredField("TASKS");
        registry.setAccessible(true);
        Map tasks = (Map) registry.get(null);
        tasks.put("broken-worker", task);
        try {
            Constructor<?> constructor = component.getClass().getDeclaredConstructor(String.class);
            constructor.setAccessible(true);
            ((Runnable) constructor.newInstance("broken-worker")).run();
            assertEquals("STOPPED", task.get("status"));
            assertEquals("FAILED", task.get("outcome"));
            assertEquals("WORKER", ((Map<?, ?>) ((List<?>) task.get("errors")).get(0)).get("stage"));
        } finally {
            tasks.remove("broken-worker");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void concurrentWorkersRemainIsolatedAfterTheComponentIsTransformed(boolean transformed) throws Exception {
        Class<?> type = transformed
                ? new ComponentTestSupport.BytecodeLoader().load(CloneWithJavassist.cloneClass(
                        "NetworkProbeComponent", "org.leo.generated.ConcurrentProbe" + System.nanoTime(), 42L))
                : NetworkProbeComponent.class;
        List<Map<String, Object>> targets = new ArrayList<>();
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closedPort = socket.getLocalPort();
        }
        for (int i = 0; i < 256; i++) targets.add(Map.of("host", "127.0.0.1", "port", closedPort, "targetId", "target-" + i));
        Map<String, Object> started = ComponentTestSupport.runComponent((Runnable) type.getDeclaredConstructor().newInstance(),
                Map.of("action", "startTask", "plan", Map.of("targets", targets,
                        "stages", List.of("tcp-connect"), "limits", Map.of("threads", 32, "timeout", 100))));
        assertEquals(200, started.get("code"));
        String id = String.valueOf(started.get("taskId"));
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            Map<?, ?> snapshot = Map.of();
            while (System.nanoTime() < deadline) {
                Map<String, Object> response = ComponentTestSupport.runComponent((Runnable) type.getDeclaredConstructor().newInstance(),
                        Map.of("action", "queryTask", "taskId", id, "maxItems", 512));
                assertEquals(200, response.get("code"), response.toString());
                snapshot = (Map<?, ?>) response.get("result");
                if ("STOPPED".equals(snapshot.get("status"))) break;
                Thread.sleep(1L);
            }
            assertEquals("COMPLETED", snapshot.get("outcome"), snapshot.toString());
            assertEquals(256, snapshot.get("completed"));
            List<?> rows = (List<?>) snapshot.get("observations");
            assertEquals(256, rows.size());
            assertEquals(256L, rows.stream().map(row -> ((Map<?, ?>) row).get("targetId")).distinct().count());
            assertEquals(List.of(), snapshot.get("errors"));
        } finally {
            ComponentTestSupport.runComponent((Runnable) type.getDeclaredConstructor().newInstance(), Map.of("action", "stopTask", "taskId", id));
            ComponentTestSupport.runComponent((Runnable) type.getDeclaredConstructor().newInstance(), Map.of("action", "releaseTask", "taskId", id));
        }
    }

    @AfterEach
    void clearStaticState() throws Exception {
        state("TASKS").clear();
    }

    @Test
    void stopKeepsTaskSnapshotAvailableForPolling() throws Exception {
        String taskId = "lifecycle-task";
        Map<String, Object> task = new HashMap<>();
        task.put("taskId", taskId); task.put("status", "RUNNING");
        state("TASKS").put(taskId, task);
        NetworkProbeComponent component = new NetworkProbeComponent();
        invoke(component, "stopTask", taskId);
        assertEquals("STOPPED", task.get("status"));
        assertTrue(task.containsKey("finishedAt"));
        assertTrue(state("TASKS").containsKey(taskId));
    }

    @Test
    void pauseAndResumeChangeOnlyTaskState() throws Exception {
        String taskId = "pause-task";
        Map<String, Object> task = new HashMap<>(); task.put("taskId", taskId); task.put("status", "RUNNING");
        state("TASKS").put(taskId, task);
        NetworkProbeComponent component = new NetworkProbeComponent();
        invoke(component, "pauseTask", taskId); assertEquals("PAUSED", task.get("status"));
        invoke(component, "resumeTask", taskId); assertEquals("RUNNING", task.get("status"));
    }

    @Test
    void stoppedAndReleasedTaskRejectsLateWorkerResults() throws Exception {
        String taskId = "late-result-task";
        Map<String, Object> task = new HashMap<>();
        task.put("taskId", taskId);
        task.put("status", "RUNNING");
        task.put("completed", 0);
        task.put("total", 1);
        task.put("observations", new ArrayList<>());
        task.put("errors", new ArrayList<>());
        task.put("plan", Map.of("stages", List.of("tcp-exchange"), "timeout", 1000, "maxReadBytes", 1024));
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            server.setSoTimeout(2000);
            task.put("targets", List.of(Map.of("host", "127.0.0.1", "port", server.getLocalPort(), "protocol", "tcp")));
            state("TASKS").put(taskId, task);
            Constructor<NetworkProbeComponent> constructor = NetworkProbeComponent.class.getDeclaredConstructor(String.class);
            constructor.setAccessible(true);
            NetworkProbeComponent worker = constructor.newInstance(taskId);
            CompletableFuture<Void> completed = new CompletableFuture<>();
            Thread thread = new Thread(() -> {
                try { worker.run(); completed.complete(null); }
                catch (Throwable error) { completed.completeExceptionally(error); }
            });
            thread.setDaemon(true);
            thread.start();
            try (Socket connection = server.accept()) {
                invoke(new NetworkProbeComponent(), "stopTask", taskId);
                Map<String, Object> stopped = new HashMap<>(task);
                invoke(new NetworkProbeComponent(), "releaseTask", taskId);
                connection.setSoTimeout(1000);
                assertEquals(-1, connection.getInputStream().read(), "stop must close the active socket");
                connection.shutdownOutput();
                completed.get(3, TimeUnit.SECONDS);
                assertEquals(stopped, task);
                assertEquals(List.of(), task.get("observations"));
                assertEquals(List.of(), task.get("errors"));
                assertFalse(state("TASKS").containsKey(taskId));
            } finally {
                synchronized (task) { task.put("status", "STOPPED"); task.notifyAll(); }
                thread.join(2000);
            }
        }
    }

    @ParameterizedTest @CsvSource({"false,pause", "true,pause", "false,pressure", "true,pressure"})
    void parkedNodeWorkersReleasePoolThreadsWithoutReplayingTheCurrentStage(boolean payload, String mode) throws Exception {
        Object component = ComponentTestSupport.component("NetworkProbeComponent", payload);
        String id = "";
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            server.setSoTimeout(2000);
            Map<String, Object> plan = Map.of("targets", List.of(Map.of("host", "127.0.0.1", "port", server.getLocalPort())),
                    "stages", "pause".equals(mode) ? List.of("tcp-exchange", "tcp-exchange") : List.of("tcp-exchange"),
                    "limits", Map.of("threads", 1, "timeout", 1000));
            id = String.valueOf(ComponentTestSupport.invokeComponent(component, Map.of("action", "startTask", "plan", plan)).get("taskId"));
            Field registry = component.getClass().getDeclaredField("TASKS"); registry.setAccessible(true);
            Map task = (Map) ((Map)registry.get(null)).get(id);
            try (Socket connection = server.accept()) {
                if ("pause".equals(mode)) ComponentTestSupport.invokeComponent(component, Map.of("action", "pauseTask", "taskId", id));
                else synchronized (task) {
                    Map<String, Object> row = Map.of("state", "open");
                    Object size = ComponentTestSupport.call(component, "observationSize", new Class[]{Map.class}, row);
                    for (int i = 0; i < 3072; i++) ComponentTestSupport.call(component, "offerObservation",
                            new Class[]{Map.class, Map.class, long[].class}, task, row, size);
                }
                connection.getOutputStream().write("first".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            }
            Field executor = component.getClass().getDeclaredField("WORK_EXECUTOR"); executor.setAccessible(true);
            var pool = (java.util.concurrent.ThreadPoolExecutor) executor.get(null);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (pool.getActiveCount() > 0 && System.nanoTime() < deadline) Thread.sleep(5);
            assertEquals(0, pool.getActiveCount(), "idle tasks must return their physical worker threads");
            Object small = component.getClass().getDeclaredConstructor().newInstance();
            String smallId = String.valueOf(ComponentTestSupport.invokeComponent(small, Map.of("action", "startTask", "plan",
                    Map.of("targets", List.of(Map.of("host", "127.0.0.1", "port", 1)), "stages", List.of("fixture-stage")))).get("taskId"));
            awaitNode(small, smallId);
            ComponentTestSupport.invokeComponent(small, Map.of("action", "releaseTask", "taskId", smallId));
            if ("pause".equals(mode)) {
                ComponentTestSupport.invokeComponent(component, Map.of("action", "resumeTask", "taskId", id));
                try (Socket connection = server.accept()) { connection.getOutputStream().write("second".getBytes(java.nio.charset.StandardCharsets.US_ASCII)); }
            } else ComponentTestSupport.invokeComponent(component, Map.of("action", "ackTask", "taskId", id, "cursor", 3072L));
            Map<?, ?> result = awaitNode(component, id);
            assertEquals(1, result.get("completed"));
            List<?> rows = (List<?>)result.get("observations");
            assertEquals("pause".equals(mode) ? 2 : 1, rows.size());
            assertEquals("first", ((Map<?, ?>)((Map<?, ?>)rows.get(0)).get("evidence")).get("banner"));
            if ("pause".equals(mode)) assertEquals("second", ((Map<?, ?>)((Map<?, ?>)rows.get(1)).get("evidence")).get("banner"));
        } finally {
            if (!id.isEmpty()) {
                ComponentTestSupport.invokeComponent(component, Map.of("action", "stopTask", "taskId", id));
                ComponentTestSupport.invokeComponent(component, Map.of("action", "releaseTask", "taskId", id));
            }
        }
    }

    private Map<?, ?> awaitNode(Object component, String id) throws Exception {
        for (int i = 0; i < 200; i++) {
            Map<?, ?> result = (Map<?, ?>) ComponentTestSupport.invokeComponent(component,
                    Map.of("action", "queryTask", "taskId", id)).get("result");
            if ("STOPPED".equals(result.get("status"))) { assertEquals("COMPLETED", result.get("outcome")); return result; }
            Thread.sleep(5);
        }
        throw new AssertionError("node task did not complete");
    }

    private void invoke(NetworkProbeComponent component, String method, String taskId) throws Exception {
        Field params = NetworkProbeComponent.class.getDeclaredField("params"); params.setAccessible(true);
        Field results = NetworkProbeComponent.class.getDeclaredField("results"); results.setAccessible(true);
        params.set(component, new HashMap<>(Map.of("action", method, "taskId", taskId)));
        results.set(component, new HashMap<>());
        component.invoke();
    }

    @SuppressWarnings("unchecked")
    private Map<Object, Object> state(String name) throws Exception {
        Field field = NetworkProbeComponent.class.getDeclaredField(name); field.setAccessible(true);
        return (Map<Object, Object>) field.get(null);
    }
}
