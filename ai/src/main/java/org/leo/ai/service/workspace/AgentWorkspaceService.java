package org.leo.ai.service.workspace;

import org.leo.ai.agent.AiToolContext;
import org.leo.ai.config.AgentWorkspaceProperties;
import org.leo.core.entity.AiExecutionPolicy;
import org.leo.core.util.session.PuppetNodeSessionWorkDirUtil;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.regex.Pattern;

/** 用户与 AI 任务双重隔离的持久化工作空间。 */
@Service
public class AgentWorkspaceService {

    private static final String TASKS_DIR = "ai-tasks";
    private final AgentWorkspaceProperties properties;
    private final ConcurrentHashMap<String, ReentrantReadWriteLock> locks = new ConcurrentHashMap<>();

    public AgentWorkspaceService(AgentWorkspaceProperties properties) {
        this.properties = properties;
    }

    public Workspace workspaceFromContext() {
        AiExecutionPolicy policy = AiToolContext.requireExecutionPolicy();
        String memoryKey = AiToolContext.getThreadId() == null
                ? AiToolContext.requireSessionId()
                : AiToolContext.requireSessionId() + ":" + AiToolContext.getThreadId();
        return open(policy.getUserId(), workspaceId(memoryKey));
    }

    public Workspace open(String userId, String workspaceId) {
        if (userId == null || userId.isBlank()) throw new SecurityException("缺少用户身份");
        if (!userId.matches("[A-Za-z0-9_-]{1,128}")) {
            throw new SecurityException("非法用户标识");
        }
        if (workspaceId == null || !workspaceId.matches("[0-9a-f]{32}")) {
            throw new IllegalArgumentException("非法 workspaceId");
        }
        Path userRoot = PuppetNodeSessionWorkDirUtil.getUserWorkspaceDir(userId).toPath()
                .toAbsolutePath().normalize();
        Path base = userRoot.resolve(TASKS_DIR).resolve(workspaceId).normalize();
        if (!base.startsWith(userRoot)) throw new SecurityException("工作空间越界");
        Path files = base.resolve("files");
        Path internal = base.resolve(".internal");
        try {
            Files.createDirectories(files);
            Files.createDirectories(internal.resolve("versions"));
            Files.createDirectories(internal.resolve("trash"));
            Files.createDirectories(internal.resolve("tmp"));
        } catch (IOException e) {
            throw new IllegalStateException("无法初始化 Agent 工作空间", e);
        }
        return new Workspace(userId, workspaceId, userRoot, base, files, internal,
                locks.computeIfAbsent(userId + ":" + workspaceId,
                        ignored -> new ReentrantReadWriteLock()));
    }

