package org.leo.web.service;

import jakarta.annotation.PreDestroy;
import org.leo.core.puppet.capability.NetworkProbeCapable;
import org.leo.web.exception.ApiException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs one logical network probe as a sequence of bounded node-side tasks.
 *
 * <p>A node accepts at most 128 targets per task. This service owns that
 * transport detail, aggregates progress and evidence, and releases every
 * terminal child task after collecting its final snapshot.</p>
 */
@Service
public final class NetworkProbeOrchestrationService implements AutoCloseable {

    private static final int NODE_BATCH_SIZE = 128;
    private static final int MAX_ACTIVE_TASKS = 36;
    private static final int RESULT_PAGE_ITEMS = 256;
    private static final int RESULT_PAGE_BYTES = 512 * 1024;
    private static final long RPC_TIMEOUT_MS = 15_000L;
    private static final long TASK_TTL_MS = 30L * 60L * 1000L;
    private static final long DEFAULT_POLL_INTERVAL_MS = 100L;

    private final Map<String, LogicalTask> tasks = new java.util.concurrent.ConcurrentHashMap<>();
    private final ExecutorService executor;
    private final ExecutorService rpcExecutor;
    private final long pollIntervalMs;

    public NetworkProbeOrchestrationService() {
        this(new ThreadPoolExecutor(
                4,
                4,
                60L,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(32),
                daemonThreadFactory(),
                new ThreadPoolExecutor.AbortPolicy()), DEFAULT_POLL_INTERVAL_MS,
                new ThreadPoolExecutor(
                8,
                8,
                60L,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(64),
                daemonThreadFactory(),
                new ThreadPoolExecutor.CallerRunsPolicy()));
    }

    NetworkProbeOrchestrationService(ExecutorService executor, long pollIntervalMs) {
        this(executor, pollIntervalMs, new ThreadPoolExecutor(
                4,
                4,
                60L,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(32),
                daemonThreadFactory(),
                new ThreadPoolExecutor.CallerRunsPolicy()));
    }

    NetworkProbeOrchestrationService(ExecutorService executor, long pollIntervalMs,
                                     ExecutorService rpcExecutor) {
        this.executor = executor;
        this.rpcExecutor = rpcExecutor;
        this.pollIntervalMs = Math.max(10L, pollIntervalMs);
    }

    public Map<String, Object> start(String sessionId, NetworkProbeCapable node, Map<String, Object> plan) {
        if (sessionId == null || sessionId.isBlank()) throw new IllegalArgumentException("sessionId不能为空");
        if (node == null) throw new IllegalArgumentException("网络探测节点不能为空");
        List<Map<String, Object>> targets = mapList(plan == null ? null : plan.get("targets"));
        if (targets.isEmpty()) throw new IllegalArgumentException("plan.targets不能为空");
        cleanup();
        String taskId = UUID.randomUUID().toString();
        LogicalTask task = new LogicalTask(taskId, sessionId.trim(), node, plan, targets);
        synchronized (tasks) {
            long activeTaskCount = tasks.values().stream()
                    .filter(candidate -> candidate.finished.getCount() > 0L)
                    .count();
            if (activeTaskCount >= MAX_ACTIVE_TASKS) {
                throw new IllegalStateException("服务端网络探测任务已达上限");
            }
            tasks.put(taskId, task);
        }
        try {
            task.future = executor.submit(() -> run(task));
        } catch (RuntimeException error) {
            tasks.remove(taskId);
            throw new IllegalStateException("服务端网络探测调度队列已满", error);
        }
        return response(Map.of("taskId", taskId));
    }

    public Map<String, Object> query(String sessionId, String taskId) {
        cleanup();
        LogicalTask task = requireTask(sessionId, taskId);
        return response(Map.of("result", snapshot(task)));
    }

    Map<String, Object> querySince(String sessionId, String taskId,
                                   long observationCursor, long errorCursor) {
        cleanup();
        LogicalTask task = requireTask(sessionId, taskId);
        return response(Map.of("result", incrementalSnapshot(task, observationCursor, errorCursor)));
    }

    void acknowledge(String sessionId, String taskId, long observationCursor, long errorCursor) {
        LogicalTask task = requireTask(sessionId, taskId);
        synchronized (task.monitor) {
            acknowledge(task.observations, true, task, observationCursor);
            acknowledge(task.errors, false, task, errorCursor);
        }
    }

