package org.leo.web.service;

import org.leo.core.puppet.capability.NetworkProbeCapable;
import org.leo.web.exception.ApiException;
import org.leo.web.service.discovery.NetworkProbeLimits;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs the discovery workflow as one user-visible task.
 *
 * <p>The existing orchestration service remains responsible for node batching
 * and transport lifecycle. This service only owns stage transitions and the
 * derived targets passed from one stage to the next.</p>
 */
@Service
public final class NetworkProbeWorkflowService implements AutoCloseable {

    public static final String KIND = "network-workflow";
    private static final long TASK_TTL_MS = 30L * 60L * 1000L;
    private static final long DEFAULT_POLL_INTERVAL_MS = 100L;
    private static final List<String> STAGE_NAMES = Collections.unmodifiableList(Arrays.asList(
            "REACHABILITY", "PORT_SCAN", "SERVICE_PROBE", "RECON"));
    private static final Set<Integer> HTTP_PORTS = new LinkedHashSet<>(Arrays.asList(
            Integer.valueOf(80), Integer.valueOf(81), Integer.valueOf(443), Integer.valueOf(8000),
            Integer.valueOf(8008), Integer.valueOf(8080), Integer.valueOf(8081), Integer.valueOf(8088),
            Integer.valueOf(8443), Integer.valueOf(8888), Integer.valueOf(9000), Integer.valueOf(9090)));

    private final NetworkProbeAnalysisService analysisService;
    private final NetworkProbeOrchestrationService orchestrationService;
    private volatile NetworkProbeResultStore resultStore;
    private final Map<String, WorkflowTask> tasks = new java.util.concurrent.ConcurrentHashMap<>();
    private final ExecutorService executor;
    private final long pollIntervalMs;

    @Autowired
    public NetworkProbeWorkflowService(NetworkProbeAnalysisService analysisService,
                                       NetworkProbeOrchestrationService orchestrationService) {
        this(analysisService, orchestrationService, new ThreadPoolExecutor(
                2,
                2,
                60L,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(22),
                daemonThreadFactory(),
                new ThreadPoolExecutor.AbortPolicy()), DEFAULT_POLL_INTERVAL_MS);
    }

    NetworkProbeWorkflowService(NetworkProbeAnalysisService analysisService,
                                NetworkProbeOrchestrationService orchestrationService,
                                ExecutorService executor, long pollIntervalMs) {
        this.analysisService = analysisService;
        this.orchestrationService = orchestrationService;
        this.executor = executor;
        this.pollIntervalMs = Math.max(10L, pollIntervalMs);
    }

    @Autowired(required = false)
    public void setResultStore(NetworkProbeResultStore resultStore) {
        this.resultStore = resultStore;
    }

    public Map<String, Object> start(String sessionId, NetworkProbeCapable node, Map<String, Object> request) {
        WorkflowSpec spec = validate(request);
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
        try {
            task.future = executor.submit(() -> run(task));
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

    public Map<String, Object> query(String sessionId, String taskId) {
        cleanup();
        return response(Map.of("result", snapshot(requireTask(sessionId, taskId))));
    }

    /** Compact task state for polling; result rows are fetched through results/query. */
    public Map<String, Object> querySummary(String sessionId, String taskId) {
        cleanup();
        Map<String, Object> full = snapshot(requireTask(sessionId, taskId));
        return response(Map.of("result", compactSnapshot(full)));
    }

    public Map<String, Object> querySummaryIfPresent(String sessionId, String taskId) {
        cleanup();
        if (sessionId == null || sessionId.isBlank() || taskId == null || taskId.isBlank()) {
            return Map.of();
        }
        WorkflowTask task = tasks.get(taskId.trim());
        if (task == null || !task.sessionId.equals(sessionId.trim())) return Map.of();
        return compactSnapshot(snapshot(task));
    }

    public List<Map<String, Object>> listSummaries(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) throw new IllegalArgumentException("sessionId不能为空");
        cleanup();
        return tasks.values().stream()
                .filter(task -> task.sessionId.equals(sessionId.trim()))
                .sorted((left, right) -> Long.compare(right.createdAt, left.createdAt))
                .map(task -> compactSnapshot(snapshot(task)))
                .toList();
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
        if (childTaskId != null) orchestrationService.pause(sessionId, childTaskId);
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
            task.monitor.notifyAll();
        }
        if (childTaskId != null) orchestrationService.resume(sessionId, childTaskId);
        return response(Map.of("status", "RUNNING"));
    }

