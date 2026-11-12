package org.leo.service.discovery;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.leo.service.discovery.NetworkProbePayloads.copyMap;

/**
 * Bounded delivery window: retained rows, consumer cursors and capacity belong together.
 * The owning task holds its monitor for all operations except the volatile pressure read,
 * keeping task metadata and evidence snapshots consistent without a second lock order.
 * Accepted rows transfer to the buffer; published map/list containers are detached copies.
 */
final class NetworkProbeResultBuffer {
    static final int PAGE_ITEMS = 512;
    static final int PAGE_BYTES = 1024 * 1024;
    private static final int MAX_PENDING_ITEMS = 4096;
    private static final long MAX_PENDING_BYTES = 8L * 1024 * 1024;

    private final List<Map<String, Object>> observations = new ArrayList<>();
    private final List<Map<String, Object>> errors = new ArrayList<>();
    private final Set<Map<String, Object>> seenErrors = new HashSet<>();
    private int observationOffset;
    private int errorOffset;
    private long pendingBytes;
    private volatile boolean backpressured;

    /** Returns the accepted row count, or -1 without accepting any part of an oversized backlog. */
    int offer(List<Map<String, Object>> page, List<Map<String, Object>> diagnostics,
              boolean applyBackpressure) {
        List<Map<String, Object>> freshErrors = diagnostics.stream()
                .filter(error -> !seenErrors.contains(error)).toList();
        long bytes = page.stream().mapToLong(NetworkProbeResultBuffer::estimateBytes).sum()
                + freshErrors.stream().mapToLong(NetworkProbeResultBuffer::estimateBytes).sum();
        int items = page.size() + freshErrors.size();
        if (items > MAX_PENDING_ITEMS || bytes > MAX_PENDING_BYTES)
            throw new IllegalStateException("节点单页结果超过服务端缓冲容量");
        if (pendingItems() + items > MAX_PENDING_ITEMS || pendingBytes + bytes > MAX_PENDING_BYTES) {
            if (applyBackpressure) backpressured = true;
            return -1;
        }
        observations.addAll(page);
        errors.addAll(freshErrors);
        seenErrors.addAll(freshErrors);
        pendingBytes += bytes;
        refreshPressure();
        return items;
    }

    /** Diagnostics already delivered for a child remain deduplicated even after consumer ACK. */
    void beginBatch() { seenErrors.clear(); }

    boolean acknowledge(long observationCursor, long errorCursor) {
        long before = (long) observationOffset + errorOffset;
        observationOffset = acknowledge(observations, observationOffset, observationCursor);
        errorOffset = acknowledge(errors, errorOffset, errorCursor);
        refreshPressure();
        return before != (long) observationOffset + errorOffset;
    }

    boolean isBackpressured() { return backpressured; }
    boolean hasPending() { return pendingItems() > 0; }
    int pendingItems() { return observations.size() + errors.size(); }
    long pendingBytes() { return pendingBytes; }

    void addFailure(Map<String, Object> error) {
        long bytes = estimateBytes(error);
        if (pendingItems() < MAX_PENDING_ITEMS && pendingBytes + bytes <= MAX_PENDING_BYTES) {
            errors.add(error);
            pendingBytes += bytes;
        }
    }

    Map<String, Object> snapshot() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("observations", snapshotRows(observations, 0, observations.size()));
        result.put("errors", snapshotRows(errors, 0, errors.size()));
        return result;
    }

    Map<String, Object> incrementalSnapshot(long observationCursor, long errorCursor) {
        int observationStart = boundedStart(observationCursor, observationOffset, observations.size());
        int errorStart = boundedStart(errorCursor, errorOffset, errors.size());
        int observationEnd = pageEnd(observations, observationStart, PAGE_ITEMS, PAGE_BYTES);
        int errorEnd = pageEnd(errors, errorStart,
                Math.max(1, PAGE_ITEMS - (observationEnd - observationStart)), PAGE_BYTES);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("cursor", Long.valueOf(observationCursor));
        result.put("nextCursor", Long.valueOf(observationOffset + observationEnd));
        result.put("errorCursor", Long.valueOf(errorCursor));
        result.put("nextErrorCursor", Long.valueOf(errorOffset + errorEnd));
        result.put("hasMore", Boolean.valueOf(observationEnd < observations.size() || errorEnd < errors.size()));
        result.put("incremental", Boolean.TRUE);
        result.put("observations", snapshotRows(observations, observationStart, observationEnd));
        result.put("errors", snapshotRows(errors, errorStart, errorEnd));
        return result;
    }

    private static List<Map<String, Object>> snapshotRows(List<Map<String, Object>> rows, int start, int end) {
        List<Map<String, Object>> result = new ArrayList<>(end - start);
        for (int index = start; index < end; index++) result.add(copyMap(rows.get(index)));
        return result;
    }

    private static int boundedStart(long cursor, int base, int size) {
        long requested = Math.max(cursor, (long) base);
        long start = requested - base;
        return (int) Math.max(0L, Math.min((long) size, start));
    }

    private static int pageEnd(List<Map<String, Object>> values, int start, int maxItems, int maxBytes) {
        int end = start;
        int bytes = 0;
        while (end < values.size() && end - start < maxItems) {
            int estimate = String.valueOf(values.get(end)).length();
            if (end > start && bytes + estimate > maxBytes) break;
            bytes += estimate;
            end++;
        }
        return end;
    }

    private int acknowledge(List<Map<String, Object>> values, int base, long cursor) {
        long bounded = Math.max((long) base, Math.min(cursor, (long) base + values.size()));
        int remove = (int) (bounded - base);
        if (remove > 0) {
            for (Map<String, Object> value : values.subList(0, remove)) pendingBytes -= estimateBytes(value);
            pendingBytes = Math.max(0L, pendingBytes);
            values.subList(0, remove).clear();
        }
        return (int) bounded;
    }

    private void refreshPressure() {
        int count = pendingItems();
        if (count >= MAX_PENDING_ITEMS * 3 / 4 || pendingBytes >= MAX_PENDING_BYTES * 3 / 4)
            backpressured = true;
        else if (count <= MAX_PENDING_ITEMS / 2 && pendingBytes <= MAX_PENDING_BYTES / 2)
            backpressured = false;
    }

    // Conservative string/container budget, without allocating serialized copies.
    private static long estimateBytes(Object value) {
        if (value instanceof String text) return 16L + 3L * text.length();
        if (value instanceof byte[] bytes) return 16L + bytes.length;
        long size = 32L;
        if (value instanceof Map<?, ?> map) {
            for (var entry : map.entrySet()) size += 32L + estimateBytes(entry.getKey()) + estimateBytes(entry.getValue());
        } else if (value instanceof List<?> list) {
            for (Object item : list) size += 8L + estimateBytes(item);
        }
        return size;
    }
}
