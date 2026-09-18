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
import static org.leo.core.component.ComponentParameterBoundaryTest.*;

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
    void pendingResultLimitStopsTaskAndPreservesPollableSnapshot(boolean payload) throws Exception {
        Object component = component("NetworkProbeComponent", payload);
        Map<String, Object> task = task();
        Map<String, Object> observation = Map.of("state", "open");
        registry(component).put("boundary", task);
        try {
            for (int i = 0; i <= 4096; i++) add(component, task, observation);
            assertEquals(4096, ((List<?>) task.get("observations")).size());
            assertEquals("STOPPED", task.get("status"));
            assertEquals("CANCELLED", task.get("outcome"));
            assertFalse(task.containsKey("targets"));
            assertFalse(task.containsKey("plan"));
            Map<?, ?> snapshot = query(component);
            assertEquals(true, snapshot.get("truncated"));
            assertNotNull(snapshot.get("truncateReason"));
            assertFalse(((List<?>) snapshot.get("observations")).isEmpty());
            assertEquals("RESULT_LIMIT", ((Map<?, ?>) ((List<?>) snapshot.get("errors")).get(0)).get("errorCode"));
            call(component, "releaseTask", new Class[]{String.class}, "boundary");
            assertFalse(registry(component).containsKey("boundary"));
        } finally { registry(component).remove("boundary"); }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void acknowledgementsReleaseBudgetAndKeepCursorMonotonic(boolean payload) throws Exception {
        Object component = component("NetworkProbeComponent", payload);
        Map<String, Object> task = task();
        registry(component).put("boundary", task);
        Map<String, Object> large = Map.of("state", "open", "evidence", "x".repeat(2 * 1024 * 1024));
        try {
            add(component, task, large);
            prepare(component, Map.of());
            call(component, "ackTask", new Class[]{String.class, long.class}, "boundary", 1L);
            assertEquals(0L, task.get("retainedChars"));
            add(component, task, large);
            assertEquals("RUNNING", task.get("status"));
            assertEquals(2L, query(component).get("nextCursor"));
            add(component, task, large);
            assertEquals("STOPPED", task.get("status"));
            assertEquals(1, ((List<?>) task.get("observations")).size());
            assertEquals(true, query(component).get("truncated"));
        } finally { registry(component).remove("boundary"); }
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

    private void add(Object component, Map<String, Object> task, Map<String, Object> observation) throws Exception {
        call(component, "addObservation", new Class[]{Map.class, Map.class}, task, observation);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> registry(Object component) throws Exception {
        Field field = component.getClass().getDeclaredField("TASKS");
        field.setAccessible(true);
        return (Map<String, Object>) field.get(null);
    }
}