    public Map<String, Object> stop(String sessionId, String taskId) {
        WorkflowTask task = requireTask(sessionId, taskId);
        String childTaskId;
        synchronized (task.monitor) {
            if (!task.terminal()) {
                task.cancelRequested = true;
                task.status = "STOPPED";
                task.outcome = "CANCELLED";
                task.finishedAt = System.currentTimeMillis();
                StageState stage = task.currentStage == null ? null : task.stages.get(task.currentStage);
                if (stage != null && !stage.terminal()) stage.status = "CANCELLED";
                task.monitor.notifyAll();
            }
            childTaskId = task.childTaskId;
        }
        if (childTaskId != null) orchestrationService.stop(sessionId, childTaskId);
        try {
            task.finished.await(5L, TimeUnit.SECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
        return response(Map.of("status", "STOPPED"));
    }

    private void run(WorkflowTask task) {
        try {
            StageState reachability = task.stages.get("REACHABILITY");
            Map<String, Object> reachabilityScan = reachabilityScan(task.spec);
            NetworkProbeAnalysisService.PreparedScan reachabilityPrepared = analysisService.prepare(reachabilityScan);
            Map<String, Object> reachabilityResult = executeStage(task, reachability,
                    reachabilityPrepared.plan(), reachabilityPrepared, null);
            if (task.cancelRequested || task.terminal()) return;

            Map<String, Object> reachabilityAnalysis = map(reachabilityResult.get("analysis"));
            List<String> reachableHosts = stringList(reachabilityAnalysis.get("reachableHostList"));
            LinkedHashSet<String> portHosts = new LinkedHashSet<>(reachableHosts);
            if (portHosts.isEmpty()) {
                skip(task, task.stages.get("PORT_SCAN"), "NO_REACHABLE_HOSTS");
                skip(task, task.stages.get("SERVICE_PROBE"), "NO_REACHABLE_HOSTS");
                skip(task, task.stages.get("RECON"), "NO_REACHABLE_HOSTS");
                complete(task);
                return;
            }

            StageState portScan = task.stages.get("PORT_SCAN");
            Map<String, Object> portResult = executeStage(task, portScan,
                    portPlan(task.spec, new ArrayList<>(portHosts)), null, null);
            if (task.cancelRequested || task.terminal()) return;
            List<Map<String, Object>> openEndpoints = openEndpoints(portResult);
            enrichPortResult(portScan, openEndpoints);

            StageState serviceProbe = task.stages.get("SERVICE_PROBE");
            if (openEndpoints.isEmpty()) {
                skip(task, serviceProbe, "NO_OPEN_PORTS");
                skip(task, task.stages.get("RECON"), "NO_OPEN_PORTS");
                complete(task);
                return;
            }
            if (!task.spec.probeServices()) {
                skip(task, serviceProbe, "DISABLED");
            } else {
                Map<String, Object> serviceResult = executeStage(task, serviceProbe,
                        servicePlan(task.spec, openEndpoints), null, openEndpoints);
                if (task.cancelRequested || task.terminal()) return;
                applyServiceEvidence(openEndpoints, serviceResult);
                enrichPortResult(serviceProbe, openEndpoints);
                enrichPortResult(portScan, openEndpoints);
            }

            if (task.cancelRequested || task.terminal()) return;
            StageState recon = task.stages.get("RECON");
            if (!task.spec.probeServices()) {
                skip(task, recon, "DISABLED");
                complete(task);
                return;
            }
            try {
                Map<String, Object> reconScan = reconPlan(task.spec, openEndpoints);
                NetworkProbeAnalysisService.PreparedScan prepared = analysisService.prepare(reconScan);
                Map<String, Object> reconResult = executeStage(task, recon, prepared.plan(), prepared, openEndpoints);
                applyFingerprintMatches(openEndpoints, map(reconResult.get("analysis")));
                enrichPortResult(recon, openEndpoints);
                if (resultStore != null && !resultStore.updateFingerprints(task.taskId, openEndpoints)) {
                    throw new IllegalStateException("指纹分析结果持久化失败");
                }
            } catch (IllegalArgumentException error) {
                if (!hasExplicitRules(task.spec.ruleSelector())
                        && isAutomaticReconNoop(error.getMessage())) {
                    skip(task, recon, "NO_APPLICABLE_RULES");
                } else {
                    throw error;
                }
            }
            if (!task.cancelRequested && !task.terminal()) complete(task);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            fail(task, "扫描工作流被中断");
        } catch (Exception error) {
            fail(task, messageOf(error));
        } finally {
            task.finished.countDown();
        }
    }

    private Map<String, Object> executeStage(WorkflowTask task, StageState stage,
                                             Map<String, Object> plan,
                                             NetworkProbeAnalysisService.PreparedScan prepared,
                                             List<Map<String, Object>> liveEndpoints)
            throws Exception {
        awaitRunnable(task);
        synchronized (task.monitor) {
            task.currentStage = stage.name;
            stage.status = "RUNNING";
        }
        Map<String, Object> started = orchestrationService.start(task.sessionId, task.node, plan);
        String childTaskId = text(started.get("taskId"));
        if (childTaskId.isEmpty()) throw new IllegalStateException("工作流阶段未返回 taskId: " + stage.name);
        if (prepared != null) analysisService.register(childTaskId, prepared);
        synchronized (task.monitor) {
            task.childTaskId = childTaskId;
            stage.backendTaskId = childTaskId;
        }
        synchronized (task.monitor) {
            if (task.cancelRequested) orchestrationService.stop(task.sessionId, childTaskId);
            else if ("PAUSED".equals(task.status)) orchestrationService.pause(task.sessionId, childTaskId);
        }

        Map<String, Object> latest = Map.of();
        long observationCursor = 0L;
        long errorCursor = 0L;
        int persistenceFailures = 0;
        Map<String, Map<String, Object>> openEndpointsByKey = new LinkedHashMap<>();
        Map<String, Map<String, Object>> serviceObservationsByKey = new LinkedHashMap<>();
        NetworkProbeResultStore store = resultStore;
        try {
            while (true) {
                if (task.cancelRequested) orchestrationService.stop(task.sessionId, childTaskId);
                Map<String, Object> queried = orchestrationService.querySince(task.sessionId, childTaskId,
                        observationCursor, errorCursor);
                if (prepared != null) analysisService.enrich(childTaskId, queried);
                latest = map(queried.get("result"));
                if (latest.isEmpty()) throw new IllegalStateException("工作流阶段结果为空: " + stage.name);
                updateStage(stage, latest);
                List<Map<String, Object>> observations = mapList(latest.get("observations"));
                List<Map<String, Object>> errors = mapList(latest.get("errors"));
                if ("PORT_SCAN".equals(stage.name)) {
                    for (Map<String, Object> endpoint : openEndpoints(Map.of("observations", observations))) {
                        openEndpointsByKey.putIfAbsent(endpointKey(endpoint), endpoint);
                    }
                    enrichPortResult(stage, new ArrayList<>(openEndpointsByKey.values()));
                } else if ("SERVICE_PROBE".equals(stage.name)) {
                    for (Map<String, Object> observation : observations) {
                        String key = text(observation.get("stage")) + "|" + endpointKey(observation);
                        serviceObservationsByKey.putIfAbsent(key, observation);
                    }
                    if (liveEndpoints != null) {
                        Map<String, Object> serviceEvidence = new LinkedHashMap<>();
                        serviceEvidence.put("observations", new ArrayList<>(serviceObservationsByKey.values()));
                        applyServiceEvidence(liveEndpoints, serviceEvidence);
                        enrichPortResult(stage, liveEndpoints);
                    }
                } else if ("RECON".equals(stage.name) && liveEndpoints != null) {
                    applyFingerprintMatches(liveEndpoints, map(latest.get("analysis")));
                    enrichPortResult(stage, liveEndpoints);
                    enrichPortResult(task.stages.get("SERVICE_PROBE"), liveEndpoints);
                }
                boolean persisted = store == null || (observations.isEmpty() && errors.isEmpty())
                        || store.append(task.taskId, annotateStage(observations, stage.name),
                        annotateStage(errors, stage.name));
                if (persisted) {
                    persistenceFailures = 0;
                } else if (++persistenceFailures >= 8) {
                    throw new IllegalStateException("扫描结果连续持久化失败");
                }
                long nextCursor = longValue(latest.get("nextCursor"), observationCursor);
                long nextErrorCursor = longValue(latest.get("nextErrorCursor"), errorCursor);
                if (persisted) {
                    orchestrationService.acknowledge(task.sessionId, childTaskId, nextCursor, nextErrorCursor);
                    observationCursor = Math.max(observationCursor, nextCursor);
                    errorCursor = Math.max(errorCursor, nextErrorCursor);
                }
                persistTaskStateIfNeeded(task);
                if ("STOPPED".equalsIgnoreCase(text(latest.get("status")))
                        && !Boolean.TRUE.equals(latest.get("hasMore"))) {
                    if (!persisted) {
                        Thread.sleep(Math.max(25L, pollIntervalMs * 2L));
                        continue;
                    }
                    break;
                }
                Thread.sleep(pollIntervalMs);
            }
            if ("PORT_SCAN".equals(stage.name)) {
                latest.put("openPortResults", new ArrayList<>(openEndpointsByKey.values()));
            } else if ("SERVICE_PROBE".equals(stage.name)) {
                latest.put("serviceObservations", new ArrayList<>(serviceObservationsByKey.values()));
            }
        } finally {
            synchronized (task.monitor) {
                if (childTaskId.equals(task.childTaskId)) task.childTaskId = null;
            }
        }
        String outcome = text(latest.get("outcome")).toUpperCase(Locale.ROOT);
        if (task.cancelRequested || "CANCELLED".equals(outcome)) {
            synchronized (task.monitor) {
                stage.status = "CANCELLED";
            }
            if (!task.cancelRequested) cancel(task, "工作流阶段被节点取消: " + stage.name);
        } else if ("FAILED".equals(outcome)) {
            synchronized (task.monitor) {
                stage.status = "FAILED";
            }
            throw new IllegalStateException("工作流阶段失败: " + stage.name);
        } else {
            synchronized (task.monitor) {
                stage.status = "COMPLETED";
            }
        }
        if (store != null) {
            store.updateTask(task.taskId, task.status, task.outcome, task.currentStage,
                    taskProgress(task), task.spec.hosts().size());
        }
        return latest;
    }

    private String endpointKey(Map<String, Object> endpoint) {
        return text(endpoint.get("host")) + ":" + integer(endpoint.get("port"), -1);
    }

    private void updateStage(StageState stage, Map<String, Object> result) {
        synchronized (stage) {
            stage.result = new LinkedHashMap<>(result);
            stage.total = integer(result.get("total"), integer(result.get("completed"), 0));
            stage.completed = Math.min(stage.total, integer(result.get("completed"), 0));
            stage.progress = boundedProgress(result.get("progress"), stage.total, stage.completed);
        }
    }

    private void skip(WorkflowTask task, StageState stage, String reason) {
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
            task.monitor.notifyAll();
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
        NetworkProbeResultStore store = resultStore;
        if (store == null) return;
        store.updateTask(task.taskId, task.status, task.outcome, task.currentStage,
                taskProgress(task), task.spec.hosts().size(), task.error, stageSnapshots(task));
        synchronized (task.monitor) {
            task.lastPersistedAt = System.currentTimeMillis();
            task.lastPersistedProgress = taskProgress(task);
            task.lastPersistedStatus = task.status;
            task.lastPersistedOutcome = task.outcome;
            task.lastPersistedStage = task.currentStage;
        }
    }

    private void persistTaskStateIfNeeded(WorkflowTask task) {
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
            if (!changed && now - task.lastPersistedAt < 1000L) return;
        }
        store.updateTask(task.taskId, status, outcome, currentStage, progress,
                task.spec.hosts().size(), task.error, stageSnapshots(task));
        synchronized (task.monitor) {
            task.lastPersistedAt = now;
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
                Map<String, Object> item = new LinkedHashMap<>();
                synchronized (stage) {
                    item.put("name", stage.name);
                    item.put("status", stage.status);
                    item.put("progress", Integer.valueOf(stage.progress));
                    item.put("total", Integer.valueOf(stage.total));
                    item.put("completed", Integer.valueOf(stage.completed));
                    if (stage.reason != null) item.put("reason", stage.reason);
                }
                result.add(item);
            }
            return result;
        }
    }