    public Map<String, Object> list(Workspace workspace, String relativePath,
                                    int depth, int maxEntries) {
        int safeDepth = Math.max(1, Math.min(depth <= 0 ? 1 : depth, 8));
        int safeLimit = Math.max(1, Math.min(maxEntries <= 0 ? 200 : maxEntries, 1000));
        ReentrantReadWriteLock.ReadLock lock = workspace.lock().readLock();
        lock.lock();
        try {
            Path base = resolve(workspace, relativePath, true);
            if (!Files.isDirectory(base, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException("path 不是目录");
            }
            List<Map<String, Object>> entries = new ArrayList<>();
            try (var stream = Files.walk(base, safeDepth)) {
                stream.filter(path -> !path.equals(base))
                        .filter(path -> !Files.isSymbolicLink(path))
                        .sorted(Comparator.comparing(path -> toRelative(workspace, path)))
                        .limit(safeLimit)
                        .forEach(path -> {
                            try { entries.add(describe(workspace, path)); }
                            catch (IOException ignored) { }
                        });
            }
            Map<String, Object> result = baseResult(workspace);
            result.put("path", toRelative(workspace, base));
            result.put("entries", entries);
            result.put("returned", entries.size());
            result.put("limit", safeLimit);
            return result;
        } catch (IOException e) {
            throw new IllegalStateException("列出工作空间失败", e);
        } finally {
            lock.unlock();
        }
    }

    public Map<String, Object> readText(Workspace workspace, String relativePath,
                                        int startLine, int maxLines) {
        int from = Math.max(1, startLine <= 0 ? 1 : startLine);
        int lineLimit = Math.max(1, Math.min(maxLines <= 0 ? 200 : maxLines, 2000));
        ReentrantReadWriteLock.ReadLock lock = workspace.lock().readLock();
        lock.lock();
        try {
            Path target = requireRegularFile(workspace, relativePath);
            long size = Files.size(target);
            if (size > properties.getMaxFileBytes()) {
                throw new IllegalArgumentException("文件超过 Agent 单文件读取上限");
            }
            byte[] bytes = Files.readAllBytes(target);
            requireText(bytes);
            String content = new String(bytes, StandardCharsets.UTF_8);
            List<String> lines = content.lines().toList();
            int startIndex = Math.min(from - 1, lines.size());
            StringBuilder selected = new StringBuilder();
            int endIndex = startIndex;
            boolean truncated = false;
            while (endIndex < lines.size() && endIndex - startIndex < lineLimit) {
                String line = lines.get(endIndex);
                if (selected.length() + line.length() + 1 > properties.getMaxReadChars()) {
                    truncated = true;
                    break;
                }
                if (!selected.isEmpty()) selected.append('\n');
                selected.append(line);
                endIndex++;
            }
            if (endIndex < lines.size()) truncated = true;
            Map<String, Object> result = fileRef(workspace, target, bytes);
            result.put("startLine", from);
            result.put("endLine", endIndex);
            result.put("totalLines", lines.size());
            result.put("content", selected.toString());
            result.put("truncated", truncated);
            result.put("nextStartLine", truncated ? endIndex + 1 : null);
            return result;
        } catch (IOException e) {
            throw new IllegalStateException("读取工作空间文件失败", e);
        } finally {
            lock.unlock();
        }
    }

    public Map<String, Object> search(Workspace workspace, String query, String relativePath,
                                      String glob, boolean regex, int maxResults) {
        if (query == null || query.isBlank()) throw new IllegalArgumentException("query 不能为空");
        int limit = Math.max(1, Math.min(maxResults <= 0 ? 100 : maxResults,
                properties.getMaxSearchResults()));
        Pattern pattern = regex ? Pattern.compile(query) : null;
        var matcher = glob == null || glob.isBlank() ? null
                : workspace.filesRoot().getFileSystem().getPathMatcher("glob:" + glob.trim());
        ReentrantReadWriteLock.ReadLock lock = workspace.lock().readLock();
        lock.lock();
        try {
            Path base = resolve(workspace, relativePath, true);
            List<Map<String, Object>> matches = new ArrayList<>();
            try (var stream = Files.walk(base)) {
                var iterator = stream.iterator();
                while (iterator.hasNext() && matches.size() < limit) {
                    Path file = iterator.next();
                    if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                            || Files.isSymbolicLink(file)
                            || Files.size(file) > Math.min(properties.getMaxFileBytes(), 4L * 1024 * 1024)) continue;
                    Path relative = workspace.filesRoot().relativize(file);
                    if (matcher != null && !matcher.matches(relative)) continue;
                    byte[] bytes = Files.readAllBytes(file);
                    if (isBinary(bytes)) continue;
                    List<String> lines = new String(bytes, StandardCharsets.UTF_8).lines().toList();
                    for (int i = 0; i < lines.size() && matches.size() < limit; i++) {
                        String line = lines.get(i);
                        boolean found = regex ? pattern.matcher(line).find() : line.contains(query);
                        if (found) {
                            Map<String, Object> hit = new LinkedHashMap<>();
                            hit.put("path", relative.toString().replace('\\', '/'));
                            hit.put("line", i + 1);
                            hit.put("text", line.length() > 500 ? line.substring(0, 500) : line);
                            matches.add(hit);
                        }
                    }
                }
            }
            Map<String, Object> result = baseResult(workspace);
            result.put("matches", matches);
            result.put("returned", matches.size());
            result.put("limit", limit);
            return result;
        } catch (IOException e) {
            throw new IllegalStateException("搜索工作空间失败", e);
        } finally {
            lock.unlock();
        }
    }

    public Map<String, Object> writeText(Workspace workspace, String relativePath,
                                         String content, String expectedSha256) {
        if (content == null) throw new IllegalArgumentException("content 不能为空");
        if (content.length() > properties.getMaxWriteChars()) {
            throw new IllegalArgumentException("单次写入内容过大；请先写脚本或使用 workspaceApplyPatch 分段修改");
        }
        return mutate(workspace, relativePath, content, expectedSha256);
    }

