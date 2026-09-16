package org.leo.service;

import jakarta.annotation.PreDestroy;
import org.leo.core.config.LeoConfig;
import org.leo.core.entity.Puppet;
import org.leo.core.puppet.AbstractPuppetNode;
import org.leo.core.puppet.capability.FileCapable;
import org.leo.service.concurrent.ServiceTaskExecutor;
import org.leo.service.transfer.TransferStage;
import org.leo.service.transfer.TransferTaskState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.DigestUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
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
import java.util.concurrent.atomic.AtomicLong;

@Component
public class UploadEngineService {
    private static final Logger log = LoggerFactory.getLogger(UploadEngineService.class);
    private static final int DEFAULT_CHUNK_SIZE = 1024 * 1024;
    private static final int MAX_CHUNK_SIZE = 1024 * 1024;
    private static final int MAX_ACTIVE_TASKS_PER_USER = 3;
    private static final String PRIVILEGE_ADMIN = "admin";
    /** 已终结任务保留时长（毫秒），超过后由 evictFinished 清理 */
    private static final long FINISHED_TASK_RETAIN_MS = 30 * 60 * 1000L; // 30 分钟

    private final ConcurrentHashMap<String, UploadTask> tasksById = new ConcurrentHashMap<>();
    private final ServiceTaskExecutor taskExecutor;
    private final Object admissionLock = new Object();

    public UploadEngineService(ServiceTaskExecutor taskExecutor) {
        this.taskExecutor = taskExecutor;
    }

    public Path resolveVfsFilePath(String vfsPath) {
        if (isBlank(vfsPath)) {
            throw new IllegalArgumentException("缺少必要参数: vfsPath");
        }
        String normalized = vfsPath.trim().replace('\\', '/');
        if (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        Path base = getVfsBasePath();
        Path resolved = base.resolve(Paths.get(normalized)).normalize();
        if (!resolved.startsWith(base)) {
            throw new IllegalArgumentException("非法VFS路径");
        }
        return resolved;
    }

    public void validateReadPermission(String userId, String privilege, Path resolvedPath) {
        if (isBlank(userId)) {
            throw new IllegalArgumentException("用户信息无效");
        }
        if (resolvedPath == null) {
            throw new IllegalArgumentException("文件路径不能为空");
        }
        Path vfsBase = getVfsBasePath();
        Path normalizedPath = resolvedPath.toAbsolutePath().normalize();
        if (!normalizedPath.startsWith(vfsBase)) {
            throw new IllegalArgumentException("非法VFS路径");
        }
        if (PRIVILEGE_ADMIN.equals(privilege)) {
            return;
        }
        Path userBase = getUserBasePath(userId);
        Path skillsBase = vfsBase.resolve("skills").toAbsolutePath().normalize();
        if (normalizedPath.startsWith(userBase) || normalizedPath.startsWith(skillsBase)) {
            return;
        }
        throw new IllegalArgumentException("无权访问该VFS路径");
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
        if (isBlank(userId)) {
            throw new IllegalArgumentException("缺少必要参数: userId");
        }
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
                originalFilename, resolvedChunkSize, taskExecutor);
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
        requireUserId(userId);
        if (isBlank(taskId)) {
            throw new IllegalArgumentException("缺少必要参数: taskId");
        }
        UploadTask task = tasksById.get(taskKey(userId, taskId));
        if (task == null) {
            throw new IllegalArgumentException("任务不存在: " + taskId);
        }
        return task.snapshot();
    }

