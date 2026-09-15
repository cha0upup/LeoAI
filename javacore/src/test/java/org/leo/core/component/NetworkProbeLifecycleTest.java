package org.leo.core.component;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

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
                connection.getOutputStream().write("SSH-2.0-late\r\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
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

    private void invoke(NetworkProbeComponent component, String method, String taskId) throws Exception {
        Field params = NetworkProbeComponent.class.getDeclaredField("params"); params.setAccessible(true);
        Field results = NetworkProbeComponent.class.getDeclaredField("results"); results.setAccessible(true);
        params.set(component, new HashMap<>(Map.of("methodName", method, "taskId", taskId)));
        results.set(component, new HashMap<>());
        component.invoke();
    }

    @SuppressWarnings("unchecked")
    private Map<Object, Object> state(String name) throws Exception {
        Field field = NetworkProbeComponent.class.getDeclaredField(name); field.setAccessible(true);
        return (Map<Object, Object>) field.get(null);
    }
}