    public Map<String, Object> applyPatch(Workspace workspace, String relativePath,
                                          String patch, String expectedSha256) {
        if (expectedSha256 == null || expectedSha256.isBlank()) {
            throw new IllegalArgumentException("修改现有文件必须提供 workspaceReadText 返回的 sha256");
        }
        ReentrantReadWriteLock.WriteLock lock = workspace.lock().writeLock();
        lock.lock();
        try {
            Path target = requireRegularFile(workspace, relativePath);
            byte[] oldBytes = readManagedBytes(target);
            requireExpectedHash(oldBytes, expectedSha256);
            requireText(oldBytes);
            String updated = UnifiedDiffApplier.apply(
                    new String(oldBytes, StandardCharsets.UTF_8), patch);
            return writeLocked(workspace, target, oldBytes,
                    updated.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("应用补丁失败", e);
        } finally {
            lock.unlock();
        }
    }

    public Map<String, Object> delete(Workspace workspace, String relativePath,
                                      String expectedSha256) {
        ReentrantReadWriteLock.WriteLock lock = workspace.lock().writeLock();
        lock.lock();
        try {
            Path target = resolve(workspace, relativePath, false);
            if (target.equals(workspace.filesRoot())) throw new IllegalArgumentException("不能删除工作空间根目录");
            if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException("路径不存在");
            }
            if (Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                requireExpectedHash(readManagedBytes(target), expectedSha256);
            }
            String trashId = Instant.now().toEpochMilli() + "-" + target.getFileName();
            Path trash = workspace.internalRoot().resolve("trash").resolve(trashId);
            Files.createDirectories(trash.getParent());
            move(target, trash);
            Map<String, Object> result = baseResult(workspace);
            result.put("deleted", true);
            result.put("path", toRelative(workspace, target));
            result.put("trashId", trashId);
            result.put("recoverable", true);
            return result;
        } catch (IOException e) {
            throw new IllegalStateException("删除工作空间路径失败", e);
        } finally {
            lock.unlock();
        }
    }

