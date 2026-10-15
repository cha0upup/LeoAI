package org.leo.web.service;

import jakarta.annotation.PreDestroy;
import org.leo.core.agent.capability.NetworkProbeCapable;
import org.leo.web.exception.ApiException;
import org.leo.service.discovery.NetworkProbeLimits;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.Map;
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
import java.util.LinkedHashSet;
import java.util.concurrent.atomic.AtomicInteger;

import static org.leo.service.discovery.NetworkProbePayloads.copyMap;
import static org.leo.service.discovery.NetworkProbePayloads.copyMapOrEmpty;

/**
 * Runs one logical network probe as a sequence of bounded node-side tasks.
 *
 * <p>A node accepts at most {@value NetworkProbeLimits#NODE_BATCH_SIZE} targets per task. This service owns that
 * transport detail, aggregates progress and evidence, and releases every
 * terminal child task after collecting its final snapshot.</p>
 */
@Service
public final class NetworkProbeOrchestrationService implements AutoCloseable {

    private static final int MAX_ACTIVE_TASKS = 36;
    private static final int RESULT_PAGE_ITEMS = 512;
    private static final int RESULT_PAGE_BYTES = 1024 * 1024;
    private static final int MAX_PENDING_ITEMS = 4096;
    private static final long MAX_PENDING_BYTES = 8L * 1024 * 1024;
    private static final long RPC_TIMEOUT_MS = 15_000L;
    private static final long TASK_TTL_MS = 30L * 60L * 1000L;
    private static final long DEFAULT_POLL_INTERVAL_MS = 2000L;