    public Map<String, Object> cancel(String userId, String taskId) {
        requireUserId(userId);
        if (isBlank(taskId)) {
            throw new IllegalArgumentException("缺少必要参数: taskId");
        }
        UploadTask task = tasksById.get(taskKey(userId, taskId));
        if (task == null) {
            throw new IllegalArgumentException("任务不存在: " + taskId);
        }
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
            if (task.state == TransferTaskState.PAUSED) {
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
            if (task.state == TransferTaskState.FAILED) {
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
        if (!task.state.isTerminal()) {
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
        if (isBlank(userId)) {
            throw new IllegalArgumentException("缺少必要参数: userId");
        }
        if (isBlank(sessionId)) {
            throw new IllegalArgumentException("缺少必要参数: sessionId");
        }

        List<Map<String, Object>> tasks = new ArrayList<>();
        for (UploadTask task : tasksById.values()) {
            if (userId.equals(task.userId) && sessionId.equals(task.sessionId)) {
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
            if (task.state.isTerminal() && (now - task.updatedAt) > FINISHED_TASK_RETAIN_MS) {
                if (tasksById.remove(entry.getKey(), task)) {
                    evicted++;
                }
            }
        }
        return evicted;
    }

    @PreDestroy
    public void close() {
        for (UploadTask task : tasksById.values()) {
            synchronized (task) {
                // 提交中的远端重命名无法撤回，保留结果，继续关闭其他任务。
                if (task.isCommitting()) continue;
                task.cancel();
            }
        }
        tasksById.clear();
    }

    private Path getUserBasePath(String userId) {
        Path usersBase = getVfsBasePath().resolve("users").toAbsolutePath().normalize();
        Path userBase = usersBase.resolve(userId).normalize();
        if (userBase.equals(usersBase) || !userBase.startsWith(usersBase)) {
            throw new IllegalArgumentException("用户信息无效");
        }
        return userBase;
    }

    private Path getVfsBasePath() {
        String vfsPath = LeoConfig.getVfsPath();
        if (isBlank(vfsPath)) {
            vfsPath = LeoConfig.DEFAULT_VFS_PATH;
        }
        return new File(vfsPath).toPath().toAbsolutePath().normalize();
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
                .filter(task -> userId.equals(task.userId) && task.occupiesSlot())
                .count();
        if (active >= MAX_ACTIVE_TASKS_PER_USER) {
            throw new IllegalStateException(
                    "当前用户同时运行的上传任务已达到上限: " + MAX_ACTIVE_TASKS_PER_USER);
        }
    }

    private void submit(UploadTask task, FileCapable fileNode) {
        try {
            Future<?> future = taskExecutor.submitUpload(new Runnable() {
                @Override
                public void run() {
                    task.runUpload(fileNode);
                }
            });
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

    private static final class UploadTask {
        private final String taskId;
        private final String userId;
        private final String sessionId;
        private final String filePath;
        private final File localFile;
        private final String originalFilename;
        private final int chunkSize;
        private final long totalBytes;
        private final ServiceTaskExecutor taskExecutor;
        private final long createdAt = System.currentTimeMillis();
        private final AtomicLong uploadedBytes = new AtomicLong(0L);
        private final AtomicLong bytesAtStart = new AtomicLong(0L);
        private final AtomicLong speedStartAt = new AtomicLong(0L);
        private final String tempPath;

        private volatile TransferTaskState state = TransferTaskState.NEW;
        private volatile TransferStage currentStage = TransferStage.CREATED;
        private volatile TransferStage errorStage;
        private volatile String errorMessage;
        private volatile long updatedAt = createdAt;
        private volatile long startedAt;
        private volatile long endAt;
        private volatile boolean cancelRequested = false;
        private volatile boolean pauseRequested = false;
        private volatile boolean tempInitialized = false;
        private volatile boolean cleanupComplete = true;
        private volatile Future<?> future;
        private volatile FileCapable activeFileNode;

        private UploadTask(String taskId,
                           String userId,
                           String sessionId,
                           String filePath,
                           File localFile,
                           String originalFilename,
                           int chunkSize,
                           ServiceTaskExecutor taskExecutor) {
            this.taskId = taskId;
            this.userId = userId;
            this.sessionId = sessionId;
            this.filePath = filePath;
            this.localFile = localFile;
            this.originalFilename = originalFilename;
            this.chunkSize = chunkSize;
            this.taskExecutor = taskExecutor;
            this.totalBytes = Math.max(localFile.length(), 0L);
            String tempToken = taskId.length() > 12 ? taskId.substring(0, 12) : taskId;
            int separatorIndex = Math.max(filePath.lastIndexOf('/'), filePath.lastIndexOf('\\'));
            String parentPrefix = separatorIndex >= 0 ? filePath.substring(0, separatorIndex + 1) : "";
            this.tempPath = parentPrefix + ".leo-upload-" + tempToken + ".part";
        }

        private void runUpload(FileCapable fileNode) {
            synchronized (this) {
                if (state != TransferTaskState.NEW) {
                    return;
                }
                state = TransferTaskState.RUNNING;
                currentStage = TransferStage.PREPARING;
                pauseRequested = false;
                cancelRequested = false;
                long runStartedAt = System.currentTimeMillis();
                if (startedAt <= 0L) {
                    startedAt = runStartedAt;
                }
                bytesAtStart.set(uploadedBytes.get());
                speedStartAt.set(runStartedAt);
                endAt = 0L;
                activeFileNode = fileNode;
                cleanupComplete = false;
                touch();
            }
            InputStream inputStream = null;
            boolean committed = false;
            try {
                if (!tempInitialized) {
                    ensureSuccess(fileNode.createFile(tempPath, ""));
                    tempInitialized = true;
                    uploadedBytes.set(0L);
                }
                inputStream = new FileInputStream(localFile);
                skipFully(inputStream, uploadedBytes.get());
                byte[] buffer = new byte[chunkSize];
                long offset = uploadedBytes.get();
                currentStage = TransferStage.TRANSFERRING;
                touch();
                int read;
                while ((read = inputStream.read(buffer)) >= 0) {
                    if (finishIfRequested()) return;
                    if (read == 0) {
                        continue;
                    }
                    byte[] chunk = buffer;
                    if (read != buffer.length) {
                        chunk = new byte[read];
                        System.arraycopy(buffer, 0, chunk, 0, read);
                    }
                    Map<String, Object> result = fileNode.fileUploadChunk(tempPath, offset, chunk);
                    ensureSuccess(result);
                    offset += read;
                    uploadedBytes.set(offset);
                    touch();
                }
                if (finishIfRequested()) return;
                currentStage = TransferStage.VERIFYING_LOCAL;
                touch();
                String localMd5 = calculateMd5(localFile);
                if (finishIfRequested()) {
                    return;
                }
                currentStage = TransferStage.VERIFYING_REMOTE;
                touch();
                Map<String, Object> remoteChecksum = fileNode.getFileMD5(tempPath);
                ensureSuccess(remoteChecksum);
                if (finishIfRequested()) {
                    return;
                }
                Object remoteMd5Value = remoteChecksum.get("md5");
                String remoteMd5 = remoteMd5Value == null ? null : String.valueOf(remoteMd5Value);
                if (remoteMd5 == null || !localMd5.equalsIgnoreCase(remoteMd5)) {
                    throw new IllegalStateException(
                            "上传完整性校验失败: local=" + localMd5 + ", remote=" + remoteMd5);
                }
                if (!beginCommit()) return;
                ensureSuccess(fileNode.moveFile(tempPath, filePath, "overwrite"));
                committed = true;
                tempInitialized = false;
                state = TransferTaskState.COMPLETED;
                currentStage = TransferStage.FINISHED;
                endAt = System.currentTimeMillis();
                touch();
            } catch (Exception e) {
                if (!finishIfRequested()) {
                    errorStage = currentStage;
                    errorMessage = e.getMessage();
                    state = TransferTaskState.FAILED;
                    endAt = System.currentTimeMillis();
                    touch();
                }
            } finally {
                if (inputStream != null) {
                    try {
                        inputStream.close();
                    } catch (Exception ignored) {
                    }
                }
                synchronized (this) {
                    if (!committed && state != TransferTaskState.PAUSED) {
                        tryDeleteRemoteFile(fileNode, tempPath);
                        tempInitialized = false;
                    }
                    cleanupComplete = true;
                }
            }
        }

        private synchronized boolean beginCommit() {
            if (finishIfRequested()) return false;
            currentStage = TransferStage.COMMITTING;
            touch();
            return true;
        }

        private void requireCancellable() {
            if (isCommitting()) {
                throw new IllegalStateException("文件正在提交，请等待提交结果");
            }
        }

        private boolean isCommitting() {
            return state == TransferTaskState.RUNNING && currentStage == TransferStage.COMMITTING;
        }

        private synchronized void pause() {
            if (state != TransferTaskState.RUNNING && state != TransferTaskState.NEW) {
                return;
            }
            requireCancellable();
            pauseRequested = true;
            state = TransferTaskState.PAUSED;
            touch();
            Future<?> running = future;
            taskExecutor.cancelUpload(running);
        }

        private synchronized void cancel() {
            if (state.isTerminal()) {
                return;
            }
            requireCancellable();
            boolean cleanImmediately = state == TransferTaskState.PAUSED && tempInitialized && cleanupComplete;
            cancelRequested = true;
            pauseRequested = false;
            state = TransferTaskState.CANCELLED;
            currentStage = TransferStage.FINISHED;
            endAt = System.currentTimeMillis();
            touch();
            Future<?> running = future;
            taskExecutor.cancelUpload(running);
            if (cleanImmediately && activeFileNode != null) {
                tryDeleteRemoteFile(activeFileNode, tempPath);
                tempInitialized = false;
            }
        }

        private synchronized void attachFuture(Future<?> future) {
            this.future = future;
            if (cancelRequested || pauseRequested) taskExecutor.cancelUpload(future);
        }

        private synchronized void reject(String message) {
            errorMessage = message;
            errorStage = currentStage;
            state = TransferTaskState.FAILED;
            endAt = System.currentTimeMillis();
            touch();
        }

        private synchronized void resetForRetry() {
            if (state != TransferTaskState.FAILED) {
                return;
            }
            if (!cleanupComplete) {
                throw new IllegalStateException("上传任务正在清理临时文件");
            }
            state = TransferTaskState.NEW;
            currentStage = TransferStage.CREATED;
            errorStage = null;
            errorMessage = null;
            cancelRequested = false;
            pauseRequested = false;
            tempInitialized = false;
            uploadedBytes.set(0L);
            endAt = 0L;
            touch();
        }

        private synchronized void prepareResume() {
            if (state != TransferTaskState.PAUSED) {
                return;
            }
            if (!cleanupComplete) throw new IllegalStateException("上传任务正在暂停，请稍后恢复");
            state = TransferTaskState.NEW;
            pauseRequested = false;
            cancelRequested = false;
            touch();
        }

        private synchronized void finishPaused() {
            if (state.isTerminal()) {
                return;
            }
            state = TransferTaskState.PAUSED;
            touch();
        }

        private synchronized void finishCancelled() {
            if (state == TransferTaskState.COMPLETED || state == TransferTaskState.FAILED) {
                return;
            }
            state = TransferTaskState.CANCELLED;
            currentStage = TransferStage.FINISHED;
            endAt = System.currentTimeMillis();
            touch();
        }

        private boolean finishIfRequested() {
            if (cancelRequested) {
                finishCancelled();
                return true;
            }
            if (pauseRequested) {
                finishPaused();
                return true;
            }
            return false;
        }

        private Map<String, Object> snapshot() {
            HashMap<String, Object> map = new HashMap<>();
            long uploaded = uploadedBytes.get();
            map.put("taskId", taskId);
            map.put("sessionId", sessionId);
            map.put("filePath", filePath);
            map.put("fileName", originalFilename);
            map.put("fileSize", totalBytes);
            map.put("uploadedBytes", uploaded);
            map.put("speedBytesPerSec", speedBytesPerSecond(uploaded));
            map.put("chunkSize", chunkSize);
            map.put("state", state.name());
            map.put("currentStage", currentStage.name());
            map.put("createdAt", createdAt);
            map.put("startedAt", startedAt);
            map.put("endAt", endAt);
            map.put("updatedAt", updatedAt);
            map.put("progress", totalBytes <= 0
                    ? (state == TransferTaskState.COMPLETED ? 100D : 0D)
                    : Math.min(100D, (uploaded * 100D) / totalBytes));
            map.put("ephemeral", true);
            if (errorMessage != null && !errorMessage.isBlank()) {
                map.put("errorMessage", errorMessage);
            }
            if (errorStage != null) {
                map.put("errorStage", errorStage.name());
            }
            return map;
        }

        private boolean isActive() {
            return state.isActive();
        }

        private boolean occupiesSlot() {
            return state == TransferTaskState.NEW || state == TransferTaskState.RUNNING;
        }

        private void requireSession(String expectedSessionId) {
            if (!sessionId.equals(expectedSessionId)) {
                throw new IllegalArgumentException("任务归属不匹配: " + taskId);
            }
        }

        private void touch() {
            updatedAt = System.currentTimeMillis();
        }

        private long speedBytesPerSecond(long uploaded) {
            long started = speedStartAt.get();
            if (started <= 0L || state != TransferTaskState.RUNNING) {
                return 0L;
            }
            long elapsed = Math.max(1L, System.currentTimeMillis() - started);
            return Math.max(0L, uploaded - bytesAtStart.get()) * 1000L / elapsed;
        }

        private void skipFully(InputStream input, long bytes) throws Exception {
            long remaining = bytes;
            while (remaining > 0L) {
                long skipped = input.skip(remaining);
                if (skipped <= 0L) {
                    if (input.read() < 0) {
                        throw new IllegalStateException("上传源文件长度发生变化");
                    }
                    skipped = 1L;
                }
                remaining -= skipped;
            }
        }

        private void ensureSuccess(Map<String, Object> result) {
            if (result == null) {
                throw new IllegalStateException("远端操作未返回结果");
            }
            Object codeObj = result.get("code");
            int code;
            try {
                code = codeObj instanceof Number
                        ? ((Number) codeObj).intValue()
                        : Integer.parseInt(String.valueOf(codeObj));
            } catch (Exception error) {
                throw new IllegalStateException("远端操作返回状态无效");
            }
            if (code != 200) {
                Object msg = result.get("msg");
                throw new IllegalStateException(msg == null ? "远端上传失败" : String.valueOf(msg));
            }
        }

        private void tryDeleteRemoteFile(FileCapable fileNode, String targetPath) {
            try {
                fileNode.deleteFile(targetPath);
            } catch (Exception ignored) {
            }
        }

        private String calculateMd5(File file) throws Exception {
            try (InputStream input = new FileInputStream(file)) {
                return DigestUtils.md5DigestAsHex(input);
            }
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