    public Map<String, Object> promote(Workspace workspace, String sourcePath,
                                       String outputPath) {
        ReentrantReadWriteLock.WriteLock lock = workspace.lock().writeLock();
        lock.lock();
        try {
            Path source = requireRegularFile(workspace, sourcePath);
            String targetRelative = outputPath == null || outputPath.isBlank()
                    ? "output/" + source.getFileName() : "output/" + outputPath;
            Path target = resolve(workspace, targetRelative, false);
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException("output 目标已存在；请选择新路径或先确认删除旧制品");
            }
            byte[] bytes = readManagedBytes(source);
            Map<String, Object> result = writeLocked(workspace, target, null, bytes);
            result.put("promoted", true);
            result.put("sourcePath", toRelative(workspace, source));
            return result;
        } catch (IOException e) {
            throw new IllegalStateException("发布工作空间制品失败", e);
        } finally {
            lock.unlock();
        }
    }

    /** 保存系统生成的日志或抓取结果，仍然执行容量、版本与原子写校验。 */
    public Map<String, Object> writeGeneratedBytes(Workspace workspace, String relativePath,
                                                   byte[] bytes) {
        ReentrantReadWriteLock.WriteLock lock = workspace.lock().writeLock();
        lock.lock();
        try {
            Path target = resolve(workspace, relativePath, false);
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException("系统生成文件目标已存在，请使用新路径");
            }
            return writeLocked(workspace, target, null, bytes);
        } catch (IOException e) {
            throw new IllegalStateException("保存系统生成文件失败", e);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 在启动外部文件传输前校验工作空间目标和预计大小，避免下载完成后才发现配额不足。
     */
    public Map<String, Object> validateGeneratedFileTarget(Workspace workspace,
                                                            String relativePath,
                                                            long expectedBytes) {
        ReentrantReadWriteLock.ReadLock lock = workspace.lock().readLock();
        lock.lock();
        try {
            Path target = resolve(workspace, relativePath, false);
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException("系统生成文件目标已存在，请使用新路径");
            }
            long safeSize = Math.max(0L, expectedBytes);
            if (safeSize > properties.getMaxFileBytes()) {
                throw new IllegalArgumentException("文件超过 Agent 单文件上限");
            }
            checkQuota(workspace, 0L, safeSize, true);
            Map<String, Object> result = baseResult(workspace);
            result.put("path", toRelative(workspace, target));
            result.put("expectedBytes", safeSize);
            return result;
        } catch (IOException e) {
            throw new IllegalStateException("校验工作空间文件目标失败", e);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 将平台侧已经落盘的普通文件原子复制进当前 Agent 工作空间。
     * 相同内容的重复提交是幂等的，目标路径存在但内容不同时保持冲突语义。
     */
    public Map<String, Object> writeGeneratedFile(Workspace workspace, String relativePath,
                                                  Path source) {
        ReentrantReadWriteLock.WriteLock lock = workspace.lock().writeLock();
        lock.lock();
        Path temp = null;
        try {
            if (source == null || Files.isSymbolicLink(source)
                    || !Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException("导入源不是普通文件");
            }
            long sourceSize = Files.size(source);
            if (sourceSize > properties.getMaxFileBytes()) {
                throw new IllegalArgumentException("文件超过 Agent 单文件上限");
            }
            String sourceSha256 = sha256(source);
            Path target = resolve(workspace, relativePath, false);
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
                        && !Files.isSymbolicLink(target)
                        && Files.size(target) == sourceSize
                        && sourceSha256.equalsIgnoreCase(sha256(target))) {
                    Map<String, Object> existing = fileRef(
                            workspace, target, sourceSize, sourceSha256);
                    existing.put("created", false);
                    existing.put("reused", true);
                    return existing;
                }
                throw new IllegalArgumentException("系统生成文件目标已存在且内容不同，请使用新路径");
            }
            checkQuota(workspace, 0L, sourceSize, true);
            Files.createDirectories(target.getParent());
            temp = Files.createTempFile(workspace.internalRoot().resolve("tmp"),
                    "import-", ".tmp");
            Files.copy(source, temp, StandardCopyOption.REPLACE_EXISTING);
            if (Files.size(temp) != sourceSize
                    || !sourceSha256.equalsIgnoreCase(sha256(temp))) {
                throw new IllegalStateException("导入源在复制期间发生变化");
            }
            move(temp, target);
            temp = null;
            Map<String, Object> result = fileRef(
                    workspace, target, sourceSize, sourceSha256);
            result.put("created", true);
            result.put("reused", false);
            return result;
        } catch (IOException e) {
            throw new IllegalStateException("导入系统生成文件失败", e);
        } finally {
            if (temp != null) {
                try { Files.deleteIfExists(temp); }
                catch (IOException ignored) { }
            }
            lock.unlock();
        }
    }

    private Map<String, Object> mutate(Workspace workspace, String relativePath, String content,
                                       String expectedSha256) {
        ReentrantReadWriteLock.WriteLock lock = workspace.lock().writeLock();
        lock.lock();
        try {
            Path target = resolve(workspace, relativePath, false);
            byte[] oldBytes = Files.exists(target, LinkOption.NOFOLLOW_LINKS)
                    ? readManagedBytes(requireRegularFile(workspace, relativePath)) : null;
            if (oldBytes != null) {
                if (expectedSha256 == null || expectedSha256.isBlank()) {
                    throw new IllegalArgumentException("覆盖现有文件必须提供 expectedSha256");
                }
                requireExpectedHash(oldBytes, expectedSha256);
            }
            return writeLocked(workspace, target, oldBytes,
                    content.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("写入工作空间失败", e);
        } finally {
            lock.unlock();
        }
    }

    private Map<String, Object> writeLocked(Workspace workspace, Path target,
                                             byte[] oldBytes, byte[] newBytes) throws IOException {
        if (newBytes.length > properties.getMaxFileBytes()) {
            throw new IllegalArgumentException("文件超过 Agent 单文件上限");
        }
        checkQuota(workspace, oldBytes != null ? oldBytes.length : 0, newBytes.length,
                oldBytes == null);
        if (oldBytes != null) archiveVersion(workspace, oldBytes);
        Files.createDirectories(target.getParent());
        Path temp = Files.createTempFile(workspace.internalRoot().resolve("tmp"), "write-", ".tmp");
        Files.write(temp, newBytes);
        move(temp, target);
        Map<String, Object> result = fileRef(workspace, target, newBytes);
        result.put("previousSha256", oldBytes != null ? sha256(oldBytes) : null);
        result.put("created", oldBytes == null);
        return result;
    }

    private void checkQuota(Workspace workspace, long oldSize, long newSize,
                            boolean addingFile) throws IOException {
        long total = 0;
        long files = 0;
        try (var stream = Files.walk(workspace.filesRoot())) {
            for (Path path : stream.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
                    .filter(p -> !Files.isSymbolicLink(p)).toList()) {
                total += Files.size(path);
                files++;
            }
        }
        if (total - oldSize + newSize > properties.getQuotaBytes()) {
            throw new IllegalArgumentException("Agent 工作空间容量已达到上限");
        }
        if (addingFile && files + 1 > properties.getMaxFiles()) {
            throw new IllegalArgumentException("Agent 工作空间文件数已达到上限");
        }
    }

    private void archiveVersion(Workspace workspace, byte[] bytes) throws IOException {
        String hash = sha256(bytes);
        Path snapshot = workspace.internalRoot().resolve("versions").resolve(hash);
        if (!Files.exists(snapshot)) Files.write(snapshot, bytes);
    }

    private Path requireRegularFile(Workspace workspace, String relativePath) {
        Path target = resolve(workspace, relativePath, false);
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(target)) {
            throw new IllegalArgumentException("path 不是普通文件: " + displayPath(relativePath));
        }
        return target;
    }

    private Path resolve(Workspace workspace, String relativePath, boolean allowRoot) {
        String value = relativePath == null ? "" : relativePath.trim();
        if (value.indexOf('\0') >= 0 || value.contains("\\")) {
            throw new IllegalArgumentException("非法工作空间路径");
        }
        Path relative = Path.of(value.isBlank() ? "." : value);
        if (relative.isAbsolute()) throw new IllegalArgumentException("只允许相对路径");
        for (Path segment : relative) {
            if ("..".equals(segment.toString())) throw new IllegalArgumentException("路径不能包含 ..");
        }
        Path target = workspace.filesRoot().resolve(relative).normalize();
        if (!target.startsWith(workspace.filesRoot())
                || (!allowRoot && target.equals(workspace.filesRoot()))) {
            throw new IllegalArgumentException("路径越过工作空间边界");
        }
        Path cursor = workspace.filesRoot();
        Path rel = workspace.filesRoot().relativize(target);
        for (Path part : rel) {
            cursor = cursor.resolve(part);
            if (Files.exists(cursor, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(cursor)) {
                throw new IllegalArgumentException("工作空间不允许符号链接");
            }
        }
        return target;
    }

    private Map<String, Object> describe(Workspace workspace, Path target) throws IOException {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("path", toRelative(workspace, target));
        item.put("type", Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS) ? "directory" : "file");
        item.put("size", Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) ? Files.size(target) : null);
        item.put("modifiedAt", Files.getLastModifiedTime(target, LinkOption.NOFOLLOW_LINKS).toInstant().toString());
        if (Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            item.put("sha256", sha256(target));
        }
        return item;
    }

    private Map<String, Object> fileRef(Workspace workspace, Path target, byte[] bytes) {
        return fileRef(workspace, target, bytes.length, sha256(bytes));
    }

    private Map<String, Object> fileRef(Workspace workspace, Path target,
                                        long size, String sha256) {
        Map<String, Object> result = baseResult(workspace);
        String path = toRelative(workspace, target);
        result.put("path", path);
        result.put("size", size);
        result.put("sha256", sha256);
        result.put("userWorkspacePath", TASKS_DIR + "/" + workspace.workspaceId()
                + "/files/" + path);
        result.put("downloadEndpoint", "/platform/user/file/download");
        return result;
    }

    private Map<String, Object> baseResult(Workspace workspace) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("workspaceId", workspace.workspaceId());
        return result;
    }

    private String toRelative(Workspace workspace, Path path) {
        return workspace.filesRoot().relativize(path).toString().replace('\\', '/');
    }

    private String displayPath(String value) {
        return value == null || value.isBlank() ? "." : value;
    }

    private void requireExpectedHash(byte[] bytes, String expected) {
        if (expected == null || !sha256(bytes).equalsIgnoreCase(expected.trim())) {
            throw new IllegalArgumentException("文件已变化，expectedSha256 不匹配；请重新读取后再修改");
        }
    }

    private void requireText(byte[] bytes) {
        if (isBinary(bytes)) throw new IllegalArgumentException("该文件不是 UTF-8 文本文件");
    }

    private boolean isBinary(byte[] bytes) {
        int sample = Math.min(bytes.length, 8_192);
        for (int i = 0; i < sample; i++) if (bytes[i] == 0) return true;
        return false;
    }

    private static String workspaceId(String memoryKey) {
        return sha256(memoryKey.getBytes(StandardCharsets.UTF_8)).substring(0, 32);
    }

    public static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private String sha256(Path path) throws IOException {
        try (var input = Files.newInputStream(path)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) >= 0) digest.update(buffer, 0, count);
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private byte[] readManagedBytes(Path path) throws IOException {
        if (Files.size(path) > properties.getMaxFileBytes()) {
            throw new IllegalArgumentException("文件超过 Agent 单文件上限");
        }
        return Files.readAllBytes(path);
    }

    private void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public record Workspace(String userId, String workspaceId, Path userRoot,
                            Path baseRoot, Path filesRoot, Path internalRoot,
                            ReentrantReadWriteLock lock) {}
}
