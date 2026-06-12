package org.leo.service;

import org.leo.core.config.LeoConfig;
import org.leo.core.puppet.impl.JavaPuppetNode;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class UploadEngineService {
    private static final int DEFAULT_CHUNK_SIZE = 1024 * 1024;
    private static final int MAX_CHUNK_SIZE = 1024 * 1024;
    private static final String PRIVILEGE_ADMIN = "admin";
    /** 已终结任务保留时长（毫秒），超过后由 evictFinished 清理 */
    private static final long FINISHED_TASK_RETAIN_MS = 30 * 60 * 1000L; // 30 分钟

    private final ConcurrentHashMap<String, UploadTask> tasksById = new ConcurrentHashMap<>();
    private final ExecutorService executorService = Executors.newFixedThreadPool(
            Math.max(4, Runtime.getRuntime().availableProcessors()),
            r -> { Thread t = new Thread(r, "upload-engine"); t.setDaemon(true); return t; });

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

    public Map<String, Object> start(JavaPuppetNode puppetNode,
                                     String userId,
                                     String sessionId,
                                     String filePath,
                                     File localFile,
                                     String originalFilename,
                                     int chunkSize) {
        if (puppetNode == null) {
            throw new IllegalArgumentException("puppetNode不能为空");
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
        String taskId = computeTaskId(puppetNode.getHostId(), sessionId, filePath, localFile, originalFilename);
        UploadTask task = new UploadTask(taskId, userId, sessionId, filePath, localFile, originalFilename, resolvedChunkSize);
        UploadTask previous = tasksById.put(taskId, task);
        if (previous != null) {
            previous.cancel();
        }
        executorService.submit(new Runnable() {
            @Override
            public void run() {
                task.runUpload(puppetNode);
            }
        });
        return task.snapshot();
    }

    public Map<String, Object> progress(String taskId) {
        if (isBlank(taskId)) {
            throw new IllegalArgumentException("缺少必要参数: taskId");
        }
        UploadTask task = tasksById.get(taskId);
        if (task == null) {
            return Collections.singletonMap("taskId", taskId);
        }
        return task.snapshot();
    }

    public Map<String, Object> cancel(String taskId) {
        if (isBlank(taskId)) {
            throw new IllegalArgumentException("缺少必要参数: taskId");
        }
        UploadTask task = tasksById.get(taskId);
        if (task == null) {
            return Collections.singletonMap("taskId", taskId);
        }
        task.cancel();
        return task.snapshot();
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
            if (isTerminalState(task.state) && (now - task.updatedAt) > FINISHED_TASK_RETAIN_MS) {
                tasksById.remove(entry.getKey());
                evicted++;
            }
        }
        return evicted;
    }

    private static boolean isTerminalState(String state) {
        return "COMPLETED".equals(state) || "FAILED".equals(state) || "CANCELLED".equals(state);
    }

    private Path getUserBasePath(String userId) {
        return getVfsBasePath().resolve("users").resolve(userId).toAbsolutePath().normalize();
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
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                String hex = Integer.toHexString(b & 0xff);
                if (hex.length() == 1) {
                    sb.append('0');
                }
                sb.append(hex);
            }
            return sb.toString();
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
        private final long createdAt = System.currentTimeMillis();
        private final AtomicLong uploadedBytes = new AtomicLong(0L);

        private volatile String state = "PENDING";
        private volatile String errorMessage;
        private volatile long updatedAt = createdAt;
        private volatile boolean cancelRequested = false;

        private UploadTask(String taskId,
                           String userId,
                           String sessionId,
                           String filePath,
                           File localFile,
                           String originalFilename,
                           int chunkSize) {
            this.taskId = taskId;
            this.userId = userId;
            this.sessionId = sessionId;
            this.filePath = filePath;
            this.localFile = localFile;
            this.originalFilename = originalFilename;
            this.chunkSize = chunkSize;
            this.totalBytes = Math.max(localFile.length(), 0L);
        }

        private void runUpload(JavaPuppetNode puppetNode) {
            state = "RUNNING";
            updatedAt = System.currentTimeMillis();
            InputStream inputStream = null;
            try {
                tryDeleteRemoteFile(puppetNode, filePath);
                inputStream = new FileInputStream(localFile);
                byte[] buffer = new byte[chunkSize];
                long offset = 0L;
                int read;
                while ((read = inputStream.read(buffer)) >= 0) {
                    if (cancelRequested) {
                        state = "CANCELLED";
                        updatedAt = System.currentTimeMillis();
                        return;
                    }
                    byte[] chunk = buffer;
                    if (read != buffer.length) {
                        chunk = new byte[read];
                        System.arraycopy(buffer, 0, chunk, 0, read);
                    }
                    Map<String, Object> result = puppetNode.fileUploadChunk(filePath, offset, chunk);
                    ensureSuccess(result);
                    offset += read;
                    uploadedBytes.set(offset);
                    updatedAt = System.currentTimeMillis();
                }
                state = cancelRequested ? "CANCELLED" : "COMPLETED";
                updatedAt = System.currentTimeMillis();
            } catch (Exception e) {
                errorMessage = e.getMessage();
                state = cancelRequested ? "CANCELLED" : "FAILED";
                updatedAt = System.currentTimeMillis();
            } finally {
                if (inputStream != null) {
                    try {
                        inputStream.close();
                    } catch (Exception ignored) {
                    }
                }
            }
        }

        private void cancel() {
            cancelRequested = true;
            if (!"COMPLETED".equals(state) && !"FAILED".equals(state)) {
                state = "CANCELLED";
            }
            updatedAt = System.currentTimeMillis();
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
            map.put("chunkSize", chunkSize);
            map.put("state", state);
            map.put("createdAt", createdAt);
            map.put("updatedAt", updatedAt);
            map.put("progress", totalBytes <= 0 ? 100D : Math.min(100D, (uploaded * 100D) / totalBytes));
            map.put("ephemeral", true);
            if (errorMessage != null && !errorMessage.isBlank()) {
                map.put("errorMessage", errorMessage);
            }
            return map;
        }

        private void ensureSuccess(Map<String, Object> result) {
            if (result == null) {
                return;
            }
            Object codeObj = result.get("code");
            if (codeObj instanceof Number && ((Number) codeObj).intValue() != 200) {
                Object msg = result.get("msg");
                throw new IllegalStateException(msg == null ? "远端上传失败" : String.valueOf(msg));
            }
        }

        private void tryDeleteRemoteFile(JavaPuppetNode puppetNode, String targetPath) {
            try {
                puppetNode.deleteFile(targetPath);
            } catch (Exception ignored) {
            }
        }
    }
}
