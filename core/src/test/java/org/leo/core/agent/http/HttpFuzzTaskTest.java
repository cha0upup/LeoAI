package org.leo.core.agent.http;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class HttpFuzzTaskTest {

    @Test
    void recordsCompletionWithoutDependingOnAnExternalQuery() {
        HttpFuzzTask task = new HttpFuzzTask("task-1", 2, 100L);

        task.record(Map.of("index", 0));
        assertEquals(HttpFuzzTask.RUNNING, task.snapshot().get("status"));

        task.record(Map.of("index", 1));
        Map<String, Object> snapshot = task.snapshot();
        assertEquals(HttpFuzzTask.FINISHED, snapshot.get("status"));
        assertEquals(2, snapshot.get("completed"));
        assertEquals(2, ((java.util.List<?>) snapshot.get("results")).size());
        assertNotNull(snapshot.get("finishedAt"));
    }
}
