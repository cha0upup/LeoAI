package org.leo.service.transfer;

import jakarta.annotation.PreDestroy;
import org.leo.core.entity.Agent;
import org.leo.core.agent.AbstractAgentNode;
import org.leo.core.agent.capability.FileCapable;
import org.leo.service.concurrent.ServiceTaskExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;

/** Coordinates the owner-scoped task registry, queue admission and upload commands. */
@Component
public class UploadEngineService {
    private static final Logger log = LoggerFactory.getLogger(UploadEngineService.class);
    private static final int DEFAULT_CHUNK_SIZE = 1024 * 1024;
    private static final int MAX_CHUNK_SIZE = 1024 * 1024;
    private static final int MAX_ACTIVE_TASKS_PER_USER = 3;
    /** 已终结任务保留时长（毫秒），超过后由 evictFinished 清理 */
    private static final long FINISHED_TASK_RETAIN_MS = 30 * 60 * 1000L; // 30 分钟

    private final ConcurrentHashMap<String, UploadTask> tasksById = new ConcurrentHashMap<>();
    private final ServiceTaskExecutor taskExecutor;
    private final Object admissionLock = new Object();

    public UploadEngineService(ServiceTaskExecutor taskExecutor) {
        this.taskExecutor = taskExecutor;
    }

    public Map<String, Object> start(FileCapable fileNode,
                                     String userId,
                                     String sessionId,
                                     String filePath,
                                     File localFile,
                                     String originalFilename,
                                     int chunkSize) {
        if (fileNode == null) {
            throw new IllegalArgumentException("fileNode不能为空");
        }
        requireUserId(userId);
        if (isBlank(sessionId)) {
            throw new IllegalArgumentException("缺少必要参数: sessionId");
        }
        if (isBlank(filePath)) {
            throw new IllegalArgumentException("缺少必要参数: filePath");
        }
        if (localFile == null || !localFile.exists() || !localFile.isFile()) {
            throw new IllegalArgumentException("上传源文件不存在");
        }

        int resolvedChunkSize = clampInt(chunkSize, DEFAULT_CHUNK_SIZE, 1, MAX_CHUNK_SIZE);
        String taskId = computeTaskId(resolveNodeScopeKey(fileNode, sessionId), sessionId, filePath, localFile, originalFilename);
        UploadTask task = new UploadTask(
                taskId, userId, sessionId, filePath, localFile,
                originalFilename, resolvedChunkSize, future -> taskExecutor.cancelUpload(future));
        synchronized (admissionLock) {
            requireActiveSlot(userId);
            tasksById.put(taskKey(userId, taskId), task);
            submit(task, fileNode);
        }
        log.info("[FileTransfer] upload started taskId={}, userId={}, sessionId={}, bytes={}",
                taskId, userId, sessionId, localFile.length());
        return task.snapshot();
    }

    public Map<String, Object> progress(String userId, String taskId) {
        UploadTask task = requireTask(userId, taskId);
        return task.snapshot();
    }

    public Map<String, Object> cancel(String userId, String taskId) {
        UploadTask task = requireTask(userId, taskId);
        task.cancel();
        log.info("[FileTransfer] upload cancelled taskId={}, userId={}", taskId, userId);
        return task.snapshot();
    }

    public Map<String, Object> pause(String userId, String taskId) {
        UploadTask task = requireTask(userId, taskId);
        task.pause();
        log.info("[FileTransfer] upload paused taskId={}, userId={}", taskId, userId);
        return task.snapshot();
    }

    public Map<String, Object> resume(FileCapable fileNode,
                                      String userId,
                                      String sessionId,
                                      String taskId) {
        UploadTask task = requireTask(userId, taskId);
        task.requireSession(sessionId);
        synchronized (admissionLock) {
            if (task.isPaused()) {
                requireActiveSlot(userId);
                task.prepareResume();
                submit(task, fileNode);
            }
        }
        log.info("[FileTransfer] upload resumed taskId={}, userId={}, sessionId={}",
                taskId, userId, sessionId);
        return task.snapshot();
    }

    public Map<String, Object> retry(FileCapable fileNode,
                                     String userId,
                                     String sessionId,
                                     String taskId) {
        UploadTask task = requireTask(userId, taskId);
        task.requireSession(sessionId);
        synchronized (admissionLock) {
            if (task.isFailed()) {
                requireActiveSlot(userId);
                task.resetForRetry();
                submit(task, fileNode);
            }
        }
        log.info("[FileTransfer] upload retried taskId={}, userId={}, sessionId={}",
                taskId, userId, sessionId);
        return task.snapshot();
    }