    private static Map<String, Object> durableConfig(WorkflowSpec spec) {
        Map<String, Object> config = new LinkedHashMap<>();
        if (!spec.name().isEmpty()) config.put("name", spec.name());
        config.put("hosts", spec.hosts());
        config.put("ports", spec.ports());
        config.put("timeout", Integer.valueOf(spec.timeout()));
        config.put("threads", Integer.valueOf(spec.threads()));
        config.put("probeServices", Boolean.valueOf(spec.probeServices()));
        if (!spec.ruleSelector().isEmpty()) config.put("ruleSelector", spec.ruleSelector());
        return config;
    }

    private int taskProgress(WorkflowTask task) {
        synchronized (task.monitor) {
            int completed = 0;
            for (StageState stage : task.stages.values()) if (stage.terminal()) completed++;
            return "COMPLETED".equals(task.outcome) ? 100
                    : Math.min(99, (completed * 100 + currentStageProgress(task)) / STAGE_NAMES.size());
        }
    }

    private void awaitRunnable(WorkflowTask task) throws InterruptedException {
        synchronized (task.monitor) {
            while ("PAUSED".equals(task.status) && !task.cancelRequested) task.monitor.wait(250L);
            if (task.cancelRequested) throw new InterruptedException("workflow cancelled");
        }
    }

