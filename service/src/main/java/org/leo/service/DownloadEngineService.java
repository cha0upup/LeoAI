package org.leo.service;

import org.leo.core.puppet.impl.JavaPuppetNode;
import org.leo.service.downloadengine.DownloadStore;
import org.leo.service.downloadengine.DownloadTask;
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
    private static final int DEFAULT_THREADS = 4;
    private static final int MAX_THREADS = 16;
    private static final int DEFAULT_CHUNK_SIZE = 1024 * 1024;
    private static final int MAX_CHUNK_SIZE = 1024 * 1024;
    /** 已终结任务在内存中保留时长（毫秒），超过后由 evictFinished 清理 */
    private static final long FINISHED_TASK_RETAIN_MS = 30 * 60 * 1000L; // 30 分钟

    private final ConcurrentHashMap<String, DownloadTask> tasksById = new ConcurrentHashMap<>();

    public Map<String, Object> startOrResume(JavaPuppetNode puppetNode,
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
        if (puppetNode == null) {
            throw new IllegalArgumentException("puppetNode不能为空");
        }

        int t = clampInt(threads, DEFAULT_THREADS, 1, MAX_THREADS);
        int cs = clampInt(chunkSize, DEFAULT_CHUNK_SIZE, 1, MAX_CHUNK_SIZE);

        @SuppressWarnings("unchecked")
        Map<String, Object> probe = puppetNode.fileDownloadChunk(filePath, 1L, 0L);
        long expectedLength = toLong(probe.get("length"));

        @SuppressWarnings("unchecked")
        Map<String, Object> md5Res = puppetNode.getFileMD5(filePath);
        String expectedMd5 = extractMd5(md5Res);
        if (expectedMd5 == null || expectedMd5.isBlank()) {
            throw new IllegalStateException("获取远端文件MD5失败");
        }

        String taskId = computeTaskId(puppetNode.getHostId(), filePath, expectedLength, expectedMd5);

        DownloadTask existing = tasksById.get(taskId);
        if (existing != null) {
            existing.ensureStarted();
            return existing.snapshot();
        }

        DownloadStore store = new DownloadStore(userId, taskId);
        DownloadTask task = DownloadTask.createNewOrLoad(
                puppetNode,
                sessionId,
                taskId,
                filePath,
                t,
                cs,
                expectedLength,
                expectedMd5,
                store
        );
        DownloadTask prev = tasksById.putIfAbsent(taskId, task);
        if (prev != null) {
            prev.ensureStarted();
            return prev.snapshot();
        }
        task.ensureStarted();
        return task.snapshot();
    }

    public Map<String, Object> resume(JavaPuppetNode puppetNode, String userId, String sessionId, String taskId) throws Exception {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("缺少必要参数: userId");
        }
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("缺少必要参数: sessionId");
        }
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("缺少必要参数: taskId");
        }
        if (puppetNode == null) {
            throw new IllegalArgumentException("puppetNode不能为空");
        }

        DownloadTask t = tasksById.get(taskId);
        if (t != null) {
            t.ensureStarted();
            return t.snapshot();
        }

        DownloadStore store = new DownloadStore(userId, taskId);
        if (!store.getTaskDir().exists()) {
            throw new IllegalArgumentException("任务不存在: " + taskId);
        }
        DownloadTask task = DownloadTask.loadFromDisk(puppetNode, sessionId, taskId, store);
        DownloadTask prev = tasksById.putIfAbsent(taskId, task);
        if (prev != null) {
            prev.ensureStarted();
            return prev.snapshot();
        }
        task.ensureStarted();
        return task.snapshot();
    }

    public Map<String, Object> progress(String userId, String taskId) {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("缺少必要参数: taskId");
        }
        DownloadTask task = tasksById.get(taskId);
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
            Map<String, Object> snapshot = task.snapshot();
            if (sessionId.equals(String.valueOf(snapshot.get("sessionId")))) {
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
            DownloadTask.State s = task.getState();
            if (isTerminalState(s) && task.getEndAtMs() > 0 && (now - task.getEndAtMs()) > FINISHED_TASK_RETAIN_MS) {
                tasksById.remove(entry.getKey());
                evicted++;
            }
        }
        return evicted;
    }

    private static boolean isTerminalState(DownloadTask.State s) {
        return s == DownloadTask.State.COMPLETED || s == DownloadTask.State.FAILED || s == DownloadTask.State.CANCELLED;
    }

    public Map<String, Object> cancel(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("缺少必要参数: taskId");
        }
        DownloadTask task = tasksById.get(taskId);
        if (task == null) {
            return Collections.singletonMap("taskId", taskId);
        }
        task.cancel();
        return task.snapshot();
    }

    private static long toLong(Object obj) {
        if (obj == null) {
            return 0L;
        }
        if (obj instanceof Number) {
            return ((Number) obj).longValue();
        }
        return Long.parseLong(String.valueOf(obj));
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
        if (codeObj instanceof Number && ((Number) codeObj).intValue() != 200) {
            return null;
        }
        Object md5 = md5Res.get("md5");
        if (md5 == null) {
            md5 = md5Res.get("data");
        }
        return md5 == null ? null : String.valueOf(md5);
    }

    private static String computeTaskId(String hostId, String filePath, long len, String md5) throws Exception {
        String input = String.valueOf(hostId) + "|" + filePath + "|" + len + "|" + md5;
        MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
        byte[] digest = sha256.digest(input.getBytes(StandardCharsets.UTF_8));
        String b64 = Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        return b64.substring(0, 16);
    }
}

