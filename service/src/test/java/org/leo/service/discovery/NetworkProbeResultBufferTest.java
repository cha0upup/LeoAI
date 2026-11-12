package org.leo.service.discovery;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class NetworkProbeResultBufferTest {
    @Test
    void independentConsumerCursorsClampStaleAndFutureAcksAndReleaseTheExactBacklog() {
        var buffer = new NetworkProbeResultBuffer();
        buffer.offer(rows(3), List.of(Map.of("error", "one"), Map.of("error", "two")), true);
        long before = buffer.pendingBytes();
        assertTrue(buffer.acknowledge(1, 1));
        assertEquals(3, buffer.pendingItems());
        assertTrue(buffer.pendingBytes() > 0 && buffer.pendingBytes() < before);
        long remaining = buffer.pendingBytes();
        assertFalse(buffer.acknowledge(-1, 0));
        assertEquals(remaining, buffer.pendingBytes());
        var staleQuery = buffer.incrementalSnapshot(0, 0);
        assertEquals(0L, staleQuery.get("cursor"));
        assertEquals(3L, staleQuery.get("nextCursor"));
        assertEquals(2L, staleQuery.get("nextErrorCursor"));
        assertEquals(2, ((List<?>) staleQuery.get("observations")).size());
        assertTrue(buffer.acknowledge(Long.MAX_VALUE, Long.MAX_VALUE));
        assertEquals(0, buffer.pendingItems());
        assertEquals(0L, buffer.pendingBytes());
        assertFalse(buffer.acknowledge(Long.MAX_VALUE, Long.MAX_VALUE));
        buffer.offer(List.of(Map.of("index", 3)), List.of(Map.of("error", "three")), true);
        var next = buffer.incrementalSnapshot(3, 2);
        assertEquals(4L, next.get("nextCursor"));
        assertEquals(3L, next.get("nextErrorCursor"));
    }

    @Test
    void diagnosticDeduplicationSurvivesAckButResetsForTheNextChild() {
        var buffer = new NetworkProbeResultBuffer();
        var error = Map.<String, Object>of("error", "diagnostic");
        assertEquals(1, buffer.offer(List.of(), List.of(error), true));
        buffer.acknowledge(0, 1);
        assertEquals(0, buffer.offer(List.of(), List.of(error), true));
        assertFalse(buffer.hasPending());
        buffer.beginBatch();
        assertEquals(1, buffer.offer(List.of(), List.of(error), true));
        assertEquals(2L, buffer.incrementalSnapshot(0, 1).get("nextErrorCursor"));
        buffer.acknowledge(0, 2);
        assertEquals(0L, buffer.pendingBytes());
    }

    @Test
    void fullCapacityDefersAnEntirePageWithoutConsumingItsDiagnostics() {
        var buffer = new NetworkProbeResultBuffer();
        buffer.offer(rows(4096), List.of(), true);
        long before = buffer.pendingBytes();
        var page = List.<Map<String, Object>>of(Map.of("index", 4096));
        var errors = List.<Map<String, Object>>of(Map.of("error", "deferred"));
        assertEquals(-1, buffer.offer(page, errors, true));
        assertEquals(4096, buffer.pendingItems());
        assertEquals(before, buffer.pendingBytes());
        assertTrue(buffer.isBackpressured());
        buffer.acknowledge(2047, 0);
        assertTrue(buffer.isBackpressured());
        buffer.acknowledge(2048, 0);
        assertFalse(buffer.isBackpressured());
        assertEquals(2, buffer.offer(page, errors, true));
        assertEquals(1, ((List<?>) buffer.snapshot().get("errors")).size());
        buffer.acknowledge(Long.MAX_VALUE, Long.MAX_VALUE);
        assertEquals(0L, buffer.pendingBytes());
    }

    @Test
    void bytePressureUsesHighAndLowWaterMarksAndStopDrainDoesNotAddPressure() {
        var buffer = new NetworkProbeResultBuffer();
        var large = List.<Map<String, Object>>of(Map.of("body", "x".repeat(800_000)));
        buffer.offer(large, List.of(), true);
        buffer.offer(large, List.of(), true);
        assertFalse(buffer.isBackpressured());
        long before = buffer.pendingBytes();
        assertEquals(-1, buffer.offer(List.of(large.get(0), large.get(0)), List.of(), false));
        assertFalse(buffer.isBackpressured());
        assertEquals(before, buffer.pendingBytes());
        buffer.offer(large, List.of(), true);
        assertTrue(buffer.isBackpressured());
        buffer.acknowledge(1, 0);
        assertTrue(buffer.isBackpressured());
        buffer.acknowledge(2, 0);
        assertFalse(buffer.isBackpressured());
        buffer.acknowledge(3, 0);
        assertEquals(0L, buffer.pendingBytes());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectsAnIndividuallyOversizedPageWithoutChangingTheWindow(boolean bytes) {
        var buffer = new NetworkProbeResultBuffer();
        List<Map<String, Object>> page = bytes ? List.of(Map.of("body", "x".repeat(3_000_000))) : rows(4097);
        assertThrows(IllegalStateException.class, () -> buffer.offer(page, List.of(), true));
        assertEquals(0, buffer.pendingItems());
        assertEquals(0L, buffer.pendingBytes());
        assertFalse(buffer.isBackpressured());
    }

    @Test
    void pagedReadsKeepDiagnosticsMovingWithoutConsumingOrRepeatingTheWindow() {
        var buffer = new NetworkProbeResultBuffer();
        buffer.offer(rows(600), List.of(Map.of("error", "one"), Map.of("error", "two")), true);
        long before = buffer.pendingBytes();
        var first = buffer.incrementalSnapshot(0, 0);
        assertEquals(512, ((List<?>) first.get("observations")).size());
        assertEquals(1, ((List<?>) first.get("errors")).size());
        assertEquals(true, first.get("hasMore"));
        var second = buffer.incrementalSnapshot(512, 1);
        assertEquals(88, ((List<?>) second.get("observations")).size());
        assertEquals(1, ((List<?>) second.get("errors")).size());
        assertEquals(false, second.get("hasMore"));
        assertEquals(before, buffer.pendingBytes());
        assertEquals(first, buffer.incrementalSnapshot(0, 0));
        buffer.acknowledge(600, 2);
        assertEquals(0L, buffer.pendingBytes());
    }

    @Test
    @SuppressWarnings("unchecked")
    void publishedContainersDoNotChangeRetainedRowsOrTheirCapacityAndDiagnosticIdentity() {
        var buffer = new NetworkProbeResultBuffer();
        var error = new LinkedHashMap<String, Object>(Map.of("error", "diagnostic"));
        var observation = Map.<String, Object>of("index", 0,
                "evidence", Map.of("tags", new ArrayList<>(List.of("original"))));
        buffer.offer(List.of(observation), List.of(error), true);
        long before = buffer.pendingBytes();
        var full = buffer.snapshot();
        var row = (Map<String, Object>) ((List<?>) full.get("observations")).get(0);
        row.put("padding", "x".repeat(100_000));
        ((List<?>) ((Map<?, ?>) row.get("evidence")).get("tags")).clear();
        ((Map<String, Object>) ((List<?>) full.get("errors")).get(0)).put("error", "changed");
        var page = buffer.incrementalSnapshot(0, 0);
        assertFalse(((Map<?, ?>) ((List<?>) page.get("observations")).get(0)).containsKey("padding"));
        ((List<?>) page.get("observations")).clear();
        assertEquals(0, buffer.offer(List.of(), List.of(error), true));
        assertEquals(before, buffer.pendingBytes());
        assertEquals(2, buffer.pendingItems());
        buffer.acknowledge(1, 1);
        assertEquals(0L, buffer.pendingBytes());
    }

    @Test
    void localFailureDiagnosticsRespectTheSameHardCapacityWithoutDiscardingEvidence() {
        var buffer = new NetworkProbeResultBuffer();
        buffer.offer(rows(4096), List.of(), true);
        buffer.addFailure(Map.of("error", "no room"));
        assertEquals(4096, buffer.pendingItems());
        assertTrue(((List<?>) buffer.snapshot().get("errors")).isEmpty());
        buffer.acknowledge(Long.MAX_VALUE, 0);
        buffer.addFailure(Map.of("error", "orchestration failure"));
        assertEquals(1, buffer.pendingItems());
        buffer.acknowledge(Long.MAX_VALUE, Long.MAX_VALUE);
        assertEquals(0L, buffer.pendingBytes());
    }

    private static List<Map<String, Object>> rows(int count) {
        return IntStream.range(0, count).mapToObj(index -> Map.<String, Object>of("index", index)).toList();
    }
}
