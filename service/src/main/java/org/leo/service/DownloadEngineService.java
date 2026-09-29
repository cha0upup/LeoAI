package org.leo.service;

import jakarta.annotation.PreDestroy;
import org.leo.core.entity.Puppet;
import org.leo.core.puppet.AbstractPuppetNode;
import org.leo.core.puppet.capability.FileCapable;
import org.leo.service.downloadengine.DownloadStore;
import org.leo.service.downloadengine.DownloadTask;
import org.leo.service.concurrent.ServiceTaskExecutor;
import org.leo.service.transfer.TransferTaskState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.io.File;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class DownloadEngineService {
    private static final Logger log = LoggerFactory.getLogger(DownloadEngineService.class);
    private static final int DEFAULT_THREADS = 4;
    private static final int MAX_THREADS = 16;
    private static final int MAX_ACTIVE_TASKS_PER_USER = 4;
    private static final int DEFAULT_CHUNK_SIZE = 1024 * 1024;
    private static final int MAX_CHUNK_SIZE = 1024 * 1024;
    /** 已终结任务在内存中保留时长（毫秒），超过后由 evictFinished 清理 */
    private static final long FINISHED_TASK_RETAIN_MS = 30 * 60 * 1000L; // 30 分钟

    private final ConcurrentHashMap<String, DownloadTask> tasksById = new ConcurrentHashMap<>();
    private final ServiceTaskExecutor taskExecutor;
    private final Object admissionLock = new Object();

    public DownloadEngineService(ServiceTaskExecutor taskExecutor) {
        this.taskExecutor = taskExecutor;
    }

    public Map<String, Object> startOrResume(FileCapable fileNode,
                                             String userId,
                                             String sessionId,
                                             String filePath,
                                             int threads,
                                             int chunkSize) throws Exception {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("缺少必要参数: userId");
        }
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("缺少必要参数: sessionId");
        }
        if (filePath == null || filePath.isBlank()) {
            throw new IllegalArgumentException("缺少必要参数: filePath");
        }
        if (fileNode == null) {
            throw new IllegalArgumentException("fileNode不能为空");
        }

        int t = clampInt(threads, DEFAULT_THREADS, 1, MAX_THREADS);
        int cs = clampInt(chunkSize, DEFAULT_CHUNK_SIZE, 1, MAX_CHUNK_SIZE);

        @SuppressWarnings("unchecked")
        Map<String, Object> probe = fileNode.fileDownloadChunk(filePath, 1L, 0L);
        ensureRemoteSuccess(probe, Set.of(100, 200), "读取远端文件信息失败");
        if (!(probe.get("length") instanceof Number length)) {
            throw new IllegalStateException("远端文件长度无效");
        }
        long expectedLength = length.longValue();
        if (expectedLength < 0L) {
            throw new IllegalStateException("远端文件长度无效: " + expectedLength);
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> md5Res = fileNode.getFileMD5(filePath);
        String expectedMd5 = extractMd5(md5Res);
        if (expectedMd5 == null || expectedMd5.isBlank()) {
            throw new IllegalStateException("获取远端文件MD5失败");
        }

        String taskId = computeTaskId(userId, resolveNodeScopeKey(fileNode, sessionId),
                filePath, expectedLength, expectedMd5);
        String taskKey = taskKey(userId, taskId);

        DownloadTask existing = tasksById.get(taskKey);
        if (existing != null) {
            startWithAdmission(existing, userId);
            return existing.snapshot();
        }

        synchronized (admissionLock) {
            existing = tasksById.get(taskKey);
            if (existing != null) {
                startWithAdmissionLocked(existing, userId);
                return existing.snapshot();
            }
            requireActiveSlot(userId);
            DownloadStore store = new DownloadStore(userId, taskId);
            DownloadTask task = DownloadTask.createNewOrLoad(
                    fileNode,
                    userId,
                    sessionId,
                    taskId,
                    filePath,
                    t,
                    cs,
                    expectedLength,
                    expectedMd5,
                    store,
                    taskExecutor
            );
            tasksById.put(taskKey, task);
            task.ensureStarted();
            log.info("[FileTransfer] download started taskId={}, userId={}, sessionId={}, bytes={}, workers={}",
                    taskId, userId, sessionId, expectedLength, t);
            return task.snapshot();
        }
    }

    public Map<String, Object> resume(FileCapable fileNode, String userId, String sessionId, String taskId) throws Exception {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("缺少必要参数: userId");
        }
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("缺少必要参数: sessionId");
        }
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("缺少必要参数: taskId");
        }
        if (fileNode == null) {
            throw new IllegalArgumentException("fileNode不能为空");
        }

        String taskKey = taskKey(userId, taskId);
        DownloadTask t = tasksById.get(taskKey);
        if (t != null) {
            t.requireOwner(userId, sessionId);
            startWithAdmission(t, userId);
            return t.snapshot();
        }

        synchronized (admissionLock) {
            t = tasksById.get(taskKey);
            if (t != null) {
                t.requireOwner(userId, sessionId);
                startWithAdmissionLocked(t, userId);
                return t.snapshot();
            }
            requireActiveSlot(userId);
            DownloadStore store = new DownloadStore(userId, taskId);
            if (!store.getTaskDir().exists()) {
                throw new IllegalArgumentException("任务不存在: " + taskId);
            }
            DownloadTask task = DownloadTask.loadFromDisk(
                    fileNode, userId, sessionId, taskId, store, taskExecutor);
            tasksById.put(taskKey, task);
            task.ensureStarted();
            log.info("[FileTransfer] download resumed taskId={}, userId={}, sessionId={}",
                    taskId, userId, sessionId);
            return task.snapshot();
        }
    }

    public Map<String, Object> progress(String userId, String taskId) {
        requireUserId(userId);
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("缺少必要参数: taskId");
        }
        DownloadTask task = tasksById.get(taskKey(userId, taskId));
        if (task != null) {
            return task.snapshot();
        }

        if (userId != null && !userId.isBlank()) {
            DownloadStore store = new DownloadStore(userId, taskId);
            if (store.getTaskDir().exists()) {
                try {
                    Map<String, Object> meta = store.readMeta();
                    Map<String, Object> out = new HashMap<>();
                    out.put("taskId", taskId);
                    out.put("state", meta == null ? "UNKNOWN" : meta.get("state"));
                    out.put("meta", sanitizeMetaForFrontend(store, meta));
                    return out;
                } catch (Exception ignored) {
                }
            }
        }
        return Collections.singletonMap("taskId", taskId);
    }

    public Map<String, Object> listBySessionId(String userId, String sessionId) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("缺少必要参数: userId");
        }
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("缺少必要参数: sessionId");
        }

        List<Map<String, Object>> tasks = new ArrayList<>();
        Set<String> existedTaskIds = new HashSet<>();

        for (DownloadTask task : tasksById.values()) {
            if (task.belongsTo(userId, sessionId)) {
                Map<String, Object> snapshot = task.snapshot();
                tasks.add(snapshot);
                existedTaskIds.add(String.valueOf(snapshot.get("taskId")));
            }
        }

        File tasksRoot = DownloadStore.getTasksRootDir(userId);
        File[] taskDirs = tasksRoot.listFiles(File::isDirectory);
        if (taskDirs != null) {
            for (File taskDir : taskDirs) {
                String taskId = taskDir.getName();
                if (existedTaskIds.contains(taskId)) {
                    continue;
                }
                try {
                    DownloadStore store = new DownloadStore(userId, taskId);
                    Map<String, Object> meta = store.readMeta();
                    if (meta == null) {
                        continue;
                    }
                    if (!sessionId.equals(String.valueOf(meta.get("sessionId")))) {
                        continue;
                    }
                    Map<String, Object> item = new HashMap<>();
                    item.put("taskId", taskId);
                    item.put("sessionId", meta.get("sessionId"));
                    item.put("state", meta.get("state"));
                    item.put("meta", sanitizeMetaForFrontend(store, meta));
                    tasks.add(item);
                } catch (Exception ignored) {
                }
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("sessionId", sessionId);
        result.put("count", tasks.size());
        result.put("tasks", tasks);
        return result;
    }

    private static Map<String, Object> sanitizeMetaForFrontend(DownloadStore store, Map<String, Object> meta) {
        if (meta == null) {
            return null;
        }
        Map<String, Object> safe = new HashMap<>(meta);
        safe.remove("userId");
        // Remove absolute paths
        Object finalPath = safe.remove("finalPath");
        Object tempPath = safe.remove("tempPath");
        // Provide relative paths if possible
        if (finalPath != null) {
            String rel = store.toUserRelativePath(new java.io.File(String.valueOf(finalPath)));
            if (rel != null) {
                safe.put("downloadPath", rel);
            }
        }
        if (tempPath != null) {
            String rel = store.toUserRelativePath(new java.io.File(String.valueOf(tempPath)));
            if (rel != null) {
                safe.put("taskTempPath", rel);
            }
        }
        return safe;
    }

    /**
     * 清理已终结（COMPLETED/FAILED/CANCELLED）超过保留时长的任务，防止内存泄漏。
     * 建议由定时调度（如 @Scheduled）周期性调用。
     *
     * @return 被清理的任务数量
     */
    public int evictFinished() {
        long now = System.currentTimeMillis();
        int evicted = 0;
        for (Map.Entry<String, DownloadTask> entry : tasksById.entrySet()) {
            DownloadTask task = entry.getValue();
            TransferTaskState s = task.getState();
            if (isTerminalState(s) && task.getEndAtMs() > 0 && (now - task.getEndAtMs()) > FINISHED_TASK_RETAIN_MS) {
                if (tasksById.remove(entry.getKey(), task)) {
                    new DownloadStore(task.getUserId(), task.getTaskId())
                            .deleteTaskArtifacts();
                    evicted++;
                }
            }
        }
        return evicted + DownloadStore.evictExpiredTaskArtifacts(now - FINISHED_TASK_RETAIN_MS);
    }

    @PreDestroy
    public void close() {
        for (DownloadTask task : tasksById.values()) task.suspendForShutdown();
        tasksById.clear();
    }

    private static boolean isTerminalState(TransferTaskState state) {
        return state != null && state.isTerminal();
    }

    public Map<String, Object> pause(String userId, String taskId) {
        requireUserId(userId);
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("缺少必要参数: taskId");
        }
        DownloadTask task = tasksById.get(taskKey(userId, taskId));
        if (task == null) {
            throw new IllegalArgumentException("任务不存在: " + taskId);
        }
        task.pause();
        log.info("[FileTransfer] download paused taskId={}, userId={}", taskId, userId);
        return task.snapshot();
    }

    public Map<String, Object> cancel(String userId, String taskId) {
        requireUserId(userId);
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("缺少必要参数: taskId");
        }
        DownloadTask task = tasksById.get(taskKey(userId, taskId));
        if (task == null) {
            throw new IllegalArgumentException("任务不存在: " + taskId);
        }
        task.cancel();
        log.info("[FileTransfer] download cancelled taskId={}, userId={}", taskId, userId);
        return task.snapshot();
    }

    public Map<String, Object> retry(FileCapable fileNode,
                                     String userId,
                                     String sessionId,
                                     String taskId) throws Exception {
        requireUserId(userId);
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("缺少必要参数: sessionId");
        }
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("缺少必要参数: taskId");
        }
        if (fileNode == null) {
            throw new IllegalArgumentException("fileNode不能为空");
        }
        String key = taskKey(userId, taskId);
        DownloadTask task = tasksById.get(key);
        if (task == null) {
            DownloadStore store = new DownloadStore(userId, taskId);
            if (!store.getTaskDir().exists()) {
                throw new IllegalArgumentException("任务不存在: " + taskId);
            }
            task = DownloadTask.loadFromDisk(
                    fileNode, userId, sessionId, taskId, store, taskExecutor);
            DownloadTask previous = tasksById.putIfAbsent(key, task);
            if (previous != null) {
                task = previous;
            }
        }
        task.requireOwner(userId, sessionId);
        synchronized (admissionLock) {
            if (task.getState() != TransferTaskState.FAILED) {
                return task.snapshot();
            }
            requireActiveSlot(userId);
            task.resetFailedForRetry();
            task.ensureStarted();
        }
        log.info("[FileTransfer] download retried taskId={}, userId={}, sessionId={}",
                taskId, userId, sessionId);
        return task.snapshot();
    }

    public Map<String, Object> remove(String userId, String taskId) {
        requireUserId(userId);
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("缺少必要参数: taskId");
        }
        String key = taskKey(userId, taskId);
        DownloadTask task = tasksById.get(key);
        if (task != null && task.isActive()) {
            throw new IllegalStateException("运行中的任务需先暂停或取消");
        }
        if (task != null && !task.getState().isTerminal()) {
            task.cancel();
        }
        if (task != null) {
            tasksById.remove(key, task);
        }
        DownloadStore store = new DownloadStore(userId, taskId);
        if (!store.getTaskDir().exists() && task == null) {
            throw new IllegalArgumentException("任务不存在: " + taskId);
        }
        boolean removed = store.deleteTaskArtifacts();
        log.info("[FileTransfer] download record removed taskId={}, userId={}, removed={}",
                taskId, userId, removed);
        Map<String, Object> result = new HashMap<>();
        result.put("taskId", taskId);
        result.put("removed", removed);
        return result;
    }

    private void startWithAdmission(DownloadTask task, String userId) {
        synchronized (admissionLock) {
            startWithAdmissionLocked(task, userId);
        }
    }

    private void startWithAdmissionLocked(DownloadTask task, String userId) {
        if (!task.isActive() && task.getState().canStart()) {
            requireActiveSlot(userId);
        }
        task.ensureStarted();
    }

    private void requireActiveSlot(String userId) {
        long active = tasksById.values().stream()
                .filter(task -> userId.equals(task.getUserId()) && task.isActive())
                .count();
        if (active >= MAX_ACTIVE_TASKS_PER_USER) {
            throw new IllegalStateException(
                    "当前用户同时运行的下载任务已达到上限: " + MAX_ACTIVE_TASKS_PER_USER);
        }
    }

    private static int clampInt(int v, int defaultVal, int min, int max) {
        int val = v <= 0 ? defaultVal : v;
        if (val < min) return min;
        if (val > max) return max;
        return val;
    }

    private static String extractMd5(Map<String, Object> md5Res) {
        if (md5Res == null) {
            return null;
        }
        Object codeObj = md5Res.get("code");
        if (!(codeObj instanceof Number code) || code.intValue() != 200) {
            return null;
        }
        Object md5 = md5Res.get("md5");
        return md5 == null ? null : String.valueOf(md5);
    }

    private static void ensureRemoteSuccess(Map<String, Object> result,
                                            Set<Integer> acceptedCodes,
                                            String errorPrefix) {
        if (result == null) {
            throw new IllegalStateException(errorPrefix + ": 节点返回为空");
        }
        int code = result.get("code") instanceof Number number ? number.intValue() : -1;
        if (!acceptedCodes.contains(code)) {
            Object message = result.get("msg");
            throw new IllegalStateException(errorPrefix + ": "
                    + (message == null ? "code=" + code : message));
        }
    }

    private static String computeTaskId(String userId, String nodeScopeKey,
                                        String filePath, long len, String md5) throws Exception {
        String input = userId + "|" + String.valueOf(nodeScopeKey) + "|" + filePath + "|" + len + "|" + md5;
        MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
        byte[] digest = sha256.digest(input.getBytes(StandardCharsets.UTF_8));
        String b64 = Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        return b64;
    }

    private static String taskKey(String userId, String taskId) {
        return userId + ":" + taskId;
    }

    private static void requireUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("缺少必要参数: userId");
        }
    }

    private static String resolveNodeScopeKey(FileCapable fileNode, String sessionId) {
        if (fileNode instanceof AbstractPuppetNode node) {
            Puppet puppet = node.getPuppet();
            if (puppet != null && puppet.getPuppetId() != null && !puppet.getPuppetId().isBlank()) {
                return puppet.getPuppetId();
            }
        }
        return sessionId;
    }
}
