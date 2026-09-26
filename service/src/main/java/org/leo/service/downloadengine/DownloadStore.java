package org.leo.service.downloadengine;

import org.leo.core.config.LeoConfig;
import org.leo.core.util.json.JsonUtil;
import org.leo.service.transfer.TransferTaskState;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.FileVisitResult;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.Map;

public class DownloadStore {
    private static final long MIN_FREE_SPACE_RESERVE_BYTES = 64L * 1024L * 1024L;
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

    public File getTempFile() {
        validateTaskPath();
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
        return new File(userDownloadsDir, ".tasks");
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> readMeta() throws Exception {
        validateTaskPath();
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
        ensureTaskDirectory();
        Files.write(p, json.getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    public byte[] readChunksBitmap() throws Exception {
        validateTaskPath();
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
        ensureTaskDirectory();
        Files.write(p, data, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    /**
     * Ensures the local VFS has enough room for the remaining download while
     * preserving a small operational reserve for metadata and application logs.
     */
    public void requireUsableSpace(long requiredBytes) throws Exception {
        ensureTaskDirectory();
        long usable = Files.getFileStore(taskDir.toPath()).getUsableSpace();
        long required = Math.max(0L, requiredBytes);
        if (required > Math.max(0L, usable - MIN_FREE_SPACE_RESERVE_BYTES)) {
            throw new IllegalStateException(
                    "平台存储空间不足: required=" + required + ", usable=" + usable);
        }
    }

    /** Removes resumable metadata and partial data, leaving a committed download untouched. */
    public boolean deleteTaskArtifacts() {
        validateTaskPath();
        return deleteRecursively(taskDir);
    }

    private static File computeUserRootDir(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId不能为空");
        }
        if (userId.indexOf('/') >= 0 || userId.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("userId格式无效");
        }
        String vfsPath = LeoConfig.getVfsPath();
        if (vfsPath == null || vfsPath.isBlank()) {
            vfsPath = "root";
        }
        Path usersDir = new File(vfsPath, "users").toPath().toAbsolutePath().normalize();
        Path userPath = usersDir.resolve(userId.trim()).normalize();
        if (userPath.equals(usersDir) || !userPath.startsWith(usersDir)) {
            throw new IllegalArgumentException("userId格式无效");
        }
        File userDir = userPath.toFile();
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
        if (!taskId.matches("[A-Za-z0-9_-]{1,128}")) {
            throw new IllegalArgumentException("taskId格式无效");
        }
        File tasksRoot = new File(userDownloadsDir, ".tasks");
        return new File(tasksRoot, taskId.trim());
    }

    private void ensureTaskDirectory() throws Exception {
        validateTaskPath();
        Path path = taskDir.toPath();
        if (Files.isSymbolicLink(path)) {
            throw new IllegalStateException("下载任务目录格式无效");
        }
        Files.createDirectories(path);
        if (Files.isSymbolicLink(path)) {
            throw new IllegalStateException("下载任务目录格式无效");
        }
    }

    private void validateTaskPath() {
        Path tasksRoot = new File(userRootDir, ".tasks").toPath().toAbsolutePath().normalize();
        Path path = taskDir.toPath().toAbsolutePath().normalize();
        if (path.equals(tasksRoot) || !path.startsWith(tasksRoot)) {
            throw new IllegalStateException("下载任务路径越界");
        }
        if (Files.isSymbolicLink(path)) {
            throw new IllegalStateException("下载任务目录格式无效");
        }
    }

    /** Cleans terminal task directories left on disk, including records from an earlier process. */
    @SuppressWarnings("unchecked")
    public static int evictExpiredTaskArtifacts(long expireBeforeMs) {
        String vfsPath = LeoConfig.getVfsPath();
        if (vfsPath == null || vfsPath.isBlank()) {
            vfsPath = "root";
        }
        File usersRoot = new File(new File(vfsPath), "users");
        File[] users = usersRoot.listFiles(File::isDirectory);
        if (users == null) {
            return 0;
        }
        int removed = 0;
        for (File userDir : users) {
            File tasksRoot = new File(new File(userDir, "downloads"), ".tasks");
            File[] taskDirs = tasksRoot.listFiles(File::isDirectory);
            if (taskDirs == null) {
                continue;
            }
            for (File candidate : taskDirs) {
                try {
                    File metaFile = new File(candidate, META_JSON);
                    if (!metaFile.isFile()) {
                        continue;
                    }
                    String json = new String(
                            Files.readAllBytes(metaFile.toPath()), StandardCharsets.UTF_8);
                    Map<String, Object> meta =
                            (Map<String, Object>) JsonUtil.fromJsonString(json, HashMap.class);
                    TransferTaskState state = TransferTaskState.valueOf(
                            String.valueOf(meta.get("state")));
                    long endAt = numberValue(meta.get("endAtMs"));
                    long updatedAt = numberValue(meta.get("updatedAtMs"));
                    long terminalAt = Math.max(endAt, updatedAt);
                    if (state.isTerminal() && terminalAt > 0L && terminalAt < expireBeforeMs
                            && deleteRecursively(candidate)) {
                        removed++;
                    }
                } catch (Exception ignored) {
                    // A malformed or active task directory is retained for explicit inspection.
                }
            }
        }
        return removed;
    }

    private static long numberValue(Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value == null) {
            return 0L;
        }
        return Long.parseLong(String.valueOf(value));
    }

    private static boolean deleteRecursively(File target) {
        if (target == null) {
            return true;
        }
        Path targetPath = target.toPath();
        if (!Files.exists(targetPath, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return true;
        }
        try {
            Files.walkFileTree(targetPath, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                        throws java.io.IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, java.io.IOException error)
                        throws java.io.IOException {
                    if (error != null) {
                        throw error;
                    }
                    Files.deleteIfExists(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
            return true;
        } catch (Exception error) {
            return false;
        }
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
        // Produce a filename accepted by both POSIX and Windows platform servers.
        name = name.replaceAll("[\\x00-\\x1f\\\\/:*?\"<>|]+", "_");
        name = name.replaceAll("[ .]+$", "");
        if (name.isBlank()) {
            return "downloaded.bin";
        }
        String stem = name;
        int dot = stem.indexOf('.');
        if (dot >= 0) {
            stem = stem.substring(0, dot);
        }
        String upperStem = stem.toUpperCase(java.util.Locale.ROOT);
        if (upperStem.equals("CON") || upperStem.equals("PRN")
                || upperStem.equals("AUX") || upperStem.equals("NUL")
                || upperStem.matches("COM[1-9]") || upperStem.matches("LPT[1-9]")) {
            name = "_" + name;
        }
        return name;
    }
}