    public Map<String, Object> pause(String sessionId, String taskId) {
        LogicalTask task = requireTask(sessionId, taskId);
        String childTaskId;
        synchronized (task.monitor) {
            if (task.terminal()) return response(Map.of("status", "STOPPED"));
            task.status = "PAUSED";
            childTaskId = task.childTaskId;
        }
        if (childTaskId != null) controlChild(task, childTaskId, ChildControl.PAUSE);
        return response(Map.of("status", "PAUSED"));
    }

    public Map<String, Object> resume(String sessionId, String taskId) {
        LogicalTask task = requireTask(sessionId, taskId);
        String childTaskId;
        synchronized (task.monitor) {
            if (task.terminal()) return response(Map.of("status", "STOPPED"));
            task.status = "RUNNING";
            childTaskId = task.childTaskId;
            task.monitor.notifyAll();
        }
        if (childTaskId != null) controlChild(task, childTaskId, ChildControl.RESUME);
        return response(Map.of("status", "RUNNING"));
    }

    public Map<String, Object> stop(String sessionId, String taskId) {
        LogicalTask task = requireTask(sessionId, taskId);
        String childTaskId;
        synchronized (task.monitor) {
            if (!task.terminal()) {
                task.cancelRequested = true;
                task.status = "STOPPED";
                task.outcome = "CANCELLED";
                task.finishedAt = System.currentTimeMillis();
                task.monitor.notifyAll();
            }
            childTaskId = task.childTaskId;
        }
        if (childTaskId != null) controlChild(task, childTaskId, ChildControl.STOP);
        try {
            task.finished.await(5L, TimeUnit.SECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
        return response(Map.of("status", "STOPPED"));
    }

    private void run(LogicalTask task) {
        try {
            for (int offset = 0; offset < task.targets.size(); offset += NODE_BATCH_SIZE) {
                if (!awaitRunnable(task)) break;
                int end = Math.min(task.targets.size(), offset + NODE_BATCH_SIZE);
                Map<String, Object> childPlan = childPlan(task.plan, task.targets.subList(offset, end));
                Map<String, Object> started = invoke(task, () -> task.node.startNetworkProbe(childPlan));
                String childTaskId = text(started.get("taskId"));
                if (childTaskId.isEmpty()) throw new IllegalStateException("节点未返回网络探测子任务 ID");
                synchronized (task.monitor) {
                    task.childTaskId = childTaskId;
                    task.batchIndex++;
                }
                if (task.cancelRequested) controlChild(task, childTaskId, ChildControl.STOP);
                else if ("PAUSED".equals(task.status)) controlChild(task, childTaskId, ChildControl.PAUSE);

                Map<String, Object> finalSnapshot = pollChild(task, childTaskId);
                mergeCompletedBatch(task, childTaskId, finalSnapshot);
                releaseChild(task, childTaskId);
                String childOutcome = text(finalSnapshot.get("outcome")).toUpperCase(java.util.Locale.ROOT);
                if ("FAILED".equals(childOutcome)) {
                    fail(task, "节点网络探测子任务失败");
                    break;
                }
                if ("CANCELLED".equals(childOutcome) && !task.cancelRequested) {
                    synchronized (task.monitor) {
                        task.status = "STOPPED";
                        task.outcome = "CANCELLED";
                        task.finishedAt = System.currentTimeMillis();
                        task.monitor.notifyAll();
                    }
                    break;
                }
                if (task.cancelRequested) break;
            }
            synchronized (task.monitor) {
                if (!task.cancelRequested && !"FAILED".equals(task.outcome)) {
                    if ("CANCELLED".equals(task.outcome)) return;
                    task.status = "STOPPED";
                    task.outcome = "COMPLETED";
                    task.finishedAt = System.currentTimeMillis();
                }
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            fail(task, "服务端网络探测编排已中断");
            stopAndReleaseActiveChild(task);
        } catch (Exception error) {
            fail(task, messageOf(error));
            stopAndReleaseActiveChild(task);
        } finally {
            task.finished.countDown();
        }
    }

    private Map<String, Object> pollChild(LogicalTask task, String childTaskId) throws Exception {
        Map<String, Object> latest = Map.of();
        long cursor = 0L;
        long deliveredCursor = 0L;
        while (true) {
            if (task.cancelRequested) controlChild(task, childTaskId, ChildControl.STOP);
            final long requestedCursor = cursor;
            Map<String, Object> queried = invoke(task, () -> task.node.queryNetworkProbe(
                    childTaskId, requestedCursor, RESULT_PAGE_ITEMS, RESULT_PAGE_BYTES, true));
            latest = map(queried.get("result"));
            if (latest.isEmpty()) throw new IllegalStateException("节点网络探测子任务结果为空");
            boolean incremental = Boolean.TRUE.equals(latest.get("incremental"));
            synchronized (task.monitor) {
                if (childTaskId.equals(task.childTaskId)) {
                    if (incremental) {
                        long pageCursor = Math.max(0L, longValue(latest.get("cursor"), requestedCursor));
                        long pageNextCursor = Math.max(pageCursor,
                                longValue(latest.get("nextCursor"), pageCursor));
                        // A lost ack causes the node to replay the same cursor page. Keep
                        // the page visible to the caller, but append it only once.
                        if (pageNextCursor > deliveredCursor) {
                            task.observations.addAll(mapListOrEmpty(latest.get("observations")));
                            deliveredCursor = pageNextCursor;
                        }
                        appendUnique(task.errors, mapListOrEmpty(latest.get("errors")));
                        Map<String, Object> metadata = new LinkedHashMap<>(latest);
                        metadata.remove("observations");
                        metadata.remove("errors");
                        task.activeSnapshot = metadata;
                    } else {
                        task.activeSnapshot = latest;
                    }
                }
            }
            if (incremental) {
                long nextCursor = Math.max(cursor, longValue(latest.get("nextCursor"), cursor));
                if (nextCursor > cursor) {
                    final long acknowledgedCursor = nextCursor;
                    invoke(task, () -> task.node.ackNetworkProbe(childTaskId, acknowledgedCursor));
                    cursor = nextCursor;
                }
                if ("STOPPED".equalsIgnoreCase(text(latest.get("status")))
                        && !Boolean.TRUE.equals(latest.get("hasMore"))) return latest;
            } else if ("STOPPED".equalsIgnoreCase(text(latest.get("status")))) {
                return latest;
            }
            Thread.sleep(pollIntervalMs);
        }
    }

    private boolean awaitRunnable(LogicalTask task) throws InterruptedException {
        synchronized (task.monitor) {
            while ("PAUSED".equals(task.status) && !task.cancelRequested) {
                task.monitor.wait(250L);
            }
            return !task.cancelRequested;
        }
    }

    private void mergeCompletedBatch(LogicalTask task, String childTaskId, Map<String, Object> childSnapshot) {
        synchronized (task.monitor) {
            if (!childTaskId.equals(task.childTaskId)) return;
            task.completedTargets += integer(childSnapshot.get("completed"), 0);
            if (!Boolean.TRUE.equals(childSnapshot.get("incremental"))) {
                task.observations.addAll(mapListOrEmpty(childSnapshot.get("observations")));
                appendUnique(task.errors, mapListOrEmpty(childSnapshot.get("errors")));
            }
            task.activeSnapshot = Map.of();
            task.childTaskId = null;
        }
    }

    private void stopAndReleaseActiveChild(LogicalTask task) {
        String childTaskId;
        synchronized (task.monitor) {
            childTaskId = task.childTaskId;
        }
        if (childTaskId == null) return;
        controlChild(task, childTaskId, ChildControl.STOP);
        try {
            Map<String, Object> finalSnapshot = pollChild(task, childTaskId);
            mergeCompletedBatch(task, childTaskId, finalSnapshot);
        } catch (Exception ignored) {
        }
        releaseChild(task, childTaskId);
    }

    private void controlChild(LogicalTask task, String childTaskId, ChildControl control) {
        try {
            invoke(task, () -> switch (control) {
                case PAUSE -> task.node.pauseNetworkProbe(childTaskId);
                case RESUME -> task.node.resumeNetworkProbe(childTaskId);
                case STOP -> task.node.stopNetworkProbe(childTaskId);
            });
        } catch (Exception ignored) {
            // A child may finish between its latest snapshot and the control request.
        }
    }

    private void releaseChild(LogicalTask task, String childTaskId) {
        try {
            invoke(task, () -> task.node.releaseNetworkProbe(childTaskId));
        } catch (Exception ignored) {
            // Node-side TTL cleanup remains the fallback if the transport disappears.
        }
        tasks.remove(childTaskId);
    }

    private Map<String, Object> invoke(LogicalTask task, NodeCall call) throws Exception {
        Future<Map<String, Object>> future;
        try {
            future = rpcExecutor.submit(() -> {
                synchronized (task.nodeCallMonitor) {
                    Map<String, Object> response = call.invoke();
                    int code = integer(response == null ? null : response.get("code"), 500);
                    if (response == null || code != 200) {
                        throw new IllegalStateException(response == null
                                ? "节点网络探测调用返回为空"
                                : text(response.getOrDefault("msg", "节点网络探测调用失败")));
                    }
                    return response;
                }
            });
        } catch (RuntimeException error) {
            throw new IllegalStateException("节点网络探测 RPC 调度队列已满", error);
        }
        try {
            return future.get(RPC_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException error) {
            future.cancel(true);
            throw new IllegalStateException("节点网络探测 RPC 超时", error);
        } catch (ExecutionException error) {
            Throwable cause = error.getCause();
            if (cause instanceof Exception exception) throw exception;
            throw error;
        }
    }

    private Map<String, Object> snapshot(LogicalTask task) {
        synchronized (task.monitor) {
            Map<String, Object> active = task.activeSnapshot;
            int completed = Math.min(task.targets.size(), task.completedTargets + integer(active.get("completed"), 0));
            List<Map<String, Object>> observations = new ArrayList<>(task.observations);
            observations.addAll(mapListOrEmpty(active.get("observations")));
            List<Map<String, Object>> errors = new ArrayList<>(task.errors);
            errors.addAll(mapListOrEmpty(active.get("errors")));

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("taskId", task.taskId);
            result.put("scanKind", "network-probe");
            result.put("status", task.status);
            result.put("outcome", task.outcome);
            result.put("total", Integer.valueOf(task.targets.size()));
            result.put("completed", Integer.valueOf(completed));
            result.put("progress", Integer.valueOf(task.targets.isEmpty() ? 0 : completed * 100 / task.targets.size()));
            result.put("batchCount", Integer.valueOf(task.batchCount));
            result.put("batchIndex", Integer.valueOf(task.batchIndex));
            result.put("targets", new ArrayList<>(task.targets));
            result.put("plan", publicPlan(task.plan));
            result.put("observations", observations);
            result.put("errors", errors);
            result.put("createdAt", Long.valueOf(task.createdAt));
            if (task.finishedAt > 0L) result.put("finishedAt", Long.valueOf(task.finishedAt));
            return result;
        }
    }

    private Map<String, Object> incrementalSnapshot(LogicalTask task,
                                                    long observationCursor, long errorCursor) {
        synchronized (task.monitor) {
            int observationBase = task.observationOffset;
            int observationStart = boundedStart(observationCursor, observationBase, task.observations.size());
            int errorBase = task.errorOffset;
            int errorStart = boundedStart(errorCursor, errorBase, task.errors.size());
            int observationEnd = pageEnd(task.observations, observationStart, RESULT_PAGE_ITEMS, RESULT_PAGE_BYTES);
            int errorEnd = pageEnd(task.errors, errorStart,
                    Math.max(1, RESULT_PAGE_ITEMS - (observationEnd - observationStart)), RESULT_PAGE_BYTES);
            Map<String, Object> active = task.activeSnapshot;
            int completed = Math.min(task.targets.size(),
                    task.completedTargets + integer(active.get("completed"), 0));
            List<Map<String, Object>> observations = new ArrayList<>(
                    task.observations.subList(observationStart, observationEnd));
            List<Map<String, Object>> errors = new ArrayList<>(
                    task.errors.subList(errorStart, errorEnd));
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("taskId", task.taskId);
            result.put("scanKind", "network-probe");
            result.put("status", task.status);
            result.put("outcome", task.outcome);
            result.put("total", Integer.valueOf(task.targets.size()));
            result.put("completed", Integer.valueOf(completed));
            result.put("progress", Integer.valueOf(task.targets.isEmpty()
                    ? 0 : completed * 100 / task.targets.size()));
            result.put("batchCount", Integer.valueOf(task.batchCount));
            result.put("batchIndex", Integer.valueOf(task.batchIndex));
            result.put("cursor", Long.valueOf(observationCursor));
            result.put("nextCursor", Long.valueOf(observationBase + observationEnd));
            result.put("errorCursor", Long.valueOf(errorCursor));
            result.put("nextErrorCursor", Long.valueOf(errorBase + errorEnd));
            result.put("hasMore", Boolean.valueOf(observationEnd < task.observations.size()
                    || errorEnd < task.errors.size()));
            result.put("incremental", Boolean.TRUE);
            result.put("observations", observations);
            result.put("errors", errors);
            result.put("createdAt", Long.valueOf(task.createdAt));
            if (task.finishedAt > 0L) result.put("finishedAt", Long.valueOf(task.finishedAt));
            return result;
        }
    }

    private static int boundedStart(long cursor, int base, int size) {
        long requested = Math.max(cursor, (long) base);
        long start = requested - base;
        return (int) Math.max(0L, Math.min((long) size, start));
    }

    private static int pageEnd(List<Map<String, Object>> values, int start,
                               int maxItems, int maxBytes) {
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

    private static void acknowledge(List<Map<String, Object>> values, boolean observations,
                                    LogicalTask task, long cursor) {
        int base = observations ? task.observationOffset : task.errorOffset;
        long bounded = Math.max((long) base, Math.min(cursor, (long) base + values.size()));
        int remove = (int) (bounded - base);
        if (remove > 0) {
            values.subList(0, remove).clear();
            if (observations) task.observationOffset = (int) bounded;
            else task.errorOffset = (int) bounded;
        }
    }

    private void fail(LogicalTask task, String message) {
        synchronized (task.monitor) {
            if (task.cancelRequested) {
                task.status = "STOPPED";
                task.outcome = "CANCELLED";
                if (task.finishedAt == 0L) task.finishedAt = System.currentTimeMillis();
                task.monitor.notifyAll();
                return;
            }
            task.status = "STOPPED";
            task.outcome = "FAILED";
            task.finishedAt = System.currentTimeMillis();
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("stage", "orchestration");
            error.put("errorCode", "ORCHESTRATION");
            error.put("error", message);
            task.errors.add(error);
            task.monitor.notifyAll();
        }
    }

    private LogicalTask requireTask(String sessionId, String taskId) {
        if (sessionId == null || sessionId.isBlank()) throw new IllegalArgumentException("sessionId不能为空");
        if (taskId == null || taskId.isBlank()) throw new IllegalArgumentException("taskId不能为空");
        LogicalTask task = tasks.get(taskId.trim());
        if (task == null || !task.sessionId.equals(sessionId.trim())) {
            throw ApiException.notFound("网络探测任务不存在或不属于当前会话");
        }
        return task;
    }

    private void cleanup() {
        long now = System.currentTimeMillis();
        tasks.entrySet().removeIf(entry -> {
            LogicalTask task = entry.getValue();
            return task.terminal() && task.finishedAt > 0L && now - task.finishedAt > TASK_TTL_MS;
        });
    }

    private static Map<String, Object> childPlan(Map<String, Object> source, List<Map<String, Object>> targets) {
        Map<String, Object> plan = wireMap(source);
        List<Map<String, Object>> safeTargets = new ArrayList<>();
        for (Map<String, Object> target : targets) safeTargets.add(wireMap(target));
        plan.put("targets", safeTargets);
        if (source.get("limits") instanceof Map<?, ?> rawLimits) {
            Map<String, Object> limits = map(rawLimits);
            int threads = Math.max(1, Math.min(targets.size(), integer(limits.get("threads"), 8)));
            limits.put("threads", Integer.valueOf(threads));
            plan.put("limits", limits);
        }
        return plan;
    }

    private static Map<String, Object> publicPlan(Map<String, Object> source) {
        Map<String, Object> plan = new LinkedHashMap<>();
        if (source.get("stages") instanceof List<?> stages) plan.put("stages", new ArrayList<>(stages));
        if (source.get("limits") instanceof Map<?, ?> rawLimits) {
            Map<String, Object> limits = map(rawLimits);
            plan.putAll(limits);
        }
        return plan;
    }

    private static Map<String, Object> response(Map<String, Object> values) {
        Map<String, Object> response = new LinkedHashMap<>(values);
        response.put("code", Integer.valueOf(200));
        return response;
    }

    private static List<Map<String, Object>> mapList(Object value) {
        if (!(value instanceof List<?> list)) return new ArrayList<>();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> itemMap)) throw new IllegalArgumentException("plan.targets中的项目必须是对象");
            result.add(map(itemMap));
        }
        return result;
    }

    private static List<Map<String, Object>> mapListOrEmpty(Object value) {
        try {
            return mapList(value);
        } catch (IllegalArgumentException ignored) {
            return new ArrayList<>();
        }
    }

    private static Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> source)) return new LinkedHashMap<>();
        return wireMap(source);
    }

    private static Map<String, Object> wireMap(Map<?, ?> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (entry.getKey() != null) result.put(String.valueOf(entry.getKey()), wireValue(entry.getValue()));
        }
        return result;
    }

    private static Object wireValue(Object value) {
        if (value instanceof Map<?, ?> source) return wireMap(source);
        if (value instanceof Set<?> source) {
            Set<Object> result = new LinkedHashSet<>();
            for (Object item : source) result.add(wireValue(item));
            return result;
        }
        if (value instanceof Collection<?> source) {
            List<Object> result = new ArrayList<>();
            for (Object item : source) result.add(wireValue(item));
            return result;
        }
        return value;
    }

    private static void appendUnique(List<Map<String, Object>> target, List<Map<String, Object>> additions) {
        for (Map<String, Object> addition : additions) {
            if (!target.contains(addition)) target.add(addition);
        }
    }

    private static int integer(Object value, int fallback) {
        if (value instanceof Number number) return number.intValue();
        try {
            return value == null ? fallback : Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static long longValue(Object value, long fallback) {
        if (value instanceof Number number) return number.longValue();
        try {
            return value == null ? fallback : Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String messageOf(Exception error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    private static ThreadFactory daemonThreadFactory() {
        AtomicInteger sequence = new AtomicInteger();
        return task -> {
            Thread thread = new Thread(task, "network-probe-orchestrator-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    @Override
    @PreDestroy
    public void close() {
        for (LogicalTask task : tasks.values()) {
            synchronized (task.monitor) {
                task.cancelRequested = true;
                task.monitor.notifyAll();
            }
            if (task.future != null) task.future.cancel(true);
        }
        executor.shutdownNow();
        rpcExecutor.shutdownNow();
    }

    private enum ChildControl { PAUSE, RESUME, STOP }

    @FunctionalInterface
    private interface NodeCall {
        Map<String, Object> invoke() throws Exception;
    }

    private static final class LogicalTask {
        private final String taskId;
        private final String sessionId;
        private final NetworkProbeCapable node;
        private final Map<String, Object> plan;
        private final List<Map<String, Object>> targets;
        private final int batchCount;
        private final long createdAt = System.currentTimeMillis();
        private final Object monitor = new Object();
        private final Object nodeCallMonitor = new Object();
        private final CountDownLatch finished = new CountDownLatch(1);
        private final List<Map<String, Object>> observations = new ArrayList<>();
        private final List<Map<String, Object>> errors = new ArrayList<>();
        private int observationOffset;
        private int errorOffset;
        private volatile String status = "RUNNING";
        private volatile String outcome = "RUNNING";
        private volatile boolean cancelRequested;
        private volatile String childTaskId;
        private volatile Map<String, Object> activeSnapshot = Map.of();
        private volatile java.util.concurrent.Future<?> future;
        private int completedTargets;
        private int batchIndex;
        private long finishedAt;

        private LogicalTask(String taskId, String sessionId, NetworkProbeCapable node,
                            Map<String, Object> plan, List<Map<String, Object>> targets) {
            this.taskId = taskId;
            this.sessionId = sessionId;
            this.node = node;
            this.plan = wireMap(plan);
            this.targets = new ArrayList<>();
            for (Map<String, Object> target : targets) this.targets.add(wireMap(target));
            this.batchCount = (targets.size() + NODE_BATCH_SIZE - 1) / NODE_BATCH_SIZE;
        }

        private boolean terminal() {
            return "STOPPED".equals(status);
        }
    }
}