    private Map<String, Object> snapshot(WorkflowTask task) {
        synchronized (task.monitor) {
            List<Map<String, Object>> stageSnapshots = new ArrayList<>();
            int completedStages = 0;
            for (StageState stage : task.stages.values()) {
                Map<String, Object> item = new LinkedHashMap<>();
                synchronized (stage) {
                    item.put("name", stage.name);
                    item.put("status", stage.status);
                    item.put("progress", Integer.valueOf(stage.progress));
                    item.put("total", Integer.valueOf(stage.total));
                    item.put("completed", Integer.valueOf(stage.completed));
                    if (stage.backendTaskId != null) item.put("backendTaskId", stage.backendTaskId);
                    if (stage.reason != null) item.put("reason", stage.reason);
                    if (!stage.result.isEmpty()) item.put("result", new LinkedHashMap<>(stage.result));
                }
                stageSnapshots.add(item);
                if (stage.terminal()) completedStages++;
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("taskId", task.taskId);
            result.put("scanKind", KIND);
            result.put("status", task.status);
            result.put("outcome", task.outcome);
            result.put("currentStage", task.currentStage);
            result.put("stageCount", Integer.valueOf(STAGE_NAMES.size()));
            result.put("completedStageCount", Integer.valueOf(completedStages));
            result.put("progress", Integer.valueOf("COMPLETED".equals(task.outcome) ? 100
                    : Math.min(99, (completedStages * 100 + currentStageProgress(task)) / STAGE_NAMES.size())));
            result.put("stages", stageSnapshots);
            result.put("hosts", task.spec.hosts());
            result.put("ports", task.spec.ports());
            result.put("targetCount", Integer.valueOf(task.spec.hosts().size()));
            result.put("reachableHostList", reachableHosts(task));
            result.put("openPortResults", openPortResults(task));
            result.put("serviceResults", serviceResults(task));
            result.put("reconAnalysis", reconAnalysis(task));
            if (!task.spec.name().isEmpty()) result.put("name", task.spec.name());
            result.put("createdAt", Long.valueOf(task.createdAt));
            if (task.error != null) result.put("error", task.error);
            if (task.finishedAt > 0L) result.put("finishedAt", Long.valueOf(task.finishedAt));
            return result;
        }
    }

    private Map<String, Object> compactSnapshot(Map<String, Object> full) {
        Map<String, Object> result = new LinkedHashMap<>(full);
        List<Map<String, Object>> open = mapList(full.get("openPortResults"));
        List<Map<String, Object>> services = mapList(full.get("serviceResults"));
        List<String> reachable = stringList(full.get("reachableHostList"));
        result.put("openCount", Integer.valueOf(open.size()));
        result.put("serviceCount", Integer.valueOf(services.size()));
        result.put("reachableHostCount", Integer.valueOf(reachable.size()));
        result.put("reachableHostList", reachable);
        Map<String, Object> recon = map(full.get("reconAnalysis"));
        long fingerprintCount = mapList(recon.get("matches")).stream()
                .filter(match -> Boolean.TRUE.equals(match.get("matched")))
                .count();
        result.put("fingerprintCount", Integer.valueOf((int) Math.min(Integer.MAX_VALUE, fingerprintCount)));
        result.put("resultAvailable", Boolean.valueOf(!open.isEmpty()));
        result.remove("hosts");
        result.remove("ports");
        result.remove("openPortResults");
        result.remove("serviceResults");
        result.remove("reconAnalysis");
        Object stageValue = full.get("stages");
        if (stageValue instanceof List<?> stages) {
            List<Map<String, Object>> compactStages = new ArrayList<>();
            for (Object value : stages) {
                Map<String, Object> stage = map(value);
                stage.remove("result");
                compactStages.add(stage);
            }
            result.put("stages", compactStages);
        }
        return result;
    }

    private int currentStageProgress(WorkflowTask task) {
        if (task.currentStage == null) return 0;
        StageState stage = task.stages.get(task.currentStage);
        return stage == null ? 0 : stage.progress;
    }

    private List<String> reachableHosts(WorkflowTask task) {
        StageState stage = task.stages.get("REACHABILITY");
        Map<String, Object> analysis = map(stage.result.get("analysis"));
        return stringList(analysis.get("reachableHostList"));
    }

    private List<Map<String, Object>> openPortResults(WorkflowTask task) {
        for (String name : List.of("SERVICE_PROBE", "PORT_SCAN")) {
            StageState stage = task.stages.get(name);
            Object value = stage.result.get("openPortResults");
            if (value instanceof List<?> list) return mapList(list);
        }
        return List.of();
    }

    private List<Map<String, Object>> serviceResults(WorkflowTask task) {
        return openPortResults(task).stream()
                .filter(endpoint -> Boolean.TRUE.equals(endpoint.get("serviceIdentified")))
                .toList();
    }

    private void applyFingerprintMatches(List<Map<String, Object>> endpoints, Map<String, Object> analysis) {
        Map<String, List<Map<String, Object>>> matchesByTarget = new LinkedHashMap<>();
        for (Map<String, Object> match : mapList(analysis.get("matches"))) {
            if (!Boolean.TRUE.equals(match.get("matched"))) continue;
            String targetId = text(match.get("targetId"));
            String ruleId = text(match.get("ruleId"));
            if (targetId.isEmpty() || ruleId.isEmpty()) continue;
            Map<String, Object> fingerprint = new LinkedHashMap<>();
            fingerprint.put("id", ruleId);
            fingerprint.put("name", text(match.get("ruleName")));
            fingerprint.put("protocol", text(match.get("protocol")));
            fingerprint.put("confidence", Double.valueOf(1.0));
            fingerprint.put("evidenceCount", Integer.valueOf(integer(match.get("evidenceCount"), 0)));
            matchesByTarget.computeIfAbsent(targetId, ignored -> new ArrayList<>()).add(fingerprint);
        }
        for (Map<String, Object> endpoint : endpoints) {
            String targetId = text(endpoint.get("host")) + ":" + integer(endpoint.get("port"), -1);
            List<Map<String, Object>> matches = matchesByTarget.get(targetId);
            if (matches == null || matches.isEmpty()) continue;
            endpoint.put("fingerprint", new LinkedHashMap<>(matches.get(0)));
            endpoint.put("fingerprints", new ArrayList<>(matches));
        }
    }

    private Map<String, Object> reconAnalysis(WorkflowTask task) {
        Map<String, Object> result = map(task.stages.get("RECON").result.get("analysis"));
        return result.isEmpty() ? Map.of() : result;
    }

    private WorkflowTask requireTask(String sessionId, String taskId) {
        if (sessionId == null || sessionId.isBlank()) throw new IllegalArgumentException("sessionId不能为空");
        if (taskId == null || taskId.isBlank()) throw new IllegalArgumentException("taskId不能为空");
        WorkflowTask task = tasks.get(taskId.trim());
        if (task == null || !task.sessionId.equals(sessionId.trim())) {
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

    private WorkflowSpec validate(Map<String, Object> request) {
        if (request == null) throw new IllegalArgumentException("workflow必须是对象");
        List<String> hosts = uniqueStrings(request.get("hosts"), "workflow.hosts");
        if (hosts.isEmpty()) throw new IllegalArgumentException("workflow.hosts不能为空");
        if (hosts.size() > NetworkProbeLimits.MAX_RESOLVED_HOSTS) {
            throw new IllegalArgumentException("workflow.hosts不能超过"
                    + NetworkProbeLimits.MAX_RESOLVED_HOSTS + "个");
        }
        List<Integer> ports = uniquePorts(request.get("ports"));
        if (ports.isEmpty()) throw new IllegalArgumentException("workflow.ports不能为空");
        if (request.get("targets") == null
                && (long) hosts.size() * (long) ports.size() > NetworkProbeLimits.MAX_ENDPOINT_COMBINATIONS) {
            throw new IllegalArgumentException("workflow目标组合数不能超过"
                    + NetworkProbeLimits.MAX_ENDPOINT_COMBINATIONS + "个");
        }
        List<Map<String, Object>> targets = workflowTargets(request.get("targets"), hosts, ports);
        int timeout = boundedInt(request.get("timeout"), 3000, 100, 300000);
        int threads = boundedInt(request.get("threads"), 32, 1, 64);
        boolean probeServices = !Boolean.FALSE.equals(request.get("probeServices"));
        Map<String, Object> selector = request.get("ruleSelector") instanceof Map<?, ?> raw
                ? map(raw) : Collections.emptyMap();
        String name = text(request.get("name"));
        List<Map<String, Object>> reachabilityTargets = request.get("reachabilityTargets") == null
                ? new ArrayList<>() : exactReachabilityTargets(request.get("reachabilityTargets"), hosts);
        return new WorkflowSpec(hosts, ports, targets, timeout, threads, probeServices, selector, name,
                reachabilityTargets);
    }

    private Map<String, Object> reachabilityScan(WorkflowSpec spec) {
        Map<String, Object> scan = new LinkedHashMap<>();
        scan.put("kind", "reachability");
        scan.put("hosts", spec.hosts());
        // The controller supplies the minimal exact probes for explicit
        // endpoints. A plain workflow request keeps the three cheap defaults.
        if (!spec.reachabilityTargets().isEmpty()) {
            scan.put("targets", spec.reachabilityTargets());
        }
        scan.put("timeout", Integer.valueOf(spec.timeout()));
        scan.put("threads", Integer.valueOf(spec.threads()));
        return scan;
    }

    private Map<String, Object> portPlan(WorkflowSpec spec, List<String> hosts) {
        Set<String> reachable = new LinkedHashSet<>(hosts);
        List<Map<String, Object>> targets = new ArrayList<>();
        for (Map<String, Object> target : spec.targets()) {
            if (reachable.contains(text(target.get("host")))) {
                targets.add(wireMap(target));
            }
        }
        return probePlan(targets, stageList("tcp-connect"), spec);
    }

    private Map<String, Object> servicePlan(WorkflowSpec spec, List<Map<String, Object>> endpoints) {
        List<Map<String, Object>> targets = new ArrayList<>();
        for (Map<String, Object> endpoint : endpoints) {
            Map<String, Object> target = new LinkedHashMap<>(endpoint);
            target.put("protocol", "tcp");
            target.remove("service");
            target.remove("serviceIdentified");
            target.put("stage", "tcp-exchange");
            targets.add(target);
            int port = integer(endpoint.get("port"), -1);
            String scheme = httpScheme("", port);
            if (scheme != null) {
                Map<String, Object> httpTarget = new LinkedHashMap<>(target);
                httpTarget.put("protocol", "https".equals(scheme) ? "https" : "http");
                httpTarget.put("baseUrl", scheme + "://" + hostForUrl(text(endpoint.get("host"))) + ":" + port + "/");
                httpTarget.put("stage", "http-head");
                targets.add(httpTarget);
            }
        }
        return probePlan(targets, stageList("tcp-exchange", "http-head"), spec);
    }

    private Map<String, Object> probePlan(List<Map<String, Object>> targets, List<String> stages, WorkflowSpec spec) {
        Map<String, Object> limits = new LinkedHashMap<>();
        limits.put("timeout", Integer.valueOf(spec.timeout()));
        limits.put("threads", Integer.valueOf(Math.min(spec.threads(), Math.max(1, targets.size()))));
        limits.put("maxReadBytes", Integer.valueOf(8192));
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("targets", new ArrayList<>(targets));
        plan.put("stages", new ArrayList<>(stages));
        plan.put("limits", limits);
        return plan;
    }

    private Map<String, Object> reconPlan(WorkflowSpec spec, List<Map<String, Object>> endpoints) {
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("kind", "recon");
        plan.put("targets", reconTargets(endpoints));
        plan.put("ruleSelector", wireMap(spec.ruleSelector()));
        plan.put("threads", Integer.valueOf(spec.threads()));
        return plan;
    }

    private List<Map<String, Object>> reconTargets(List<Map<String, Object>> endpoints) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> endpoint : endpoints) {
            String host = text(endpoint.get("host"));
            int port = integer(endpoint.get("port"), -1);
            String service = text(endpoint.get("service")).toLowerCase(Locale.ROOT);
            String scheme = httpScheme(service, port);
            if (scheme != null) {
                Map<String, Object> target = new LinkedHashMap<>();
                target.put("protocol", "http");
                target.put("host", host);
                target.put("port", Integer.valueOf(port));
                target.put("targetId", host + ":" + port);
                target.put("baseUrl", scheme + "://" + hostForUrl(host) + ":" + port);
                target.put("service", scheme);
                result.add(target);
            } else {
                Map<String, Object> target = new LinkedHashMap<>();
                target.put("protocol", "tcp");
                target.put("host", host);
                target.put("port", Integer.valueOf(port));
                target.put("targetId", host + ":" + port);
                target.put("service", service.isEmpty() ? "unknown" : service);
                result.add(target);
            }
        }
        return result;
    }

    private List<Map<String, Object>> openEndpoints(Map<String, Object> result) {
        Map<String, Map<String, Object>> byEndpoint = new LinkedHashMap<>();
        for (Map<String, Object> observation : mapList(result.get("observations"))) {
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
        return new ArrayList<>(byEndpoint.values());
    }

    private void applyServiceEvidence(List<Map<String, Object>> endpoints, Map<String, Object> serviceResult) {
        Map<String, List<Map<String, Object>>> evidenceByEndpoint = new LinkedHashMap<>();
        Object rawObservations = serviceResult.get("serviceObservations");
        if (rawObservations == null) rawObservations = serviceResult.get("observations");
        for (Map<String, Object> observation : mapList(rawObservations)) {
            String key = text(observation.get("host")) + ":" + integer(observation.get("port"), -1);
            evidenceByEndpoint.computeIfAbsent(key, ignored -> new ArrayList<>()).add(observation);
        }
        for (Map<String, Object> endpoint : endpoints) {
            String key = text(endpoint.get("host")) + ":" + integer(endpoint.get("port"), -1);
            for (Map<String, Object> observation : evidenceByEndpoint.getOrDefault(key, List.of())) {
                applyServiceObservation(endpoint, observation);
            }
        }
    }

    private void applyServiceObservation(Map<String, Object> endpoint, Map<String, Object> observation) {
        String stage = text(observation.get("stage"));
        endpoint.put("probe", stage);
        Map<String, Object> evidence = observation.get("evidence") instanceof Map<?, ?> raw ? map(raw) : Map.of();
        if (!evidence.isEmpty()) endpoint.put("evidence", new LinkedHashMap<>(evidence));
        if (evidence.get("banner") != null) endpoint.put("banner", evidence.get("banner"));
        if (evidence.get("statusCode") != null) {
            endpoint.put("statusCode", evidence.get("statusCode"));
            int port = integer(endpoint.get("port"), -1);
            endpoint.put("service", port == 443 || port == 8443 ? "https" : "http");
            endpoint.put("confidence", Double.valueOf(0.9));
        }
        String detectedService = detectService(text(evidence.get("banner")));
        if (!detectedService.isEmpty() && endpoint.get("service") == null) {
            endpoint.put("service", detectedService);
            endpoint.put("confidence", Double.valueOf(0.7));
        }
        if (endpoint.get("service") != null && !text(endpoint.get("service")).isEmpty()) {
            endpoint.put("serviceIdentified", Boolean.TRUE);
        }
        for (String key : List.of("server", "location", "contentType")) {
            if (evidence.get(key) != null) endpoint.put(key, evidence.get(key));
        }
        if (observation.get("error") != null) endpoint.put("probeError", observation.get("error"));
    }

    private void enrichPortResult(StageState stage, List<Map<String, Object>> endpoints) {
        synchronized (stage) {
            stage.result.put("openPortResults", new ArrayList<>(endpoints));
            List<Object> ports = new ArrayList<>();
            for (Map<String, Object> endpoint : endpoints) ports.add(endpoint.get("port"));
            stage.result.put("openPortList", ports);
        }
    }

    private List<Map<String, Object>> workflowTargets(Object value, List<String> hosts, List<Integer> ports) {
        if (value == null) {
            List<Map<String, Object>> targets = new ArrayList<>();
            for (String host : hosts) for (Integer port : ports) {
                Map<String, Object> target = new LinkedHashMap<>();
                target.put("host", host);
                target.put("port", port);
                target.put("protocol", "tcp");
                targets.add(target);
            }
            return targets;
        }
        if (!(value instanceof Collection<?> collection) || collection.isEmpty()) {
            throw new IllegalArgumentException("workflow.targets必须是非空数组");
        }
        if (collection.size() > NetworkProbeLimits.MAX_ENDPOINT_COMBINATIONS) {
            throw new IllegalArgumentException("workflow.targets不能超过"
                    + NetworkProbeLimits.MAX_ENDPOINT_COMBINATIONS + "个");
        }
        Set<String> allowedHosts = new LinkedHashSet<>(hosts);
        List<Map<String, Object>> targets = new ArrayList<>();
        Set<String> keys = new LinkedHashSet<>();
        for (Object item : collection) {
            Map<String, Object> target = map(item);
            String host = text(target.get("host"));
            int port = integer(target.get("port"), -1);
            if (!allowedHosts.contains(host) || port < 1 || port > 65535) {
                throw new IllegalArgumentException("workflow.targets包含无效目标");
            }
            if (keys.add(host + ":" + port)) {
                Map<String, Object> safeTarget = new LinkedHashMap<>();
                safeTarget.put("host", host);
                safeTarget.put("port", Integer.valueOf(port));
                safeTarget.put("protocol", "tcp");
                targets.add(safeTarget);
            }
        }
        if (targets.isEmpty()) throw new IllegalArgumentException("workflow.targets不能为空");
        return targets;
    }

    private List<Map<String, Object>> exactReachabilityTargets(Object value, List<String> hosts) {
        if (!(value instanceof Collection<?> collection) || collection.isEmpty()) {
            throw new IllegalArgumentException("workflow.reachabilityTargets必须是非空数组");
        }
        if (collection.size() > NetworkProbeLimits.MAX_REACHABILITY_PROBES) {
            throw new IllegalArgumentException("workflow.reachabilityTargets不能超过"
                    + NetworkProbeLimits.MAX_REACHABILITY_PROBES + "个");
        }
        Set<String> allowedHosts = new LinkedHashSet<>(hosts);
        Set<String> keys = new LinkedHashSet<>();
        List<Map<String, Object>> targets = new ArrayList<>();
        Map<String, Integer> targetsByHost = new LinkedHashMap<>();
        for (Object item : collection) {
            Map<String, Object> target = map(item);
            String host = text(target.get("host"));
            int port = integer(target.get("port"), -1);
            if (!allowedHosts.contains(host) || port < 1 || port > 65535) {
                throw new IllegalArgumentException("workflow.reachabilityTargets包含无效目标");
            }
            if (keys.add(host + ":" + port)) {
                int hostTargetCount = targetsByHost.getOrDefault(host, 0) + 1;
                if (hostTargetCount > NetworkProbeLimits.MAX_REACHABILITY_PROBES_PER_HOST) {
                    throw new IllegalArgumentException("单台主机的探活端口不能超过"
                            + NetworkProbeLimits.MAX_REACHABILITY_PROBES_PER_HOST + "个");
                }
                targetsByHost.put(host, hostTargetCount);
                Map<String, Object> safeTarget = new LinkedHashMap<>();
                safeTarget.put("host", host);
                safeTarget.put("port", Integer.valueOf(port));
                safeTarget.put("protocol", "tcp");
                targets.add(safeTarget);
            }
        }
        if (targets.isEmpty()) throw new IllegalArgumentException("workflow.reachabilityTargets不能为空");
        return targets;
    }

    private static String detectService(String banner) {
        String value = banner == null ? "" : banner.toLowerCase(Locale.ROOT);
        if (value.startsWith("ssh-") || value.contains("openssh")) return "ssh";
        if (value.contains("ftp")) return "ftp";
        if (value.contains("mysql") || value.contains("mariadb")) return "mysql";
        if (value.contains("redis")) return "redis";
        if (value.contains("mongodb")) return "mongodb";
        if (value.contains("postgres")) return "postgresql";
        if (value.contains("smtp") || value.contains(" esmtp")) return "smtp";
        if (value.contains("http/1.") || value.contains("server:")) return "http";
        return "";
    }

    private static String httpScheme(String service, int port) {
        if ("https".equals(service)) return "https";
        if ("http".equals(service)) return "http";
        if (!HTTP_PORTS.contains(port)) return null;
        return port == 443 || port == 8443 ? "https" : "http";
    }

    private static String hostForUrl(String host) {
        return host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host;
    }

    private static boolean hasExplicitRules(Map<String, Object> selector) {
        return selector.get("fingerprintIds") instanceof Collection<?> values && !values.isEmpty();
    }

    private static boolean isAutomaticReconNoop(String message) {
        if (message == null) return false;
        return message.contains("适用于已识别服务")
                || message.contains("没有匹配到可执行的指纹规则")
                || message.contains("目标协议与所选指纹规则不匹配");
    }

    private static int boundedProgress(Object value, int total, int completed) {
        int progress = integer(value, total > 0 ? completed * 100 / total : 0);
        return Math.max(0, Math.min(100, progress));
    }

    private static List<String> uniqueStrings(Object value, String field) {
        if (!(value instanceof Collection<?> collection)) throw new IllegalArgumentException(field + "必须是数组");
        Set<String> values = new LinkedHashSet<>();
        for (Object item : collection) {
            String text = text(item);
            if (text.isEmpty()) throw new IllegalArgumentException(field + "必须是非空字符串数组");
            values.add(text);
        }
        return new ArrayList<>(values);
    }

    private static List<Integer> uniquePorts(Object value) {
        if (!(value instanceof Collection<?> collection)) throw new IllegalArgumentException("workflow.ports必须是数组");
        Set<Integer> ports = new LinkedHashSet<>();
        for (Object item : collection) {
            int port = integer(item, -1);
            if (port < 1 || port > 65535) throw new IllegalArgumentException("workflow.ports包含无效端口");
            ports.add(port);
        }
        return new ArrayList<>(ports);
    }

    private static Map<String, Object> response(Map<String, Object> values) {
        Map<String, Object> result = new LinkedHashMap<>(values);
        result.put("code", Integer.valueOf(200));
        return result;
    }

    private static List<Map<String, Object>> mapList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) if (item instanceof Map<?, ?> raw) result.add(map(raw));
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

    private static List<String> stageList(String... values) {
        List<String> result = new ArrayList<>();
        result.addAll(Arrays.asList(values));
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

    private static int boundedInt(Object value, int fallback, int min, int max) {
        int result = integer(value, fallback);
        return Math.max(min, Math.min(max, result));
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
            synchronized (task.monitor) {
                task.cancelRequested = true;
                task.monitor.notifyAll();
            }
            if (task.future != null) task.future.cancel(true);
        }
        executor.shutdownNow();
    }

    private record WorkflowSpec(List<String> hosts, List<Integer> ports,
                                List<Map<String, Object>> targets, int timeout, int threads,
                                boolean probeServices, Map<String, Object> ruleSelector, String name,
                                List<Map<String, Object>> reachabilityTargets) { }

    private static final class WorkflowTask {
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
        private volatile String currentStage = "REACHABILITY";
        private volatile String childTaskId;
        private volatile boolean cancelRequested;
        private volatile String error;
        private volatile long finishedAt;
        private volatile java.util.concurrent.Future<?> future;
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
            for (String name : STAGE_NAMES) stages.put(name, new StageState(name));
        }

        private boolean terminal() {
            return "STOPPED".equals(status);
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