    private final Map<String, LogicalTask> tasks = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<NetworkProbeCapable, NodeBudget> nodeBudgets = new java.util.concurrent.ConcurrentHashMap<>();
    private final NetworkProbeScheduler scheduler;
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
                new ThreadPoolExecutor.AbortPolicy()));
    }

    NetworkProbeOrchestrationService(ExecutorService executor, long pollIntervalMs) {
        this(executor, pollIntervalMs, new ThreadPoolExecutor(
                4,
                4,
                60L,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(32),
                daemonThreadFactory(),
                new ThreadPoolExecutor.AbortPolicy()));
    }

    NetworkProbeOrchestrationService(ExecutorService executor, long pollIntervalMs,
                                     ExecutorService rpcExecutor) {
        this.scheduler = new NetworkProbeScheduler(executor, "network-probe-orchestrator");
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
            task.ticket = scheduler.ticket(() -> turn(task), error -> abort(task, error));
            scheduler.wake(task.ticket);
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
        boolean advanced;
        synchronized (task.monitor) {
            long before = (long) task.observationOffset + task.errorOffset;
            acknowledge(task.observations, true, task, observationCursor);
            acknowledge(task.errors, false, task, errorCursor);
            refreshPressure(task);
            task.monitor.notifyAll();
            advanced = before != (long) task.observationOffset + task.errorOffset;
        }
        if (advanced) scheduler.wake(task.ticket);
    }

    void onChange(String sessionId, String taskId, Runnable listener) {
        LogicalTask task = listener == null ? tasks.get(taskId) : requireTask(sessionId, taskId);
        if (task == null || !task.sessionId.equals(sessionId)) return;
        boolean ready;
        synchronized (task.monitor) {
            task.listener = listener;
            ready = task.terminal() || !task.observations.isEmpty() || !task.errors.isEmpty();
        }
        if (listener != null && ready) listener.run();
    }

    private void changed(LogicalTask task) {
        Runnable listener = task.listener;
        if (listener != null) listener.run();
    }

    public Map<String, Object> pause(String sessionId, String taskId) {
        LogicalTask task = requireTask(sessionId, taskId);
        String childTaskId;
        synchronized (task.monitor) {
            if (task.terminal()) return response(Map.of("status", "STOPPED"));
            task.status = "PAUSED";
            childTaskId = task.childTaskId;
        }
        if (childTaskId != null) { controlChild(task, childTaskId, ChildControl.PAUSE); task.nodePaused = true; }
        releaseBudget(task);
        scheduler.wake(task.ticket);
        changed(task);
        return response(Map.of("status", "PAUSED"));
    }

    public Map<String, Object> resume(String sessionId, String taskId) {
        LogicalTask task = requireTask(sessionId, taskId);
        synchronized (task.monitor) {
            if (task.terminal()) return response(Map.of("status", "STOPPED"));
            task.status = "RUNNING";
            task.monitor.notifyAll();
        }
        scheduler.wake(task.ticket);
        changed(task);
        return response(Map.of("status", "RUNNING"));
    }

    public Map<String, Object> stop(String sessionId, String taskId) {
        LogicalTask task = requireTask(sessionId, taskId);
        synchronized (task.monitor) {
            if (!task.terminal()) {
                task.cancelRequested = true;
                task.status = "STOPPED";
                task.outcome = "CANCELLED";
                task.finishedAt = System.currentTimeMillis();
                task.monitor.notifyAll();
            }
        }
        scheduler.wake(task.ticket);
        try {
            task.finished.await(5L, TimeUnit.SECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
        return response(Map.of("status", "STOPPED"));
    }

    private long turn(LogicalTask task) throws Exception {
        if (task.cancelRequested) {
            if (task.childTaskId != null) {
                controlChild(task, task.childTaskId, ChildControl.STOP);
                try { collectPage(task, true); } finally { releaseActive(task); }
            }
            finish(task, "CANCELLED"); return -1;
        }
        if ("PAUSED".equals(task.status) || task.backpressured) {
            if (task.childTaskId != null && !task.nodePaused) {
                controlChild(task, task.childTaskId, ChildControl.PAUSE); task.nodePaused = true;
            }
            releaseBudget(task); changed(task); return -1;
        }
        if (task.childTaskId == null && task.nextIndex >= task.targets.size()) {
            finish(task, "COMPLETED"); return -1;
        }
        if (!acquireBudget(task)) return -1; // Lease release/resume wakes the FIFO; no polling loop occupies a worker.
        if (task.childTaskId == null) {
            int workers = task.leaseThreads;
            if (workers == 0 || "PAUSED".equals(task.status) || task.cancelRequested) return 0;
            int end = Math.min(task.targets.size(), task.nextIndex + NetworkProbeLimits.SERVER_BATCH_SIZE);
            Map<String, Object> plan = childPlan(task.plan, task.targets.subList(task.nextIndex, end), workers);
            Map<String, Object> started = invoke(task, () -> task.node.startNetworkProbe(plan));
            String id = text(started.get("taskId"));
            if (id.isEmpty()) throw new IllegalStateException("节点未返回网络探测子任务 ID");
            synchronized (task.monitor) {
                task.childTaskId = id; task.childCursor = 0; task.childThreads = workers;
                task.childTargetCount = end - task.nextIndex; task.batchIndex++; task.seenErrors.clear();
            }
            if (task.cancelRequested || "PAUSED".equals(task.status)) return 0;
        }
        if (task.nodePaused) {
            controlChild(task, task.childTaskId, ChildControl.RESUME); task.nodePaused = false;
        }
        return collectPage(task, false);
    }

    private long collectPage(LogicalTask task, boolean cancelling) throws Exception {
        String id = task.childTaskId;
        Map<String, Object> latest = task.pendingPage;
        if (latest == null) {
            long cursor = task.childCursor;
            Map<String, Object> queried = invoke(task, () -> task.node.queryNetworkProbe(
                    id, cursor, cursor, RESULT_PAGE_ITEMS, RESULT_PAGE_BYTES, true));
            latest = copyMapOrEmpty(queried.get("result"));
        }
        if (latest.isEmpty()) throw new IllegalStateException("节点网络探测子任务结果为空");
        List<Map<String, Object>> page = mapListOrEmpty(latest.get("observations"));
        long next = longValue(latest.get("nextCursor"), -1);
        boolean more = Boolean.TRUE.equals(latest.get("hasMore"));
        if (longValue(latest.get("cursor"), -1) != task.childCursor || next - task.childCursor != page.size()
                || (more && page.isEmpty())) throw new IllegalStateException("节点网络探测分页游标无效");
        List<Map<String, Object>> errors = mapListOrEmpty(latest.get("errors")).stream()
                .filter(error -> !task.seenErrors.contains(error)).toList();
        long bytes = page.stream().mapToLong(NetworkProbeOrchestrationService::estimateBytes).sum()
                + errors.stream().mapToLong(NetworkProbeOrchestrationService::estimateBytes).sum();
        int items = page.size() + errors.size();
        if (items > MAX_PENDING_ITEMS || bytes > MAX_PENDING_BYTES)
            throw new IllegalStateException("节点单页结果超过服务端缓冲容量");
        boolean notify;
        synchronized (task.monitor) {
            if (task.observations.size() + task.errors.size() + items > MAX_PENDING_ITEMS
                    || task.pendingBytes + bytes > MAX_PENDING_BYTES) {
                if (cancelling) return -1;
                task.pendingPage = latest; task.backpressured = true; return 0;
            }
            task.pendingPage = null; task.observations.addAll(page); task.errors.addAll(errors);
            task.seenErrors.addAll(errors); task.pendingBytes += bytes; refreshPressure(task);
            Map<String, Object> metadata = new LinkedHashMap<>(latest);
            metadata.remove("observations"); metadata.remove("errors");
            notify = items > 0 || !metadata.equals(task.activeSnapshot);
            task.activeSnapshot = metadata;
            task.childCursor = next;
        }
        if (notify) changed(task);
        if (cancelling) {
            synchronized (task.monitor) {
                task.completedTargets += integer(latest.get("completed"), 0); task.activeSnapshot = Map.of();
            }
            return -1;
        }
        if ("STOPPED".equalsIgnoreCase(text(latest.get("status"))) && !more) {
            if (next > 0) { final long accepted = next; invoke(task, () -> task.node.ackNetworkProbe(id, accepted)); }
            String outcome = text(latest.get("outcome")).toUpperCase(java.util.Locale.ROOT);
            // Release before clearing the child ID, so failures still have a cleanup target.
            releaseChild(task, id); mergeCompletedBatch(task, id, latest);
            task.nextIndex += task.childTargetCount; task.nodePaused = false; releaseBudget(task);
            if ("FAILED".equals(outcome)) { fail(task, "节点网络探测子任务失败"); finish(task, "FAILED"); }
            else if ("CANCELLED".equals(outcome)) finish(task, "CANCELLED");
            return 0;
        }
        task.idleDelay = page.isEmpty() ? Math.min(pollIntervalMs, task.idleDelay * 2)
                : Math.min(100L, pollIntervalMs);
        return more ? 0 : task.idleDelay;
    }

    private boolean acquireBudget(LogicalTask task) {
        NodeBudget budget = nodeBudgets.computeIfAbsent(task.node, ignored -> new NodeBudget());
        synchronized (budget) {
            if (task.leaseThreads > 0) return true;
            budget.waiting.add(task);
            budget.waiting.removeIf(candidate -> candidate.terminal() || "PAUSED".equals(candidate.status) || candidate.backpressured);
            if (budget.waiting.isEmpty() || budget.waiting.iterator().next() != task) return false;
            int runnable = (int) tasks.values().stream().filter(candidate -> candidate.node.equals(task.node)
                    && !candidate.terminal() && !"PAUSED".equals(candidate.status) && !candidate.backpressured).count();
            int capacity = Math.max(1, Math.min(NetworkProbeLimits.NODE_MAX_THREADS, task.node.networkProbeWorkerLimit()));
            int wanted = task.childTaskId != null ? task.childThreads
                    : Math.min(task.requestedThreads, Math.max(1, capacity / Math.max(1, runnable)));
            wanted = Math.min(wanted, Math.min(NetworkProbeLimits.SERVER_BATCH_SIZE, task.targets.size() - task.nextIndex));
            if (budget.used + wanted > capacity) return false;
            budget.waiting.remove(task); budget.used += wanted; task.leaseThreads = wanted; return true;
        }
    }

    private void releaseBudget(LogicalTask task) {
        NodeBudget budget = nodeBudgets.get(task.node);
        if (budget == null) return;
        List<LogicalTask> waiting;
        synchronized (budget) {
            budget.used -= task.leaseThreads; task.leaseThreads = 0; budget.waiting.remove(task);
            waiting = new ArrayList<>(budget.waiting);
        }
        for (LogicalTask candidate : waiting) scheduler.wake(candidate.ticket);
    }

    private void releaseActive(LogicalTask task) {
        String id = task.childTaskId;
        if (id != null) { releaseChild(task, id); task.childTaskId = null; }
        task.pendingPage = null; releaseBudget(task);
    }

    private void finish(LogicalTask task, String outcome) {
        synchronized (task.monitor) {
            task.status = "STOPPED"; task.outcome = task.cancelRequested ? "CANCELLED" : outcome;
            task.finishedAt = System.currentTimeMillis(); task.nodePaused = false;
        }
        releaseBudget(task); scheduler.retire(task.ticket); task.finished.countDown();
        changed(task);
    }

    private void abort(LogicalTask task, Exception error) {
        fail(task, messageOf(error));
        boolean interrupted = Thread.interrupted();
        try {
            if (task.childTaskId != null) controlChild(task, task.childTaskId, ChildControl.STOP);
            releaseActive(task);
        } finally { finish(task, task.outcome); if (interrupted) Thread.currentThread().interrupt(); }
    }

    private void mergeCompletedBatch(LogicalTask task, String childTaskId, Map<String, Object> childSnapshot) {
        synchronized (task.monitor) {
            if (!childTaskId.equals(task.childTaskId)) return;
            task.completedTargets += integer(childSnapshot.get("completed"), 0);
            task.activeSnapshot = Map.of();
            task.childTaskId = null;
        }
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
    }

    private Map<String, Object> invoke(LogicalTask task, NodeCall call) throws Exception {
        Future<Map<String, Object>> future;
        try {
            future = rpcExecutor.submit(() -> {
                synchronized (task.nodeCallMonitor) {
                    Map<String, Object> response = call.invoke();
                    int code = response != null && response.get("code") instanceof Number number
                            ? number.intValue() : 500;
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
        } catch (InterruptedException error) {
            future.cancel(true);
            throw error;
        } catch (ExecutionException error) {
            Throwable cause = error.getCause();
            if (cause instanceof Exception exception) throw exception;
            throw error;
        }
    }

    private Map<String, Object> snapshot(LogicalTask task) {
        synchronized (task.monitor) {
            Map<String, Object> result = taskSummary(task);
            result.put("targets", new ArrayList<>(task.targets));
            result.put("plan", publicPlan(task.plan));
            result.put("observations", new ArrayList<>(task.observations));
            result.put("errors", new ArrayList<>(task.errors));
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
            List<Map<String, Object>> observations = new ArrayList<>(
                    task.observations.subList(observationStart, observationEnd));
            List<Map<String, Object>> errors = new ArrayList<>(
                    task.errors.subList(errorStart, errorEnd));
            Map<String, Object> result = taskSummary(task);
            result.put("cursor", Long.valueOf(observationCursor));
            result.put("nextCursor", Long.valueOf(observationBase + observationEnd));
            result.put("errorCursor", Long.valueOf(errorCursor));
            result.put("nextErrorCursor", Long.valueOf(errorBase + errorEnd));
            result.put("hasMore", Boolean.valueOf(observationEnd < task.observations.size()
                    || errorEnd < task.errors.size()));
            result.put("incremental", Boolean.TRUE);
            result.put("observations", observations);
            result.put("errors", errors);
            return result;
        }
    }

    /** Caller holds the task monitor for a consistent full or incremental snapshot. */
    private Map<String, Object> taskSummary(LogicalTask task) {
        int completed = Math.min(task.targets.size(),
                task.completedTargets + integer(task.activeSnapshot.get("completed"), 0));
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
        result.put("createdAt", Long.valueOf(task.createdAt));
        result.put("backpressured", task.backpressured);
        result.put("pendingItems", task.observations.size() + task.errors.size());
        result.put("pendingBytes", task.pendingBytes);
        result.put("nodeBackpressured", Boolean.TRUE.equals(task.activeSnapshot.get("backpressured")));
        if (task.error != null) result.put("error", task.error);
        if (task.finishedAt > 0L) result.put("finishedAt", Long.valueOf(task.finishedAt));
        return result;
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
            for (Map<String, Object> value : values.subList(0, remove)) task.pendingBytes -= estimateBytes(value);
            task.pendingBytes = Math.max(0L, task.pendingBytes);
            values.subList(0, remove).clear();
            if (observations) task.observationOffset = (int) bounded;
            else task.errorOffset = (int) bounded;
        }
    }

    private static void refreshPressure(LogicalTask task) {
        int count = task.observations.size() + task.errors.size();
        if (count >= MAX_PENDING_ITEMS * 3 / 4 || task.pendingBytes >= MAX_PENDING_BYTES * 3 / 4)
            task.backpressured = true;
        else if (count <= MAX_PENDING_ITEMS / 2 && task.pendingBytes <= MAX_PENDING_BYTES / 2)
            task.backpressured = false;
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
            task.error = message.length() > 4096 ? message.substring(0, 4096) : message;
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("stage", "orchestration");
            error.put("errorCode", "ORCHESTRATION");
            error.put("error", task.error);
            long bytes = estimateBytes(error);
            if (task.observations.size() + task.errors.size() < MAX_PENDING_ITEMS
                    && task.pendingBytes + bytes <= MAX_PENDING_BYTES) {
                task.errors.add(error);
                task.pendingBytes += bytes;
            }
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

    private static Map<String, Object> childPlan(Map<String, Object> source, List<Map<String, Object>> targets, int workers) {
        Map<String, Object> plan = new LinkedHashMap<>(source);
        plan.put("targets", targets);
        Map<String, Object> limits = copyMapOrEmpty(source.get("limits"));
        limits.put("threads", workers); plan.put("limits", limits);
        return copyMap(plan);
    }

    private static Map<String, Object> publicPlan(Map<String, Object> source) {
        Map<String, Object> plan = new LinkedHashMap<>();
        if (source.get("stages") instanceof List<?> stages) plan.put("stages", new ArrayList<>(stages));
        if (source.get("limits") instanceof Map<?, ?> limits) plan.putAll(copyMap(limits));
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
            result.add(copyMap(itemMap));
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
            if (!task.terminal()) fail(task, "网络探测服务关闭");
            String id = task.childTaskId;
            if (id != null) {
                try {
                    rpcExecutor.execute(() -> {
                        synchronized (task.nodeCallMonitor) {
                            try { task.node.stopNetworkProbe(id); } catch (Exception ignored) { }
                            try { task.node.releaseNetworkProbe(id); } catch (Exception ignored) { }
                        }
                    });
                } catch (RuntimeException ignored) { /* Node TTL remains the transport-loss fallback. */ }
            }
            releaseBudget(task); task.finished.countDown();
        }
        scheduler.close();
        rpcExecutor.shutdown();
    }

    private enum ChildControl { PAUSE, RESUME, STOP }

    @FunctionalInterface
    private interface NodeCall {
        Map<String, Object> invoke() throws Exception;
    }

    private static final class NodeBudget {
        private int used;
        private final Set<LogicalTask> waiting = new LinkedHashSet<>();
    }

    private static final class LogicalTask {
        private final String taskId;
        private final String sessionId;
        private final NetworkProbeCapable node;
        private final Map<String, Object> plan;
        private final List<Map<String, Object>> targets;
        private final int batchCount;
        private final int requestedThreads;
        private final long createdAt = System.currentTimeMillis();
        private final Object monitor = new Object();
        private final Object nodeCallMonitor = new Object();
        private final CountDownLatch finished = new CountDownLatch(1);
        private final List<Map<String, Object>> observations = new ArrayList<>();
        private final List<Map<String, Object>> errors = new ArrayList<>();
        private int observationOffset;
        private int errorOffset;
        private long pendingBytes;
        private volatile boolean backpressured;
        private String error;
        private volatile String status = "RUNNING";
        private volatile String outcome = "RUNNING";
        private volatile boolean cancelRequested;
        private volatile String childTaskId;
        private long childCursor;
        private volatile Map<String, Object> activeSnapshot = Map.of();
        private volatile Runnable listener;
        private NetworkProbeScheduler.Ticket ticket;
        private volatile int leaseThreads;
        private int childThreads, childTargetCount, nextIndex;
        private volatile boolean nodePaused;
        private long idleDelay = 10;
        private Map<String, Object> pendingPage;
        private final Set<Map<String, Object>> seenErrors = new HashSet<>();
        private int completedTargets;
        private int batchIndex;
        private long finishedAt;

        private LogicalTask(String taskId, String sessionId, NetworkProbeCapable node,
                            Map<String, Object> plan, List<Map<String, Object>> targets) {
            this.taskId = taskId;
            this.sessionId = sessionId;
            this.node = node;
            Map<String, Object> settings = new LinkedHashMap<>(plan);
            settings.remove("targets");
            this.plan = copyMap(settings);
            Map<?, ?> limits = this.plan.get("limits") instanceof Map<?, ?> map ? map : Map.of();
            this.requestedThreads = Math.max(1, Math.min(NetworkProbeLimits.NODE_MAX_THREADS,
                    integer(limits.get("threads"), NetworkProbeLimits.NODE_DEFAULT_THREADS)));
            this.targets = targets; // start() already owns this validated, deep-copied list.
            this.batchCount = (targets.size() + NetworkProbeLimits.SERVER_BATCH_SIZE - 1)
                    / NetworkProbeLimits.SERVER_BATCH_SIZE;
        }

        private boolean terminal() {
            return "STOPPED".equals(status);
        }
    }
}
