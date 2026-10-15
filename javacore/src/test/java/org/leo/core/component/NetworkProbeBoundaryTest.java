package org.leo.core.component;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.leo.core.component.ComponentTestSupport.*;

class NetworkProbeBoundaryTest {
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void rejectsOversizedPlansBeforeRegisteringOrStartingWorkers(boolean payload) throws Exception {
        Object component = component("NetworkProbeComponent", payload);
        Map<String, Object> target = Map.of("host", "127.0.0.1", "port", 1);
        for (Map<String, Object> plan : List.<Map<String, Object>>of(
                Map.of("targets", Collections.nCopies(4097, target)),
                Map.of("targets", List.of(target), "stages", Collections.nCopies(9, "tcp-connect")),
                Map.of("targets", List.of(Map.of("host", "127.0.0.1", "port", 1,
                        "request", "x".repeat(2 * 1024 * 1024 + 1)))))) {
            Map<?, ?> tasks = registry(component);
            int before = tasks.size();
            InvocationTargetException error = assertThrows(InvocationTargetException.class,
                    () -> call(component, "startTask", new Class[]{Map.class}, plan));
            assertInstanceOf(IllegalArgumentException.class, error.getCause());
            assertEquals(before, tasks.size());
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void pendingResultsBackpressureKeepsQueriesAndStopAvailable(boolean payload) throws Exception {
        Object component = component("NetworkProbeComponent", payload);
        Map<String, Object> task = task();
        registry(component).put("boundary", task);
        try {
            for (int i = 0; i < 3072; i++) add(component, task, Map.of("state", "open"));
            assertFalse(add(component, task, Map.of("state", "open")));
            Map<?, ?> snapshot = query(component);
            assertEquals("RUNNING", snapshot.get("status"));
            assertEquals(true, snapshot.get("backpressured"));
            assertEquals(3072, snapshot.get("pendingItems"));
            assertFalse(snapshot.containsKey("truncated"));
            call(component, "finishTask", new Class[]{Map.class, boolean.class}, task, true);
            assertEquals(3072, ((List<?>) task.get("observations")).size());
            call(component, "releaseTask", new Class[]{String.class}, "boundary");
            assertFalse(registry(component).containsKey("boundary"));
        } finally { stop(component, task); registry(component).remove("boundary"); }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void acknowledgementsReleaseBytePressureAndKeepCursorMonotonic(boolean payload) throws Exception {
        Object component = component("NetworkProbeComponent", payload);
        Map<String, Object> task = task();
        registry(component).put("boundary", task);
        try {
            add(component, task, Map.of("state", "open", "evidence", "x".repeat(2 * 1024 * 1024)));
            add(component, task, Map.of("state", "open", "evidence", "x".repeat(1024 * 1024)));
            assertEquals(true, query(component).get("backpressured"));
            assertFalse(add(component, task, Map.of("state", "open")));
            prepare(component, Map.of());
            call(component, "ackTask", new Class[]{String.class, long.class}, "boundary", 1L);
            assertTrue(add(component, task, Map.of("state", "open")));
            assertEquals(false, query(component).get("backpressured"));
            assertEquals("RUNNING", task.get("status"));
            call(component, "ackTask", new Class[]{String.class, long.class}, "boundary", 3L);
            assertEquals(0L, task.get("retainedBytes"));
            assertEquals(3L, query(component).get("nextCursor"));
            call(component, "ackTask", new Class[]{String.class, long.class}, "boundary", 1L);
            assertEquals(3L, query(component).get("nextCursor"));
            add(component, task, Map.of("evidence", "x".repeat(4 * 1024 * 1024)));
            assertEquals("STOPPED", task.get("status")); // A single oversized row fails rather than waiting forever.
            assertEquals(true, query(component).get("truncated"));
        } finally { stop(component, task); registry(component).remove("boundary"); }
    }

    private void stop(Object component, Map<String, Object> task) throws Exception {
        call(component, "finishTask", new Class[]{Map.class, boolean.class}, task, true);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void errorsHaveBoundedSummaryAndVisibleTotal(boolean payload) throws Exception {
        Object component = component("NetworkProbeComponent", payload);
        Map<String, Object> task = task();
        registry(component).put("boundary", task);
        try {
            for (int i = 0; i < 256; i++) {
                call(component, "addError", new Class[]{Map.class, String.class, String.class, String.class, String.class},
                        task, "synthetic", "TEST", "TEST", "synthetic error");
            }
            assertEquals(128, ((List<?>) task.get("errors")).size());
            Map<?, ?> result = query(component);
            assertEquals(256, result.get("errorCount"));
            assertEquals(true, result.get("errorsTruncated"));
            assertEquals("RUNNING", task.get("status"));
        } finally { registry(component).remove("boundary"); }
    }

    private Map<String, Object> task() {
        Map<String, Object> task = new HashMap<>();
        task.put("taskId", "boundary");
        task.put("status", "RUNNING");
        task.put("outcome", "RUNNING");
        task.put("observations", new ArrayList<>());
        task.put("errors", new ArrayList<>());
        task.put("targets", List.of());
        task.put("plan", Map.of());
        return task;
    }

    private Map<?, ?> query(Object component) throws Exception {
        Map<String, Object> result = prepare(component, Map.of());
        call(component, "queryTask", new Class[]{Map.class}, Map.of("taskId", "boundary", "maxItems", 512));
        return (Map<?, ?>) result.get("result");
    }

    private boolean add(Object component, Map<String, Object> task, Map<String, Object> observation) throws Exception {
        Object size = call(component, "observationSize", new Class[]{Map.class}, observation);
        synchronized (task) {
            return (Boolean) call(component, "offerObservation", new Class[]{Map.class, Map.class, long[].class}, task, observation, size);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> registry(Object component) throws Exception {
        Field field = component.getClass().getDeclaredField("TASKS");
        field.setAccessible(true);
        return (Map<String, Object>) field.get(null);
    }
}
