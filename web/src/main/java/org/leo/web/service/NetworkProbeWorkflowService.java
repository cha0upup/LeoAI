package org.leo.web.service;

import org.leo.service.discovery.NetworkProbeAnalysisService;
import org.leo.core.agent.capability.NetworkProbeCapable;
import org.leo.web.exception.ApiException;
import org.leo.service.discovery.NetworkProbeLimits;
import org.leo.service.discovery.ScanStage;
import org.leo.service.discovery.ScanPlanService.ScanPlan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import static org.leo.service.discovery.NetworkProbePayloads.copyMap;
import static org.leo.service.discovery.NetworkProbePayloads.copyMapOrEmpty;

/**
 * Runs the discovery workflow as one user-visible task.
 *
 * <p>The existing orchestration service remains responsible for node batching
 * and transport lifecycle. This service only owns stage transitions and the
 * derived targets passed from one stage to the next.</p>
 */
@Service
public final class NetworkProbeWorkflowService implements AutoCloseable {

    /** Stable client-facing identifier used by the task engine and scan views. */
    public static final String KIND = "network_workflow";
    private static final long TASK_TTL_MS = 30L * 60L * 1000L;
    private static final long DEFAULT_POLL_INTERVAL_MS = 2000L;
    /** A banner normally arrives immediately; do not let silent services block discovery. */
    private static final int SERVICE_BANNER_TIMEOUT_MS = 300;
    /** HTTP identification only needs the response headers. */
    private static final int HTTP_IDENTIFICATION_TIMEOUT_MS = 700;
    private static final Pattern MYSQL_VERSION = Pattern.compile("\\b\\d+\\.\\d+(?:\\.\\d+)?\\b");
    private final NetworkProbeAnalysisService analysisService;
    private final NetworkProbeOrchestrationService orchestrationService;
    private volatile NetworkProbeResultStore resultStore;
    private final Map<String, WorkflowTask> tasks = new java.util.concurrent.ConcurrentHashMap<>();
    private final NetworkProbeScheduler scheduler;
    private final long pollIntervalMs;

    @Autowired
    public NetworkProbeWorkflowService(NetworkProbeAnalysisService analysisService,
                                       NetworkProbeOrchestrationService orchestrationService) {
        this(analysisService, orchestrationService, new ThreadPoolExecutor(
                2,
                2,
                60L,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(48),
                daemonThreadFactory(),
                new ThreadPoolExecutor.AbortPolicy()), DEFAULT_POLL_INTERVAL_MS);
    }

    NetworkProbeWorkflowService(NetworkProbeAnalysisService analysisService,
                                NetworkProbeOrchestrationService orchestrationService,
                                ExecutorService executor, long pollIntervalMs) {
        this.analysisService = analysisService;
        this.orchestrationService = orchestrationService;
        this.scheduler = new NetworkProbeScheduler(executor, "network-probe-workflow");
        this.pollIntervalMs = Math.max(10L, pollIntervalMs);
    }

    @Autowired(required = false)
    public void setResultStore(NetworkProbeResultStore resultStore) {
        this.resultStore = resultStore;
    }

    public Map<String, Object> start(String sessionId, NetworkProbeCapable node, ScanPlan plan) {
        if (plan == null) throw new IllegalArgumentException("扫描计划不能为空");
        return start(sessionId, node, new WorkflowSpec(plan.hosts(), plan.ports(), plan.targets(),
                plan.timeoutMs(), plan.workers(), plan.stages(), plan.name(), plan.reachabilityTargets(),
                plan.fingerprintRules(), List.of(), "", false));
    }

    private Map<String, Object> start(String sessionId, NetworkProbeCapable node, WorkflowSpec spec) {
        if (sessionId == null || sessionId.isBlank()) throw new IllegalArgumentException("sessionId不能为空");
        if (node == null) throw new IllegalArgumentException("网络探测节点不能为空");
        cleanup();

        String taskId = UUID.randomUUID().toString();
        WorkflowTask task = new WorkflowTask(taskId, sessionId.trim(), node, spec);
        synchronized (tasks) {
            long active = tasks.values().stream().filter(candidate -> !candidate.terminal()).count();
            if (active >= 24) throw new IllegalStateException("服务端扫描工作流任务已达上限");
            tasks.put(taskId, task);
        }
        NetworkProbeResultStore store = resultStore;
        Map<String, Object> durableConfig = durableConfig(spec);
        durableConfig.put("targetCount", Integer.valueOf(spec.hosts().size()));
        if (store != null && !store.createTask(taskId, sessionId.trim(), spec.name(), durableConfig)) {
            tasks.remove(taskId);
            throw new IllegalStateException("扫描结果存储不可用");
        }
        persistTaskState(task);
        try {
            task.startup = scheduler.ticket(() -> { run(task); scheduler.retire(task.startup); return -1; },
                    error -> { fail(task, messageOf(error)); task.finished.countDown(); });
            scheduler.wake(task.startup);
        } catch (RuntimeException error) {
            synchronized (task.monitor) {
                task.status = "STOPPED";
                task.outcome = "FAILED";
                task.error = "服务端扫描工作流调度队列已满";
                task.finishedAt = System.currentTimeMillis();
            }
            persistTaskState(task);
            tasks.remove(taskId);
            throw new IllegalStateException("服务端扫描工作流调度队列已满", error);
        }
        return response(Map.of("taskId", taskId));
    }

    public Map<String, Object> startSupplemental(String sessionId, NetworkProbeCapable node,
                                                  String sourceTaskId, List<String> endpointIds, Object selector) throws Exception {
        if (resultStore == null) throw new IllegalStateException("扫描结果存储不可用");
        var source = resultStore.fingerprintSource(sessionId, sourceTaskId, endpointIds);
        return startFingerprint(sessionId, node, source.endpoints(), source.targets(),
                analysisService.snapshotRules(selector), "组件补扫", sourceTaskId, false);
    }

    public Map<String, Object> startFingerprint(String sessionId, NetworkProbeCapable node,
                                                List<Map<String, Object>> endpoints, List<Map<String, Object>> targets,
                                                List<Map<String, Object>> rules, String name, String sourceTaskId, boolean debug) throws Exception {
        endpoints = mapList(endpoints);
        targets = mapList(targets);
        rules = mapList(rules);
        if (endpoints.isEmpty()) throw new IllegalArgumentException("组件识别需要至少一个资产");
        List<String> hosts = endpoints.stream().map(endpoint -> text(endpoint.get("host"))).distinct().toList();
        WorkflowSpec spec = new WorkflowSpec(hosts, List.of(), targets, 3000, 16,
                List.of(ScanStage.FINGERPRINT), name, List.of(), rules, endpoints, sourceTaskId, debug);
        // Validate all work before creating a durable task; the orchestrator bounds node batches.
        analysisService.prepare(Map.of("kind", "fingerprint", "targets", fingerprintTargets(spec, endpoints), "rules", rules));
        return start(sessionId, node, spec);
    }

    public Map<String, Object> query(String sessionId, String taskId) {
        cleanup();
        return response(Map.of("result", snapshot(requireTask(sessionId, taskId))));
    }

    public Map<String, Object> querySummaryIfPresent(String sessionId, String taskId) {
        cleanup();
        if (sessionId == null || sessionId.isBlank() || taskId == null || taskId.isBlank()) {
            return Map.of();
        }
        WorkflowTask task = tasks.get(taskId.trim());
        if (task == null || !ownsTask(sessionId, task)) return Map.of();
        return snapshot(task, false);
    }