    public Map<String, Object> remove(String userId, String taskId) {
        UploadTask task = requireTask(userId, taskId);
        if (task.isActive()) {
            throw new IllegalStateException("运行中的任务需先暂停或取消");
        }
        if (!task.isTerminal()) {
            task.cancel();
        }
        boolean removed = tasksById.remove(taskKey(userId, taskId), task);
        Map<String, Object> result = new HashMap<>();
        result.put("taskId", taskId);
        result.put("removed", removed);
        log.info("[FileTransfer] upload record removed taskId={}, userId={}, removed={}",
                taskId, userId, removed);
        return result;
    }

    public Map<String, Object> listBySessionId(String userId, String sessionId) {
        requireUserId(userId);
        if (isBlank(sessionId)) {
            throw new IllegalArgumentException("缺少必要参数: sessionId");
        }

        List<Map<String, Object>> tasks = new ArrayList<>();
        for (UploadTask task : tasksById.values()) {
            if (task.belongsTo(userId, sessionId)) {
                tasks.add(task.snapshot());
            }
        }

        HashMap<String, Object> result = new HashMap<>();
        result.put("sessionId", sessionId);
        result.put("count", tasks.size());
        result.put("tasks", tasks);
        result.put("ephemeral", true);
        return result;
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
        for (Map.Entry<String, UploadTask> entry : tasksById.entrySet()) {
            UploadTask task = entry.getValue();
            if (task.isExpired(now, FINISHED_TASK_RETAIN_MS)) {
                if (tasksById.remove(entry.getKey(), task)) {
                    evicted++;
                }
            }
        }
        return evicted;
    }

    @PreDestroy
    public void close() {
        tasksById.values().forEach(UploadTask::stopOnShutdown);
        tasksById.clear();
    }

    private static int clampInt(int v, int defaultVal, int min, int max) {
        int value = v <= 0 ? defaultVal : v;
        if (value < min) {
            return min;
        }
        if (value > max) {
            return max;
        }
        return value;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static void requireUserId(String userId) {
        if (isBlank(userId)) {
            throw new IllegalArgumentException("缺少必要参数: userId");
        }
    }

    private UploadTask requireTask(String userId, String taskId) {
        requireUserId(userId);
        if (isBlank(taskId)) {
            throw new IllegalArgumentException("缺少必要参数: taskId");
        }
        UploadTask task = tasksById.get(taskKey(userId, taskId));
        if (task == null) {
            throw new IllegalArgumentException("任务不存在: " + taskId);
        }
        return task;
    }

    private void requireActiveSlot(String userId) {
        long active = tasksById.values().stream()
                .filter(task -> task.occupiesSlot(userId))
                .count();
        if (active >= MAX_ACTIVE_TASKS_PER_USER) {
            throw new IllegalStateException(
                    "当前用户同时运行的上传任务已达到上限: " + MAX_ACTIVE_TASKS_PER_USER);
        }
    }

    private void submit(UploadTask task, FileCapable fileNode) {
        try {
            Future<?> future = taskExecutor.submitUpload(() -> task.runUpload(fileNode));
            task.attachFuture(future);
        } catch (RejectedExecutionException error) {
            task.reject("上传任务队列繁忙");
        }
    }

    private static String taskKey(String userId, String taskId) {
        return userId + ":" + taskId;
    }

    private static String computeTaskId(String hostId,
                                        String sessionId,
                                        String filePath,
                                        File localFile,
                                        String originalFilename) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            updateDigest(md, hostId);
            updateDigest(md, sessionId);
            updateDigest(md, filePath);
            updateDigest(md, originalFilename);
            updateDigest(md, String.valueOf(localFile.length()));
            updateDigest(md, String.valueOf(localFile.lastModified()));
            updateDigest(md, UUID.randomUUID().toString());
            return HexFormat.of().formatHex(md.digest());
        } catch (Exception e) {
            return UUID.randomUUID().toString().replace("-", "");
        }
    }

    private static void updateDigest(MessageDigest md, String value) {
        String safe = value == null ? "" : value;
        md.update(safe.getBytes(StandardCharsets.UTF_8));
        md.update((byte) '\n');
    }


    private static String resolveNodeScopeKey(FileCapable fileNode, String sessionId) {
        if (fileNode instanceof AbstractAgentNode node) {
            Agent agent = node.getAgent();
            if (agent != null && agent.getAgentId() != null && !agent.getAgentId().isBlank()) {
                return agent.getAgentId();
            }
        }
        return sessionId;
    }
}
