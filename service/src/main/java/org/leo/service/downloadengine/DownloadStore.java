package org.leo.service.downloadengine;

import org.leo.core.config.LeoConfig;
import org.leo.core.util.json.JsonUtil;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;

public class DownloadStore {
    private static final String META_JSON = "meta.json";
    private static final String CHUNKS_BITMAP = "chunks.bitmap";
    private static final String TEMP_FILE = "target.part";

    private final File taskDir;

    private final File userRootDir;

    public DownloadStore(String userId, String taskId) {
        this.userRootDir = computeUserRootDir(userId);
        this.taskDir = computeTaskDir(this.userRootDir, taskId);
    }

    public File getTaskDir() {
        return taskDir;
    }

    public File getUserRootDir() {
        return userRootDir;
    }

    public File getTempFile() {
        return new File(taskDir, TEMP_FILE);
    }

    public File getMetaFile() {
        return new File(taskDir, META_JSON);
    }

    public File getChunksFile() {
        return new File(taskDir, CHUNKS_BITMAP);
    }

    public static File getTasksRootDir(String userId) {
        File userDownloadsDir = computeUserRootDir(userId);
        File tasksRoot = new File(userDownloadsDir, ".tasks");
        if (!tasksRoot.exists()) {
            tasksRoot.mkdirs();
        }
        return tasksRoot;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> readMeta() throws Exception {
        File meta = getMetaFile();
        if (!meta.exists()) {
            return null;
        }
        String json = new String(Files.readAllBytes(meta.toPath()), StandardCharsets.UTF_8);
        if (json.isBlank()) {
            return null;
        }
        return (Map<String, Object>) JsonUtil.fromJsonString(json, HashMap.class);
    }

    public void writeMeta(Map<String, Object> meta) throws Exception {
        if (meta == null) {
            return;
        }
        String json = JsonUtil.toJsonString(meta);
        Path p = getMetaFile().toPath();
        Files.write(p, json.getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    public byte[] readChunksBitmap() throws Exception {
        File f = getChunksFile();
        if (!f.exists()) {
            return null;
        }
        return Files.readAllBytes(f.toPath());
    }

    public void writeChunksBitmap(byte[] data) throws Exception {
        if (data == null) {
            return;
        }
        Path p = getChunksFile().toPath();
        Files.write(p, data, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    private static File computeUserRootDir(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId不能为空");
        }
        String vfsPath = LeoConfig.getVfsPath();
        if (vfsPath == null || vfsPath.isBlank()) {
            vfsPath = "root";
        }
        File root = new File(vfsPath);
        File usersDir = new File(root, "users");
        File userDir = new File(usersDir, userId.trim());
        File downloadsDir = new File(userDir, "downloads");
        if (!downloadsDir.exists()) {
            downloadsDir.mkdirs();
        }
        return downloadsDir;
    }

    private static File computeTaskDir(File userDownloadsDir, String taskId) {
        if (userDownloadsDir == null) {
            throw new IllegalArgumentException("userDownloadsDir不能为空");
        }
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("taskId不能为空");
        }
        File tasksRoot = new File(userDownloadsDir, ".tasks");
        if (!tasksRoot.exists()) {
            tasksRoot.mkdirs();
        }
        File taskDir = new File(tasksRoot, taskId.trim());
        if (!taskDir.exists()) {
            taskDir.mkdirs();
        }
        return taskDir;
    }

    public File resolveUniqueFinalFile(String remoteFilePath) {
        String baseName = remoteBaseName(remoteFilePath);
        // Final file stored directly under user's downloads directory (not under task dir)
        return resolveUniqueFile(new File(userRootDir, baseName));
    }

    /**
     * Convert an absolute file path to a path relative to root/users/{userId}/.
     * Returns null if file is outside the user directory.
     */
    public String toUserRelativePath(File f) {
        if (f == null) {
            return null;
        }
        try {
            // userRootDir = root/users/{userId}/downloads
            Path userDir = userRootDir.getParentFile().toPath().toAbsolutePath().normalize(); // root/users/{userId}
            Path target = f.toPath().toAbsolutePath().normalize();
            if (!target.startsWith(userDir)) {
                return null;
            }
            String rel = userDir.relativize(target).toString();
            rel = rel.replace(File.separatorChar, '/');
            // Prevent oddities
            if (rel.contains("..")) {
                return null;
            }
            return rel;
        } catch (Exception e) {
            return null;
        }
    }

    private File resolveUniqueFile(File candidate) {
        if (!candidate.exists()) {
            return candidate;
        }
        String name = candidate.getName();
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; i <= 9999; i++) {
            File f = new File(candidate.getParentFile(), stem + "(" + i + ")" + ext);
            if (!f.exists()) {
                return f;
            }
        }
        return new File(candidate.getParentFile(), stem + "(" + System.currentTimeMillis() + ")" + ext);
    }

    private static String remoteBaseName(String remotePath) {
        if (remotePath == null || remotePath.isBlank()) {
            return "downloaded.bin";
        }
        String p = remotePath.trim().replace('\\', '/');
        int idx = p.lastIndexOf('/');
        String name = idx >= 0 ? p.substring(idx + 1) : p;
        if (name.isBlank()) {
            return "downloaded.bin";
        }
        // Basic sanitization for filesystem.
        name = name.replaceAll("[\\r\\n\\t\\\\/]+", "_");
        return name;
    }
}