    public Map<String, Object> pause(String sessionId, String taskId) {
        WorkflowTask task = requireTask(sessionId, taskId);
        String childTaskId;
        synchronized (task.monitor) {
            if (task.terminal()) return response(Map.of("status", "STOPPED"));
            task.status = "PAUSED";
            StageState stage = task.currentStage == null ? null : task.stages.get(task.currentStage);
            if (stage != null && !stage.terminal()) stage.status = "PAUSED";
            childTaskId = task.childTaskId;
        }
        if (childTaskId != null) orchestrationService.pause(task.sessionId, childTaskId);
        wakeStage(task);
        return response(Map.of("status", "PAUSED"));
    }

    public Map<String, Object> resume(String sessionId, String taskId) {
        WorkflowTask task = requireTask(sessionId, taskId);
        String childTaskId;
        synchronized (task.monitor) {
            if (task.terminal()) return response(Map.of("status", "STOPPED"));
            task.status = "RUNNING";
            StageState stage = task.currentStage == null ? null : task.stages.get(task.currentStage);
            if (stage != null && "PAUSED".equals(stage.status)) stage.status = "RUNNING";
            childTaskId = task.childTaskId;
        }
        if (childTaskId != null) orchestrationService.resume(task.sessionId, childTaskId);
        wakeStage(task);
        return response(Map.of("status", "RUNNING"));
    }

    public Map<String, Object> stop(String sessionId, String taskId) {
        return stopTask(requireTask(sessionId, taskId));
    }

