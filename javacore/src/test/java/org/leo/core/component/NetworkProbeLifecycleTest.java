package org.leo.core.component;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Lifecycle coverage for the unified probe task registry. */
class NetworkProbeLifecycleTest {

    @AfterEach
    void clearStaticState() throws Exception {
        state("TASKS").clear();
        state("TASK_LOCKS").clear();
    }

    @Test
    void stopKeepsTaskSnapshotAvailableForPolling() throws Exception {
        String taskId = "lifecycle-task";
        Map<String, Object> task = new HashMap<>();
        task.put("taskId", taskId); task.put("status", "RUNNING");
        state("TASKS").put(taskId, task); state("TASK_LOCKS").put(taskId, new Object());
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
        state("TASKS").put(taskId, task); state("TASK_LOCKS").put(taskId, new Object());
        NetworkProbeComponent component = new NetworkProbeComponent();
        invoke(component, "pauseTask", taskId); assertEquals("PAUSED", task.get("status"));
        invoke(component, "resumeTask", taskId); assertEquals("RUNNING", task.get("status"));
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
