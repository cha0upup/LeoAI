package org.leo.ai.runtime;

import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAccumulator;
import java.util.concurrent.atomic.LongAdder;

/**
 * 进程内 Turn 终态指标。详细单次轨迹持久化在 ai_runs.trace_json，
 * 本组件只保留聚合计数和有限的最近快照。
 */
@Component
public class AiTurnTelemetryRegistry {

    private static final int RECENT_LIMIT = 100;

    private final ConcurrentHashMap<String, LongAdder> outcomes =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LongAdder> errorCategories =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LongAdder> runtimeEvents =
            new ConcurrentHashMap<>();
    private final LongAdder completedTurns = new LongAdder();
    private final LongAdder totalDurationMs = new LongAdder();
    private final LongAccumulator maxDurationMs =
            new LongAccumulator(Long::max, 0L);
    private final ArrayDeque<Map<String, Object>> recent = new ArrayDeque<>();

    public void record(AiTurnTrace trace) {
        if (trace == null || !trace.markRecorded()) return;
        String outcome = trace.outcome() != null ? trace.outcome() : "failed";
        long duration = trace.durationMillis();
        outcomes.computeIfAbsent(outcome, ignored -> new LongAdder()).increment();
        if (trace.errorCategory() != null) {
            errorCategories.computeIfAbsent(
                    trace.errorCategory(), ignored -> new LongAdder()).increment();
        }
        completedTurns.increment();
        totalDurationMs.add(duration);
        maxDurationMs.accumulate(duration);
        synchronized (recent) {
            recent.addFirst(trace.snapshot());
            while (recent.size() > RECENT_LIMIT) {
                recent.removeLast();
            }
        }
    }

    public Map<String, Object> snapshot() {
        long count = completedTurns.sum();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("completedTurns", count);
        result.put("averageDurationMs",
                count > 0 ? totalDurationMs.sum() / count : 0L);
        result.put("maxDurationMs", maxDurationMs.get());
        result.put("outcomes", counterSnapshot(outcomes));
        result.put("errorCategories", counterSnapshot(errorCategories));
        result.put("runtimeEvents", counterSnapshot(runtimeEvents));
        synchronized (recent) {
            result.put("recent", new ArrayList<>(recent));
        }
        return result;
    }

    public void recordRuntimeEvent(String event) {
        if (event == null || event.isBlank()) return;
        runtimeEvents.computeIfAbsent(event, ignored -> new LongAdder()).increment();
    }

    private Map<String, Long> counterSnapshot(
            ConcurrentHashMap<String, LongAdder> counters) {
        Map<String, Long> result = new TreeMap<>();
        counters.forEach((key, counter) -> result.put(key, counter.sum()));
        return result;
    }
}