    private Map<String, Object> stopTask(WorkflowTask task) {
        String childTaskId;
        boolean changed;
        synchronized (task.monitor) {
            changed = !task.terminal();
            if (changed) {
                task.cancelRequested = true;
                task.status = "STOPPED";
                task.outcome = "CANCELLED";
                task.finishedAt = System.currentTimeMillis();
                StageState stage = task.currentStage == null ? null : task.stages.get(task.currentStage);
                if (stage != null && !stage.terminal()) stage.status = "CANCELLED";
            }
            childTaskId = task.childTaskId;
        }
        if (changed) persistTaskState(task);
        if (childTaskId != null) orchestrationService.stop(task.sessionId, childTaskId);
        wakeStage(task);
        try {
            task.finished.await(5L, TimeUnit.SECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
        return response(Map.of("status", "STOPPED"));
    }

    /**
     * Stops an in-memory workflow (when it is still active) and removes it
     * from the live task registry. Durable rows are deleted by the result
     * store after this method returns.
     */
    public Map<String, Object> delete(String sessionId, String taskId) {
        if (sessionId == null || sessionId.isBlank()) throw new IllegalArgumentException("sessionId不能为空");
        if (taskId == null || taskId.isBlank()) throw new IllegalArgumentException("taskId不能为空");
        String normalizedSessionId = sessionId.trim();
        String normalizedTaskId = taskId.trim();
        WorkflowTask task = tasks.get(normalizedTaskId);
        if (task != null && !ownsTask(normalizedSessionId, task)) {
            throw ApiException.notFound("扫描工作流任务不存在或不属于当前会话");
        }
        if (task != null) {
            if (!task.terminal()) stop(normalizedSessionId, normalizedTaskId);
            tasks.remove(normalizedTaskId, task);
        }
        return response(Map.of("status", "DELETED", "taskId", normalizedTaskId));
    }

    /** Stops and forgets all in-memory workflows belonging to a destroyed session. */
    public void cleanupSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return;
        String normalizedSessionId = sessionId.trim();
        List<String> taskIds = tasks.values().stream()
                .filter(task -> task.sessionId.equals(normalizedSessionId))
                .map(task -> task.taskId)
                .toList();
        for (String taskId : taskIds) {
            try {
                WorkflowTask task = tasks.get(taskId);
                if (task != null) stopTask(task);
            } catch (Exception error) {
                // Session destruction must continue even if a child node is unavailable.
                synchronized (tasks) {
                    tasks.remove(taskId);
                }
            }
            tasks.remove(taskId);
        }
    }

    private void run(WorkflowTask task) {
        if (!proceed(task)) { task.finished.countDown(); return; }
        try {
            CompletableFuture<?> flow;
            if (!task.spec.seedEndpoints().isEmpty()) {
                List<Map<String, Object>> endpoints = mapList(task.spec.seedEndpoints());
                synchronized (task.monitor) { task.confirmedHosts.addAll(task.spec.hosts()); }
                if (resultStore != null && !resultStore.append(task.taskId, seedObservations(task.spec), List.of()))
                    throw new IllegalStateException("保存补扫资产上下文失败");
                for (Map<String, Object> endpoint : endpoints) endpoint.put("serviceIdentified", true);
                flow = executeFingerprint(task, endpoints);
            } else flow = discover(task);
            flow.whenComplete((result, error) -> {
                try {
                    if (error != null) {
                        Throwable cause = error instanceof CompletionException ? error.getCause() : error;
                        fail(task, cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage());
                    } else if (proceed(task)) complete(task);
                } finally { task.finished.countDown(); }
            });
        } catch (Exception error) { fail(task, messageOf(error)); task.finished.countDown(); }
    }

    private boolean proceed(WorkflowTask task) { return !task.cancelRequested && !task.terminal(); }

    private CompletableFuture<?> discover(WorkflowTask task) {
        Set<String> hosts = new LinkedHashSet<>(task.spec.hosts());
        StageState reachability = task.stages.get("REACHABILITY");
        CompletableFuture<Map<String, Object>> discovery = CompletableFuture.completedFuture(Map.of());
        if (reachability != null) {
            try {
                Map<String, Object> scan = reachabilityScan(task.spec);
                scan.put("hostDiscovery", true);
                scan.put("javaReachability", task.node.supportsJavaReachability());
                var prepared = analysisService.prepare(scan);
                discovery = executeStage(task, reachability, prepared.plan(), prepared, null);
            } catch (Exception error) { return CompletableFuture.failedFuture(error); }
        }
        return discovery.thenCompose(result -> {
            if (!proceed(task) || !task.spec.stages().contains(ScanStage.PORT_SCAN))
                return CompletableFuture.completedFuture(null);
            if (reachability != null) {
                Map<String, Object> analysis = copyMapOrEmpty(result.get("analysis"));
                Set<String> candidates = new LinkedHashSet<>(stringList(analysis.get("reachableHostList")));
                hosts.retainAll(candidates);
            }
            if (hosts.isEmpty()) {
                for (String name : List.of("PORT_SCAN", "SERVICE_PROBE", "FINGERPRINT"))
                    skip(task, task.stages.get(name), "NO_REACHABLE_HOSTS");
                return CompletableFuture.completedFuture(null);
            }
            StageState ports = task.stages.get("PORT_SCAN");
            return executePorts(task, ports, hosts)
                    .thenCompose(portResult -> {
                        if (!proceed(task)) return CompletableFuture.completedFuture(null);
                        List<Map<String, Object>> endpoints = mapList(portResult.get("openPortResults"));
                        if (endpoints.isEmpty()) {
                            skip(task, task.stages.get("SERVICE_PROBE"), "NO_OPEN_PORTS");
                            skip(task, task.stages.get("FINGERPRINT"), "NO_OPEN_PORTS");
                            return CompletableFuture.completedFuture(null);
                        }
                        return identifyServices(task, endpoints).thenCompose(ignored -> executeFingerprint(task, endpoints));
                    });
        });
    }

    private CompletableFuture<Map<String, Object>> executePorts(WorkflowTask task, StageState stage, Set<String> hosts) {
        synchronized (task.monitor) {
            if (!proceed(task)) return CompletableFuture.completedFuture(Map.of());
            task.currentStage = stage.name; stage.status = "PAUSED".equals(task.status) ? "PAUSED" : "RUNNING";
        }
        Map<String, Object> plan = portPlan(task.spec, hosts);
        List<Map<String, Object>> targets = mapList(plan.get("targets"));
        List<Map<String, Object>> cached = new ArrayList<>();
        List<Map<String, Object>> remaining = new ArrayList<>();
        for (Map<String, Object> target : targets) {
            Map<String, Object> observation = task.reachabilityCache.get(endpointKey(target));
            if (observation == null) remaining.add(target);
            else {
                Map<String, Object> reused = copyMap(observation);
                reused.put("probeSource", "reachability-cache"); cached.add(reused);
            }
        }
        if (resultStore != null && !cached.isEmpty()
                && !resultStore.append(task.taskId, annotateStage(cached, "PORT_SCAN"), List.of()))
            return CompletableFuture.failedFuture(new IllegalStateException("保存探活复用结果失败"));
        if (remaining.isEmpty()) {
            Map<String, Map<String, Object>> open = new LinkedHashMap<>(); collectOpenEndpoints(open, cached);
            synchronized (task.monitor) {
                if (!proceed(task)) return CompletableFuture.completedFuture(Map.of());
                stage.status = "COMPLETED";
                setStageProgress(stage, targets.size(), targets.size());
                enrichPortResult(stage, open.values());
            }
            persistTaskState(task);
            return CompletableFuture.completedFuture(Map.of("openPortResults", new ArrayList<>(open.values())));
        }
        plan.put("targets", remaining);
        return executeStage(task, stage, plan, null, null, cached.size(), targets.size());
    }

    private CompletableFuture<?> identifyServices(WorkflowTask task, List<Map<String, Object>> endpoints) {
        StageState stage = task.stages.get("SERVICE_PROBE");
        if (stage == null || !proceed(task)) return CompletableFuture.completedFuture(null);
        return executeStage(task, stage, servicePlan(task.spec, endpoints), null, endpoints).thenCompose(ignoredTcp -> {
            if (!proceed(task)) return CompletableFuture.completedFuture(null);
            List<Map<String, Object>> http = httpCandidates(endpoints);
            CompletableFuture<Map<String, Object>> probes = CompletableFuture.completedFuture(Map.of());
            int total = endpoints.size() + http.size();
            if (!http.isEmpty()) {
                setStageProgress(stage, endpoints.size(), total);
                probes = executeStage(task, stage, serviceHttpPlan(task.spec, http, false), null, endpoints, endpoints.size(), total);
            }
            return probes.thenCompose(ignoredHttp -> {
                // Each stage applies its evidence to the live endpoints before completing.
                if (!proceed(task)) return CompletableFuture.completedFuture(null);
                List<Map<String, Object>> https = httpsCandidates(http);
                if (https.isEmpty()) return CompletableFuture.completedFuture(null);
                int expanded = total + https.size(); setStageProgress(stage, total, expanded);
                return executeStage(task, stage, serviceHttpPlan(task.spec, https, true), null, endpoints, total, expanded);
            }).thenRun(() -> {
                if (!proceed(task)) return;
                enrichPortResult(task.stages.get("PORT_SCAN"), endpoints);
            });
        });
    }

    private CompletableFuture<?> executeFingerprint(WorkflowTask task, List<Map<String, Object>> endpoints) {
        StageState stage = task.stages.get("FINGERPRINT");
        if (stage == null || !proceed(task)) return CompletableFuture.completedFuture(null);
        List<Map<String, Object>> applications = fingerprintTargets(task.spec, endpoints);
        if (applications.isEmpty()) { skip(task, stage, "NO_HTTP_APPLICATIONS"); return CompletableFuture.completedFuture(null); }
        try {
            var prepared = analysisService.prepare(Map.of("kind", "fingerprint", "targets", applications,
                    "rules", task.spec.fingerprintRules(), "threads", Math.min(32, task.spec.threads()), "debug", task.spec.debug()));
            return executeStage(task, stage, prepared.plan(), prepared, endpoints);
        } catch (Exception error) { return CompletableFuture.failedFuture(error); }
    }

    private List<Map<String, Object>> seedObservations(WorkflowSpec spec) {
        List<Map<String, Object>> observations = new ArrayList<>();
        for (Map<String, Object> endpoint : spec.seedEndpoints()) {
            Map<String, Object> observation = new LinkedHashMap<>();
            for (String key : List.of("host", "port", "protocol", "service", "confidence"))
                if (endpoint.get(key) != null) observation.put(key, endpoint.get(key));
            observation.put("state", "open");
            observation.put("stage", "asset-context");
            observation.put("workflowStage", "SERVICE_PROBE");
            observation.put("sourceTaskId", spec.sourceTaskId());
            observation.put("contextOnly", true);
            Map<String, Object> evidence = new LinkedHashMap<>();
            for (String key : List.of("title", "statusCode", "server", "banner", "contentType", "location"))
                if (endpoint.get(key) != null) evidence.put(key, endpoint.get(key));
            if (endpoint.get("responseSize") != null) evidence.put("bodyLength", endpoint.get("responseSize"));
            observation.put("evidence", evidence);
            observations.add(observation);
        }
        return observations;
    }

    private CompletableFuture<Map<String, Object>> executeStage(WorkflowTask task, StageState stage,
            Map<String, Object> plan, NetworkProbeAnalysisService.PreparedScan prepared,
            List<Map<String, Object>> endpoints) {
        return executeStage(task, stage, plan, prepared, endpoints, 0, 0);
    }

    private CompletableFuture<Map<String, Object>> executeStage(WorkflowTask task, StageState stage,
            Map<String, Object> plan, NetworkProbeAnalysisService.PreparedScan prepared,
            List<Map<String, Object>> endpoints, int offset, int total) {
        StageExecution context = new StageExecution(task, stage, plan, prepared, endpoints, offset, total, resultStore);
        context.ticket = scheduler.ticket(() -> stageTurn(context), error -> abortStage(context, error));
        synchronized (stage) { stage.status = "PAUSED".equals(task.status) ? "PAUSED" : "PENDING"; }
        task.execution = context; scheduler.wake(context.ticket); return context.result;
    }

    private long stageTurn(StageExecution context) throws Exception {
        if (context.result.isDone()) return -1;
        WorkflowTask task = context.task;
        StageState stage = context.stage;
        if (context.childTaskId == null) {
            if (task.cancelRequested) {
                finishStage(context, new LinkedHashMap<>(Map.of("outcome", "CANCELLED"))); return -1;
            }
            if ("PAUSED".equals(task.status)) return -1;
            synchronized (task.monitor) { task.currentStage = stage.name; stage.status = "RUNNING"; }
            var started = orchestrationService.start(task.sessionId, task.node, context.plan);
            context.childTaskId = text(started.get("taskId"));
            if (context.childTaskId.isEmpty()) throw new IllegalStateException("工作流阶段未返回 taskId: " + stage.name);
            if (context.prepared != null) analysisService.register(context.childTaskId, context.prepared);
            synchronized (task.monitor) { task.childTaskId = context.childTaskId; stage.backendTaskId = context.childTaskId; }
            if (context.result.isDone()) {
                orchestrationService.stop(task.sessionId, context.childTaskId); disposeStage(context); return -1;
            }
            orchestrationService.onChange(task.sessionId, context.childTaskId, () -> scheduler.wake(context.ticket));
            if ("PAUSED".equals(task.status)) orchestrationService.pause(task.sessionId, context.childTaskId);
        }
        if (task.cancelRequested) orchestrationService.stop(task.sessionId, context.childTaskId);
        var queried = orchestrationService.querySince(task.sessionId, context.childTaskId,
                context.observationCursor, context.errorCursor);
        if (context.prepared != null) analysisService.enrich(context.childTaskId, queried);
        Map<String, Object> latest = copyMapOrEmpty(queried.get("result"));
        if (latest.isEmpty()) throw new IllegalStateException("工作流阶段结果为空: " + stage.name);
        updateStage(stage, latest, context.offset, context.total);
        List<Map<String, Object>> observations = mapList(latest.get("observations"));
        List<Map<String, Object>> errors = mapList(latest.get("errors"));
        synchronized (context.task.monitor) {
            for (Map<String, Object> observation : observations) {
                if ("REACHABILITY".equals(stage.name) && "tcp-connect".equals(observation.get("stage"))
                        && ("open".equals(observation.get("state")) || "REFUSED".equals(observation.get("errorCode")))
                        && task.plannedEndpointKeys.contains(endpointKey(observation)))
                    task.reachabilityCache.putIfAbsent(endpointKey(observation), copyMap(observation));
                if ("open".equals(observation.get("state")) || "REFUSED".equals(observation.get("errorCode"))) {
                    String host = text(observation.get("host"));
                    if (!host.isEmpty()) context.task.confirmedHosts.add(host);
                }
            }
        }
        if ("PORT_SCAN".equals(context.stage.name)) {
            collectOpenEndpoints(context.endpointsByKey, observations);
            enrichPortResult(context.stage, context.endpointsByKey.values());
        } else if ("SERVICE_PROBE".equals(context.stage.name)) {
            if (context.liveEndpoints != null) {
                // Live endpoint maps retain earlier evidence; replayed result pages are idempotent.
                applyServiceEvidence(context.endpointsByKey, observations);
                enrichPortResult(context.stage, context.liveEndpoints);
            }
        }
        List<Map<String, Object>> matches = "FINGERPRINT".equals(context.stage.name)
                ? mapList(copyMapOrEmpty(latest.get("analysis")).get("matches")) : List.of();
        if ("FINGERPRINT".equals(context.stage.name) && context.liveEndpoints != null) {
            applyFingerprintMatches(context.liveEndpoints, matches);
            enrichPortResult(context.stage, context.liveEndpoints);
        }
        boolean hasData = !observations.isEmpty() || !errors.isEmpty() || !matches.isEmpty();
        boolean persisted = context.store == null || !hasData || context.store.append(context.task.taskId,
                annotateStage(observations, context.stage.name), annotateStage(errors, context.stage.name),
                matches, "FINGERPRINT".equals(context.stage.name) ? context.liveEndpoints : List.of());
        if (persisted) {
            context.persistenceFailures = 0;
        } else if (++context.persistenceFailures >= 8) {
            throw new IllegalStateException("扫描结果连续持久化失败");
        }
        long nextCursor = longValue(latest.get("nextCursor"), context.observationCursor);
        long nextErrorCursor = longValue(latest.get("nextErrorCursor"), context.errorCursor);
        if (persisted) {
            orchestrationService.acknowledge(context.task.sessionId, context.childTaskId, nextCursor, nextErrorCursor);
            context.observationCursor = Math.max(context.observationCursor, nextCursor);
            context.errorCursor = Math.max(context.errorCursor, nextErrorCursor);
        }
        persistTaskState(task, false);
        boolean terminal = "STOPPED".equalsIgnoreCase(text(latest.get("status"))) && !Boolean.TRUE.equals(latest.get("hasMore"));
        if (terminal && persisted) {
            if ("PORT_SCAN".equals(stage.name)) latest.put("openPortResults", new ArrayList<>(context.endpointsByKey.values()));
            finishStage(context, latest); return -1;
        }
        if (!persisted) return terminal ? Math.max(25L, pollIntervalMs * 2) : pollIntervalMs;
        if (Boolean.TRUE.equals(latest.get("hasMore"))) return 0;
        if ("PAUSED".equals(task.status) && !hasData) return -1;
        return pollIntervalMs;
    }

    private void finishStage(StageExecution context, Map<String, Object> latest) {
        String outcome = text(latest.get("outcome")).toUpperCase(Locale.ROOT);
        StageState stage = context.stage; WorkflowTask task = context.task;
        if (task.cancelRequested || "CANCELLED".equals(outcome)) {
            synchronized (stage) { stage.status = "CANCELLED"; }
            if (!task.cancelRequested) cancel(task, stageMessage("工作流阶段被节点取消: " + stage.name, latest));
        } else if ("FAILED".equals(outcome)) {
            abortStage(context, new IllegalStateException(stageMessage("工作流阶段失败: " + stage.name, latest))); return;
        } else synchronized (stage) { stage.status = "COMPLETED"; }
        disposeStage(context);
        if (context.store != null) context.store.updateTask(task.taskId, task.status, task.outcome, task.currentStage,
                taskProgress(task), task.spec.hosts().size());
        context.result.complete(latest);
    }

    private static String stageMessage(String prefix, Map<String, Object> latest) {
        String detail = text(latest.get("error"));
        if (detail.isEmpty()) {
            for (Map<String, Object> error : mapList(latest.get("errors"))) {
                detail = text(error.get("error"));
                if (!detail.isEmpty()) break;
            }
        }
        return detail.isEmpty() ? prefix : prefix + " · " + detail;
    }

    private void abortStage(StageExecution context, Exception error) {
        boolean interrupted = Thread.interrupted();
        try {
            if (context.childTaskId != null) {
                try { orchestrationService.stop(context.task.sessionId, context.childTaskId); }
                catch (RuntimeException ignored) { }
            }
        } finally {
            disposeStage(context); context.result.completeExceptionally(error);
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private void disposeStage(StageExecution context) {
        if (context.childTaskId != null) orchestrationService.onChange(context.task.sessionId, context.childTaskId, null);
        if (context.prepared != null && context.childTaskId != null) analysisService.release(context.childTaskId);
        synchronized (context.task.monitor) {
            if (context == context.task.execution) context.task.execution = null;
            if (context.childTaskId != null && context.childTaskId.equals(context.task.childTaskId)) context.task.childTaskId = null;
        }
        scheduler.retire(context.ticket);
    }

    private void wakeStage(WorkflowTask task) {
        StageExecution context = task.execution;
        if (context != null) scheduler.wake(context.ticket);
        else if (task.startup != null) scheduler.wake(task.startup);
    }

    private static String endpointKey(Map<String, Object> endpoint) {
        return text(endpoint.get("host")) + ":" + integer(endpoint.get("port"), -1);
    }

    private void updateStage(StageState stage, Map<String, Object> result,
                              int progressOffset, int progressTotal) {
        synchronized (stage) {
            stage.result = new LinkedHashMap<>(result);
            stage.total = integer(result.get("total"), integer(result.get("completed"), 0));
            stage.completed = Math.min(stage.total, integer(result.get("completed"), 0));
            stage.progress = boundedProgress(result.get("progress"), stage.total, stage.completed);
            if ("REACHABILITY".equals(stage.name) && result.get("analysis") instanceof Map<?, ?> analysis) {
                stage.total = integer(analysis.get("total"), stage.total);
                stage.completed = integer(analysis.get("completed"), stage.completed);
                stage.progress = stage.total == 0 ? 0 : stage.completed * 100 / stage.total;
            }
            if (progressTotal > 0) {
                stage.total = progressTotal;
                stage.completed = Math.min(progressTotal, progressOffset
                        + integer(result.get("completed"), 0));
                stage.progress = Math.min(100, stage.completed * 100 / progressTotal);
            }
        }
    }

    private void setStageProgress(StageState stage, int completed, int total) {
        synchronized (stage) {
            stage.total = Math.max(0, total);
            stage.completed = Math.max(0, Math.min(stage.total, completed));
            stage.progress = stage.total == 0 ? 0 : stage.completed * 100 / stage.total;
        }
    }

    private void skip(WorkflowTask task, StageState stage, String reason) {
        if (stage == null) return;
        synchronized (task.monitor) {
            if (stage.terminal()) return;
            stage.status = "SKIPPED";
            stage.progress = 100;
            stage.reason = reason;
            stage.result = new LinkedHashMap<>();
            stage.result.put("status", "STOPPED");
            stage.result.put("outcome", "SKIPPED");
            stage.result.put("total", Integer.valueOf(0));
            stage.result.put("completed", Integer.valueOf(0));
            stage.result.put("progress", Integer.valueOf(100));
            stage.result.put("reason", reason);
        }
    }

    private void complete(WorkflowTask task) {
        synchronized (task.monitor) {
            if (task.cancelRequested || task.terminal() || !"RUNNING".equals(task.outcome)) return;
            task.status = "STOPPED";
            task.outcome = "COMPLETED";
            task.currentStage = null;
            task.finishedAt = System.currentTimeMillis();
        }
        persistTaskState(task);
    }

    private void cancel(WorkflowTask task, String message) {
        synchronized (task.monitor) {
            if (task.terminal()) return;
            task.status = "STOPPED";
            task.outcome = "CANCELLED";
            task.currentStage = null;
            task.finishedAt = System.currentTimeMillis();
            task.error = message;
        }
        persistTaskState(task);
    }

    private void fail(WorkflowTask task, String message) {
        synchronized (task.monitor) {
            if (task.cancelRequested) return;
            String currentStage = task.currentStage;
            task.status = "STOPPED";
            task.outcome = "FAILED";
            task.currentStage = null;
            task.finishedAt = System.currentTimeMillis();
            task.error = message;
            StageState stage = currentStage == null ? null : task.stages.get(currentStage);
            if (stage != null && !stage.terminal()) stage.status = "FAILED";
        }
        persistTaskState(task);
    }

    private void persistTaskState(WorkflowTask task) {
        persistTaskState(task, true);
    }

    private void persistTaskState(WorkflowTask task, boolean force) {
        NetworkProbeResultStore store = resultStore;
        if (store == null) return;
        int progress;
        String status;
        String outcome;
        String currentStage;
        long now = System.currentTimeMillis();
        synchronized (task.monitor) {
            progress = taskProgress(task);
            status = task.status;
            outcome = task.outcome;
            currentStage = task.currentStage;
            boolean changed = progress != task.lastPersistedProgress
                    || !java.util.Objects.equals(status, task.lastPersistedStatus)
                    || !java.util.Objects.equals(outcome, task.lastPersistedOutcome)
                    || !java.util.Objects.equals(currentStage, task.lastPersistedStage);
            if (!force && !changed && now - task.lastPersistedAt < 1000L) return;
        }
        store.updateTask(task.taskId, status, outcome, currentStage, progress,
                task.spec.hosts().size(), task.error, stageSnapshots(task));
        synchronized (task.monitor) {
            task.lastPersistedAt = force ? System.currentTimeMillis() : now;
            task.lastPersistedProgress = progress;
            task.lastPersistedStatus = status;
            task.lastPersistedOutcome = outcome;
            task.lastPersistedStage = currentStage;
        }
    }

    private List<Map<String, Object>> stageSnapshots(WorkflowTask task) {
        synchronized (task.monitor) {
            List<Map<String, Object>> result = new ArrayList<>();
            for (StageState stage : task.stages.values()) {
                synchronized (stage) {
                    Map<String, Object> item = stageSummary(stage);
                    for (String key : List.of("backpressured", "nodeBackpressured", "pendingItems", "pendingBytes"))
                        if (stage.result.containsKey(key)) item.put(key, stage.result.get(key));
                    if (stage.reason != null) item.put("reason", stage.reason);
                    result.add(item);
                }
            }
            return result;
        }
    }

    /** Caller holds the stage monitor while adding snapshot-specific fields. */
    private Map<String, Object> stageSummary(StageState stage) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("name", stage.name);
        item.put("status", stage.status);
        item.put("progress", Integer.valueOf(stage.progress));
        item.put("total", Integer.valueOf(stage.total));
        item.put("completed", Integer.valueOf(stage.completed));
        if ("REACHABILITY".equals(stage.name) && stage.result.get("analysis") instanceof Map<?, ?> analysis) {
            for (String key : List.of("reachableHostCount", "unknownHostCount", "unreachableHostCount", "pendingHostCount"))
                item.put(key, integer(analysis.get(key), 0));
        }
        if ("FINGERPRINT".equals(stage.name) && stage.result.get("analysis") instanceof Map<?, ?> analysis) {
            for (String key : List.of("logicalRequestCount", "networkRequestCount", "savedRequestCount"))
                if (analysis.containsKey(key)) item.put(key, analysis.get(key));
        }
        return item;
    }

    private static Map<String, Object> durableConfig(WorkflowSpec spec) {
        Map<String, Object> config = new LinkedHashMap<>();
        if (!spec.name().isEmpty()) config.put("name", spec.name());
        config.put("hosts", spec.hosts());
        config.put("ports", spec.ports());
        config.put("timeout", Integer.valueOf(spec.timeout()));
        config.put("threads", Integer.valueOf(spec.threads()));
        config.put("stages", spec.stages().stream().map(Enum::name).toList());
        if (!spec.sourceTaskId().isEmpty()) config.put("sourceTaskId", spec.sourceTaskId());
        if (spec.debug()) config.put("debug", true);
        // Preserve original hostnames and application paths for later supplemental scans.
        config.put("targets", spec.targets());
        if (!spec.fingerprintRules().isEmpty()) {
            config.put("fingerprintRules", spec.fingerprintRules());
        }
        return config;
    }

    private int taskProgress(WorkflowTask task) {
        synchronized (task.monitor) {
            int progress = 0;
            for (StageState stage : task.stages.values()) {
                progress += "COMPLETED".equals(stage.status) || "SKIPPED".equals(stage.status)
                        ? 100 : stage.progress;
            }
            return "COMPLETED".equals(task.outcome) ? 100
                    : Math.min(99, progress / task.stages.size());
        }
    }

    private Map<String, Object> snapshot(WorkflowTask task) {
        return snapshot(task, true);
    }

    private Map<String, Object> snapshot(WorkflowTask task, boolean includeResults) {
        synchronized (task.monitor) {
            List<Map<String, Object>> stageSnapshots = new ArrayList<>();
            int completedStages = 0;
            for (StageState stage : task.stages.values()) {
                synchronized (stage) {
                    Map<String, Object> item = stageSummary(stage);
                    if (stage.backendTaskId != null) item.put("backendTaskId", stage.backendTaskId);
                    if (stage.reason != null) item.put("reason", stage.reason);
                    if (includeResults && !stage.result.isEmpty()) item.put("result", new LinkedHashMap<>(stage.result));
                    stageSnapshots.add(item);
                }
                if ("COMPLETED".equals(stage.status) || "SKIPPED".equals(stage.status)) completedStages++;
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("taskId", task.taskId);
            result.put("scanKind", KIND);
            result.put("status", task.status);
            result.put("outcome", task.outcome);
            result.put("currentStage", task.currentStage);
            result.put("stageCount", Integer.valueOf(task.stages.size()));
            result.put("completedStageCount", Integer.valueOf(completedStages));
            result.put("progress", Integer.valueOf(taskProgress(task)));
            result.put("stages", stageSnapshots);
            result.put("targetCount", Integer.valueOf(task.spec.hosts().size()));
            StageState reachability = task.stages.get("REACHABILITY");
            if (reachability != null) {
                Map<String, Object> stats = copyMapOrEmpty(reachability.result.get("analysis"));
                int unknown = integer(stats.get("unknownHostCount"), 0);
                int unreachable = integer(stats.get("unreachableHostCount"), 0);
                result.put("unknownHostCount", unknown); result.put("unreachableHostCount", unreachable);
                result.put("pendingHostCount", integer(stats.get("pendingHostCount"), task.spec.hosts().size()));
                result.put("skippedHostCount", task.stages.containsKey("PORT_SCAN") ? unknown + unreachable : 0);
                result.put("portScanHostCount", task.stages.containsKey("PORT_SCAN") ? task.confirmedHosts.size() : 0);
            }
            List<Map<String, Object>> open = openPortResults(task);
            if (includeResults) {
                result.put("hosts", task.spec.hosts());
                result.put("ports", task.spec.ports());
                result.put("reachableHostList", new ArrayList<>(task.confirmedHosts));
                result.put("openPortResults", mapList(open));
                result.put("serviceResults", mapList(open.stream()
                        .filter(endpoint -> Boolean.TRUE.equals(endpoint.get("serviceIdentified"))).toList()));
            } else {
                result.put("reachableHostCount", task.confirmedHosts.size());
                result.put("reachableHostList", task.confirmedHosts.stream().limit(160).toList());
                result.put("openCount", open.size());
                int serviceCount = 0;
                int matches = 0;
                Set<String> applications = new LinkedHashSet<>();
                for (Map<String, Object> endpoint : open) {
                    if (Boolean.TRUE.equals(endpoint.get("serviceIdentified"))) serviceCount++;
                    if (endpoint.get("fingerprint") instanceof Map<?, ?> fingerprint
                            && fingerprint.get("components") instanceof List<?> components) {
                        matches += components.size();
                        for (Object item : components) if (item instanceof Map<?, ?> match)
                            applications.add(text(match.get("targetId")));
                    }
                }
                result.put("serviceCount", serviceCount);
                result.put("fingerprintCount", matches);
                result.put("identifiedApplicationCount", (long) applications.size());
                result.put("resultAvailable", !open.isEmpty());
            }
            if (!task.spec.name().isEmpty()) result.put("name", task.spec.name());
            result.put("createdAt", Long.valueOf(task.createdAt));
            if (task.error != null) result.put("error", task.error);
            if (task.finishedAt > 0L) result.put("finishedAt", Long.valueOf(task.finishedAt));
            return result;
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> openPortResults(WorkflowTask task) {
        for (String name : List.of("FINGERPRINT", "SERVICE_PROBE", "PORT_SCAN")) {
            StageState stage = task.stages.get(name);
            if (stage == null) continue;
            synchronized (stage) {
                Object value = stage.result.get("openPortResults");
                // Published rows are immutable to the worker; full API snapshots copy them before returning.
                if (value instanceof List<?> list) return (List<Map<String, Object>>) list;
            }
        }
        return List.of();
    }

    private boolean ownsTask(String sessionId, WorkflowTask task) {
        return resultStore != null ? resultStore.ownsTask(sessionId, task.taskId)
                : task.sessionId.equals(sessionId.trim());
    }

    private WorkflowTask requireTask(String sessionId, String taskId) {
        if (sessionId == null || sessionId.isBlank()) throw new IllegalArgumentException("sessionId不能为空");
        if (taskId == null || taskId.isBlank()) throw new IllegalArgumentException("taskId不能为空");
        WorkflowTask task = tasks.get(taskId.trim());
        if (task == null || !ownsTask(sessionId, task)) {
            throw ApiException.notFound("扫描工作流任务不存在或不属于当前会话");
        }
        return task;
    }

    private void cleanup() {
        long now = System.currentTimeMillis();
        tasks.entrySet().removeIf(entry -> entry.getValue().terminal()
                && entry.getValue().finishedAt > 0L
                && now - entry.getValue().finishedAt > TASK_TTL_MS);
    }

    private List<Map<String, Object>> fingerprintTargets(WorkflowSpec spec, List<Map<String, Object>> endpoints) {
        Map<String, Map<String, Object>> configured = new LinkedHashMap<>();
        for (Map<String, Object> target : spec.targets()) configured.put(endpointKey(target), target);
        Map<String, Map<String, Object>> applications = new LinkedHashMap<>();
        for (Map<String, Object> endpoint : endpoints) {
            Map<String, Object> original = configured.getOrDefault(endpointKey(endpoint), Map.of());
            List<Map<String, Object>> inputs = mapList(original.get("applications"));
            if (inputs.isEmpty() && original.containsKey("baseUrl")) inputs = List.of(Map.of("baseUrl", original.get("baseUrl")));
            if (inputs.isEmpty()) inputs = List.of(Map.of("hostname", endpoint.get("host")));
            String service = text(endpoint.get("service"));
            for (Map<String, Object> input : inputs) {
                String url = text(input.get("baseUrl"));
                if (url.isEmpty()) {
                    if (!List.of("http", "https").contains(service)) continue;
                    url = service + "://" + hostForUrl(text(input.get("hostname"))) + ":" + endpoint.get("port") + "/";
                }
                try {
                    java.net.URI uri = java.net.URI.create(url);
                    if (!List.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null)
                        throw new IllegalArgumentException("无效的应用 URL");
                    String path = uri.getRawPath();
                    if (path == null || path.isEmpty()) path = "/";
                    if (!path.endsWith("/")) path += "/";
                    url = uri.getScheme() + "://" + uri.getRawAuthority() + path;
                    Map<String, Object> target = new LinkedHashMap<>();
                    target.put("host", endpoint.get("host"));
                    target.put("port", endpoint.get("port"));
                    target.put("protocol", uri.getScheme());
                    target.put("baseUrl", url);
                    String id = endpointKey(endpoint) + "|" + url;
                    target.put("targetId", id);
                    applications.putIfAbsent(id, target);
                } catch (IllegalArgumentException error) {
                    throw new IllegalArgumentException("无法创建组件识别目标: " + url, error);
                }
            }
        }
        return new ArrayList<>(applications.values());
    }

    private void applyFingerprintMatches(List<Map<String, Object>> endpoints, List<Map<String, Object>> matches) {
        Map<String, List<Map<String, Object>>> byEndpoint = new LinkedHashMap<>();
        for (Map<String, Object> match : matches)
            byEndpoint.computeIfAbsent(endpointKey(match), ignored -> new ArrayList<>()).add(match);
        for (Map<String, Object> endpoint : endpoints) {
            List<Map<String, Object>> results = byEndpoint.get(endpointKey(endpoint));
            if (results == null) continue;
            Map<String, Object> fingerprint = new LinkedHashMap<>();
            fingerprint.put("status", results.stream().anyMatch(result -> "PENDING".equals(result.get("status")))
                    ? "RUNNING" : results.stream().anyMatch(result -> "CANCELLED".equals(result.get("status"))) ? "CANCELLED" : "COMPLETED");
            fingerprint.put("components", results.stream().filter(result -> Boolean.TRUE.equals(result.get("matched"))).toList());
            fingerprint.put("completed", results.stream().filter(result -> Boolean.TRUE.equals(result.get("complete"))).count());
            fingerprint.put("total", results.size());
            fingerprint.put("errorCount", results.stream().filter(result -> "ERROR".equals(result.get("status"))).count());
            fingerprint.put("inconclusiveCount", results.stream().filter(result -> "INCONCLUSIVE".equals(result.get("status"))).count());
            endpoint.put("fingerprint", fingerprint);
        }
    }

    private Map<String, Object> reachabilityScan(WorkflowSpec spec) {
        Map<String, Object> scan = new LinkedHashMap<>();
        scan.put("kind", "reachability");
        scan.put("hosts", spec.hosts());
        scan.put("targets", spec.reachabilityTargets());
        scan.put("timeout", Integer.valueOf(spec.timeout()));
        scan.put("threads", Integer.valueOf(spec.threads()));
        return scan;
    }

    private Map<String, Object> portPlan(WorkflowSpec spec, Set<String> hosts) {
        List<Map<String, Object>> targets = new ArrayList<>();
        for (Map<String, Object> target : spec.targets()) {
            if (hosts.contains(text(target.get("host")))) {
                targets.add(copyMap(target));
            }
        }
        return probePlan(targets, "tcp-connect", spec);
    }

    private Map<String, Object> servicePlan(WorkflowSpec spec, List<Map<String, Object>> endpoints) {
        List<Map<String, Object>> targets = new ArrayList<>();
        Integer timeout = Math.min(spec.timeout(), SERVICE_BANNER_TIMEOUT_MS);
        for (Map<String, Object> endpoint : endpoints) {
            Map<String, Object> target = new LinkedHashMap<>(endpoint);
            target.put("protocol", "tcp");
            target.remove("service");
            target.remove("serviceIdentified");
            target.put("stage", "tcp-exchange");
            target.put("timeout", timeout);
            targets.add(target);
        }
        return probePlan(targets, "tcp-exchange", spec);
    }

    private Map<String, Object> serviceHttpPlan(WorkflowSpec spec, List<Map<String, Object>> endpoints,
                                                boolean forceHttps) {
        List<Map<String, Object>> targets = new ArrayList<>();
        Integer timeout = Math.min(spec.timeout(), HTTP_IDENTIFICATION_TIMEOUT_MS);
        for (Map<String, Object> endpoint : endpoints) {
            int port = integer(endpoint.get("port"), -1);
            String scheme = forceHttps ? "https" : httpProbeScheme(port);
            Map<String, Object> target = new LinkedHashMap<>(endpoint);
            target.put("protocol", scheme);
            target.put("baseUrl", scheme + "://" + hostForUrl(text(endpoint.get("host"))) + ":" + port + "/");
            target.put("stage", "http-head");
            target.put("timeout", timeout);
            targets.add(target);
        }
        return probePlan(targets, "http-head", spec);
    }

    private List<Map<String, Object>> httpCandidates(List<Map<String, Object>> endpoints) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> endpoint : endpoints) {
            String service = text(endpoint.get("service")).toLowerCase(Locale.ROOT);
            if (isDefinitiveNonHttpService(service) || "http".equals(service) || "https".equals(service)) {
                continue;
            }
            result.add(endpoint);
        }
        return result;
    }

    private List<Map<String, Object>> httpsCandidates(List<Map<String, Object>> candidates) {
        // Candidates retain the live endpoint maps updated by the HTTP stage.
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> endpoint : candidates) {
            if (isDefaultHttpsPort(integer(endpoint.get("port"), -1))) continue;
            String service = text(endpoint.get("service")).toLowerCase(Locale.ROOT);
            if (service.isEmpty() || "unknown".equals(service)) result.add(endpoint);
        }
        return result;
    }

    private Map<String, Object> probePlan(List<Map<String, Object>> targets, String stage, WorkflowSpec spec) {
        Map<String, Object> limits = new LinkedHashMap<>();
        limits.put("timeout", Integer.valueOf(spec.timeout()));
        limits.put("threads", Integer.valueOf(Math.min(spec.threads(), Math.max(1, targets.size()))));
        limits.put("maxReadBytes", Integer.valueOf(NetworkProbeLimits.NODE_MAX_READ_BYTES));
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("targets", new ArrayList<>(targets));
        plan.put("stages", new ArrayList<>(List.of(stage)));
        plan.put("limits", limits);
        return plan;
    }

    private static void collectOpenEndpoints(Map<String, Map<String, Object>> byEndpoint,
                                      Collection<Map<String, Object>> observations) {
        for (Map<String, Object> observation : observations) {
            if (!"tcp-connect".equals(text(observation.get("stage")))
                    || !"open".equalsIgnoreCase(text(observation.get("state")))) continue;
            String host = text(observation.get("host"));
            int port = integer(observation.get("port"), -1);
            if (host.isEmpty() || port < 1) continue;
            Map<String, Object> endpoint = new LinkedHashMap<>();
            endpoint.put("host", host);
            endpoint.put("port", Integer.valueOf(port));
            endpoint.put("protocol", "tcp");
            endpoint.put("state", "open");
            endpoint.put("endpointId", "tcp|" + host + "|" + port);
            endpoint.put("discoveredAt", Long.valueOf(System.currentTimeMillis()));
            if (observation.get("latencyMs") != null) endpoint.put("responseTime", observation.get("latencyMs"));
            endpoint.put("probe", "connect");
            byEndpoint.putIfAbsent(host + ":" + port, endpoint);
        }
    }

    private void applyServiceEvidence(Map<String, Map<String, Object>> endpointsByKey,
                                      Collection<Map<String, Object>> observations) {
        for (Map<String, Object> observation : observations) {
            Map<String, Object> endpoint = endpointsByKey.get(endpointKey(observation));
            if (endpoint != null) applyServiceObservation(endpoint, observation);
        }
    }

    private void applyServiceObservation(Map<String, Object> endpoint, Map<String, Object> observation) {
        String stage = text(observation.get("stage"));
        endpoint.put("probe", stage);
        Map<String, Object> evidence = copyMapOrEmpty(observation.get("evidence"));
        if (!evidence.isEmpty()) endpoint.put("evidence", evidence);
        if (evidence.get("banner") != null) endpoint.put("banner", evidence.get("banner"));
        if (isHttpObservation(stage, evidence)) {
            endpoint.put("statusCode", evidence.get("statusCode"));
            int port = integer(endpoint.get("port"), -1);
            String observedProtocol = text(observation.get("protocol")).toLowerCase(Locale.ROOT);
            String scheme = "https".equals(observedProtocol) ? "https" : httpProbeScheme(port);
            endpoint.put("protocol", scheme);
            endpoint.put("service", scheme);
            endpoint.put("confidence", Double.valueOf(0.9));
        }
        String detectedService = detectService(text(evidence.get("banner")));
        if (!detectedService.isEmpty() && endpoint.get("service") == null) {
            endpoint.put("service", detectedService);
            if ("http".equals(detectedService)) endpoint.put("protocol", "http");
            endpoint.put("confidence", Double.valueOf(0.7));
        }
        if (endpoint.get("service") != null && !text(endpoint.get("service")).isEmpty()) {
            endpoint.put("serviceIdentified", Boolean.TRUE);
        }
        for (String key : List.of("server", "location", "contentType", "responseSize")) {
            if (evidence.get(key) != null) endpoint.put(key, evidence.get(key));
        }
        if (evidence.get("title") != null && !text(evidence.get("title")).isEmpty()) {
            endpoint.put("title", evidence.get("title"));
        }
        if (observation.get("error") != null) endpoint.put("probeError", observation.get("error"));
    }

    private void enrichPortResult(StageState stage, Collection<Map<String, Object>> endpoints) {
        synchronized (stage) {
            // Published snapshots must not share the worker's mutable endpoint maps.
            List<Map<String, Object>> results = new ArrayList<>(endpoints.size());
            List<Object> ports = new ArrayList<>(endpoints.size());
            for (Map<String, Object> endpoint : endpoints) {
                results.add(copyMap(endpoint));
                ports.add(endpoint.get("port"));
            }
            stage.result.put("openPortResults", results);
            stage.result.put("openPortList", ports);
        }
    }

    private static String detectService(String banner) {
        String value = banner == null ? "" : banner.toLowerCase(Locale.ROOT);
        if (value.startsWith("ssh-") || value.contains("openssh")) return "ssh";
        if (value.contains("ftp")) return "ftp";
        if (value.contains("mysql") || value.contains("mariadb")) return "mysql";
        // MySQL sends a binary handshake. After control bytes are sanitized,
        // the server version and authentication plugin remain while the word
        // "mysql" is often absent (for example: 8.0.43 + caching_sha2_password).
        if ((value.contains("caching_sha2_password") || value.contains("sha256_password"))
                && MYSQL_VERSION.matcher(value).find()) return "mysql";
        if (value.contains("redis")) return "redis";
        if (value.contains("mongodb")) return "mongodb";
        if (value.contains("postgres")) return "postgresql";
        if (value.contains("smtp")) return "smtp";
        if (value.contains("http/1.") || value.contains("server:")) return "http";
        return "";
    }

    private static String httpProbeScheme(int port) {
        return isDefaultHttpsPort(port) ? "https" : "http";
    }

    private static boolean isDefaultHttpsPort(int port) {
        return port == 443 || port == 8443;
    }

    private static boolean isHttpObservation(String stage, Map<String, Object> evidence) {
        if (!"http-head".equals(stage) && !"http-request".equals(stage)) return false;
        int statusCode = integer(evidence.get("statusCode"), -1);
        return statusCode >= 100 && statusCode <= 599;
    }

    private static boolean isDefinitiveNonHttpService(String service) {
        return switch (service) {
            case "ssh", "ftp", "mysql", "mariadb", "redis", "mongodb", "postgresql", "postgres", "smtp" -> true;
            default -> false;
        };
    }

    private static String hostForUrl(String host) {
        return host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host;
    }

    private static int boundedProgress(Object value, int total, int completed) {
        int progress = integer(value, total > 0 ? completed * 100 / total : 0);
        return Math.max(0, Math.min(100, progress));
    }

    private static Map<String, Object> response(Map<String, Object> values) {
        Map<String, Object> result = new LinkedHashMap<>(values);
        result.put("code", Integer.valueOf(200));
        return result;
    }

    private static List<Map<String, Object>> mapList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) if (item instanceof Map<?, ?> raw) result.add(copyMapOrEmpty(raw));
        return result;
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof Collection<?> collection)) return List.of();
        List<String> result = new ArrayList<>();
        for (Object item : collection) {
            String text = text(item);
            if (!text.isEmpty()) result.add(text);
        }
        return result;
    }

    private static List<Map<String, Object>> annotateStage(List<Map<String, Object>> values, String stage) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> value : values) {
            Map<String, Object> annotated = new LinkedHashMap<>(value);
            annotated.put("workflowStage", stage);
            result.add(annotated);
        }
        return result;
    }

    private static int integer(Object value, int fallback) {
        if (value instanceof Number number) return number.intValue();
        try {
            return value == null ? fallback : Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static long longValue(Object value, long fallback) {
        if (value instanceof Number number) return number.longValue();
        try {
            return value == null ? fallback : Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String messageOf(Exception error) {
        return error.getMessage() == null || error.getMessage().isBlank()
                ? error.getClass().getSimpleName() : error.getMessage();
    }

    private static ThreadFactory daemonThreadFactory() {
        AtomicInteger sequence = new AtomicInteger();
        return task -> {
            Thread thread = new Thread(task, "network-probe-workflow-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    @Override
    public void close() {
        for (WorkflowTask task : tasks.values()) {
            if (task.terminal()) continue;
            synchronized (task.monitor) {
                task.cancelRequested = true;
            }
            cancel(task, "扫描工作流服务关闭");
            StageExecution context = task.execution;
            if (context != null) abortStage(context, new InterruptedException("扫描工作流服务关闭"));
            else task.finished.countDown();
        }
        scheduler.close();
    }

    private record WorkflowSpec(List<String> hosts, List<Integer> ports,
                                List<Map<String, Object>> targets, int timeout, int threads,
                                List<ScanStage> stages, String name,
                                List<Map<String, Object>> reachabilityTargets,
                                List<Map<String, Object>> fingerprintRules, List<Map<String, Object>> seedEndpoints,
                                String sourceTaskId, boolean debug) { }

    private static final class WorkflowTask {
        private final Set<String> confirmedHosts = new LinkedHashSet<>();
        private final Set<String> plannedEndpointKeys = new LinkedHashSet<>();
        private final Map<String, Map<String, Object>> reachabilityCache = new LinkedHashMap<>();
        private final String taskId;
        private final String sessionId;
        private final NetworkProbeCapable node;
        private final WorkflowSpec spec;
        private final long createdAt = System.currentTimeMillis();
        private final Object monitor = new Object();
        private final CountDownLatch finished = new CountDownLatch(1);
        private final Map<String, StageState> stages = new LinkedHashMap<>();
        private volatile String status = "RUNNING";
        private volatile String outcome = "RUNNING";
        private volatile String currentStage;
        private volatile String childTaskId;
        private volatile boolean cancelRequested;
        private volatile String error;
        private volatile long finishedAt;
        private NetworkProbeScheduler.Ticket startup;
        private volatile StageExecution execution;
        private long lastPersistedAt;
        private int lastPersistedProgress = -1;
        private String lastPersistedStatus;
        private String lastPersistedOutcome;
        private String lastPersistedStage;

        private WorkflowTask(String taskId, String sessionId, NetworkProbeCapable node, WorkflowSpec spec) {
            this.taskId = taskId;
            this.sessionId = sessionId;
            this.node = node;
            this.spec = spec;
            for (Map<String, Object> target : spec.targets()) plannedEndpointKeys.add(endpointKey(target));
            for (ScanStage stage : spec.stages()) stages.put(stage.name(), new StageState(stage.name()));
            this.currentStage = spec.stages().get(0).name();
        }

        private boolean terminal() {
            return "STOPPED".equals(status);
        }
    }

    private static final class StageExecution {
        private final WorkflowTask task;
        private final StageState stage;
        private final Map<String, Object> plan;
        private final NetworkProbeAnalysisService.PreparedScan prepared;
        private final List<Map<String, Object>> liveEndpoints;
        private final int offset, total;
        private final NetworkProbeResultStore store;
        private final CompletableFuture<Map<String, Object>> result = new CompletableFuture<>();
        private final Map<String, Map<String, Object>> endpointsByKey = new LinkedHashMap<>();
        private NetworkProbeScheduler.Ticket ticket;
        private String childTaskId;
        private long observationCursor, errorCursor;
        private int persistenceFailures;
        private StageExecution(WorkflowTask task, StageState stage, Map<String, Object> plan,
                NetworkProbeAnalysisService.PreparedScan prepared, List<Map<String, Object>> endpoints,
                int offset, int total, NetworkProbeResultStore store) {
            this.task = task; this.stage = stage; this.plan = plan; this.prepared = prepared;
            this.liveEndpoints = endpoints; this.offset = offset; this.total = total; this.store = store;
            if ("PORT_SCAN".equals(stage.name)) {
                collectOpenEndpoints(endpointsByKey, task.reachabilityCache.values());
            }
            // Port discovery has already deduplicated these live endpoint maps.
            if ("SERVICE_PROBE".equals(stage.name) && endpoints != null) {
                for (var endpoint : endpoints) endpointsByKey.put(endpointKey(endpoint), endpoint);
            }
        }
    }

    private static final class StageState {
        private final String name;
        private String status = "PENDING";
        private String backendTaskId;
        private String reason;
        private int total;
        private int completed;
        private int progress;
        private Map<String, Object> result = new LinkedHashMap<>();

        private StageState(String name) {
            this.name = name;
        }

        private boolean terminal() {
            return Set.of("COMPLETED", "SKIPPED", "FAILED", "CANCELLED").contains(status);
        }
    }
}
