package org.leo.web.application.artifact;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.leo.core.infrastructure.persistence.session.AgentNodeSessionWorkDirUtil;
import org.leo.service.config.SystemConfigService;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.function.BiConsumer;

/** Curated artifacts share file storage, but exclude live AI workspaces and internal metadata. */
@Service
public class UserArtifactService {
    private static final List<String> CATEGORIES = List.of("script-builds", "ai-reports", "task-results", "imports");
    private static final Set<String> SOURCES = Set.of("script-generator", "platform-ai", "node-ai", "task-output", "manual");
    private static final long MAX_ARCHIVE_BYTES = 100L * 1024 * 1024;
    private final ObjectMapper mapper;
    private final SystemConfigService config;

    public UserArtifactService(ObjectMapper mapper, SystemConfigService config) {
        this.mapper = mapper;
        this.config = config;
    }

    public long maxUploadBytes() {
        try {
            long mb = Long.parseLong(config.getString("max.file.upload.size.mb", "100"));
            return mb > 0 && mb <= Long.MAX_VALUE / 1024 / 1024 ? mb * 1024 * 1024 : MAX_ARCHIVE_BYTES;
        } catch (NumberFormatException ignored) { return MAX_ARCHIVE_BYTES; }
    }

    public Path root(String userId) {
        return AgentNodeSessionWorkDirUtil.getUserWorkspaceDir(userId).toPath().toAbsolutePath().normalize();
    }

    public Path resolve(String userId, String relativePath, boolean artifactOnly) {
        Path base = root(userId);
        String relative = relativePath == null ? "" : relativePath.trim().replace('\\', '/');
        if (relative.startsWith("/")) relative = relative.substring(1);
        Path target = base.resolve(relative).normalize();
        if (!target.startsWith(base)) throw new IllegalArgumentException("非法路径");
        Path cursor = base;
        if (Files.isSymbolicLink(base)) throw new IllegalArgumentException("路径含符号链接");
        for (Path part : base.relativize(target)) {
            if (part.toString().startsWith(".")) throw new IllegalArgumentException("内部目录不属于成果");
            cursor = cursor.resolve(part);
            if (Files.isSymbolicLink(cursor)) throw new IllegalArgumentException("路径含符号链接");
        }
        Path path = base.relativize(target);
        if (path.getNameCount() > 0 && "ai-tasks".equals(path.getName(0).toString())) {
            // Task cards may download public task outputs; the library never edits or lists them.
            if (artifactOnly || path.getNameCount() < 4 || !"files".equals(path.getName(2).toString()))
                throw new IllegalArgumentException("运行文件请通过任务详情访问或归档");
        }
        return target;
    }

    private void walk(Path base, BiConsumer<Path, BasicFileAttributes> consumer) throws IOException {
        if (!Files.exists(base)) return;
        Files.walkFileTree(base, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (!dir.equals(base) && (dir.getFileName().toString().startsWith(".")
                        || "ai-tasks".equals(dir.getFileName().toString()))) return FileVisitResult.SKIP_SUBTREE;
                if (!dir.equals(base)) consumer.accept(dir, attrs);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (attrs.isRegularFile() && !file.getFileName().toString().startsWith(".")) consumer.accept(file, attrs);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private String relative(Path base, Path path) { return base.relativize(path).toString().replace('\\', '/'); }

    private String category(String path) {
        String first = path.split("/", 2)[0];
        return CATEGORIES.contains(first) ? first : "imports";
    }

    private Map<String, Object> fileInfo(Path base, Path path, BasicFileAttributes attrs) {
        String relative = relative(base, path);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", path.getFileName().toString());
        result.put("path", relative);
        result.put("parentPath", relative(base, path.getParent()));
        boolean directory = attrs != null ? attrs.isDirectory() : Files.isDirectory(path);
        result.put("isDirectory", directory);
        result.put("size", attrs == null || directory ? 0L : attrs.size());
        result.put("lastModified", attrs == null ? 0L : attrs.lastModifiedTime().toMillis());
        return result;
    }

    private Map<String, Object> item(Path base, Path path, BasicFileAttributes attrs) {
        Map<String, Object> result = fileInfo(base, path, attrs);
        String relative = (String) result.get("path");
        result.put("category", category(relative));
        if (attrs.isRegularFile()) result.put("metadata", metadata(base, relative));
        return result;
    }

    public Map<String, Object> overview(String userId) throws IOException {
        Path base = root(userId);
        Map<String, long[]> categories = new LinkedHashMap<>();
        CATEGORIES.forEach(key -> categories.put(key, new long[2]));
        long[] totals = new long[4]; // files, directories, bytes, last modified
        PriorityQueue<Map.Entry<Path, BasicFileAttributes>> recent = new PriorityQueue<>(
                Comparator.comparingLong(entry -> entry.getValue().lastModifiedTime().toMillis()));
        walk(base, (path, attrs) -> {
            totals[3] = Math.max(totals[3], attrs.lastModifiedTime().toMillis());
            if (attrs.isDirectory()) { totals[1]++; return; }
            totals[0]++; totals[2] += attrs.size();
            long[] counts = categories.get(category(relative(base, path)));
            counts[0]++; counts[1] += attrs.size();
            recent.add(Map.entry(path, attrs));
            if (recent.size() > 8) recent.poll();
        });
        return Map.of("totalFiles", totals[0], "totalDirectories", totals[1], "totalBytes", totals[2],
                "updatedAt", totals[3], "recentFiles", recent.stream()
                        .sorted(Comparator.comparingLong((Map.Entry<Path, BasicFileAttributes> entry) -> entry.getValue().lastModifiedTime().toMillis()).reversed())
                        .map(entry -> item(base, entry.getKey(), entry.getValue())).toList(),
                "categories", categories.entrySet().stream().map(entry -> Map.of("path", entry.getKey(),
                        "files", entry.getValue()[0], "bytes", entry.getValue()[1])).toList());
    }

    /** Returns a visible directory listing, or null when the requested directory is missing. */
    public Map<String, Object> listDirectory(String userId, String relativePath) throws IOException {
        Path target = resolve(userId, relativePath, true);
        if (!Files.exists(target)) return null;
        if (!Files.isDirectory(target)) throw new IllegalArgumentException("path不是目录");
        Path base = root(userId);
        List<Map<String, Object>> files = new ArrayList<>();
        try (var stream = Files.list(target)) {
            stream.filter(path -> !path.getFileName().toString().startsWith(".")
                            && !"ai-tasks".equals(path.getFileName().toString()) && !Files.isSymbolicLink(path))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString(), String.CASE_INSENSITIVE_ORDER))
                    .forEach(path -> {
                        BasicFileAttributes attrs;
                        try { attrs = Files.readAttributes(path, BasicFileAttributes.class); }
                        catch (Exception ignored) { attrs = null; }
                        files.add(fileInfo(base, path, attrs));
                    });
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", relative(base, target));
        result.put("count", files.size());
        result.put("fileList", files);
        return result;
    }

    public Map<String, Object> list(String userId, String category, String search, String source, int page, int pageSize) throws IOException {
        if (category != null && !category.isBlank() && !CATEGORIES.contains(category)) throw new IllegalArgumentException("未知成果分类");
        int size = Math.max(1, Math.min(200, pageSize));
        int current = Math.max(1, page);
        String keyword = search == null ? "" : search.trim().toLowerCase(Locale.ROOT);
        List<Map<String, Object>> files = new ArrayList<>();
        Path base = root(userId);
        walk(base, (path, attrs) -> {
            if (!attrs.isRegularFile()) return;
            String relative = relative(base, path);
            if (category != null && !category.isBlank() && !category.equals(category(relative))) return;
            Map<String, Object> item = item(base, path, attrs);
            Map<?, ?> metadata = (Map<?, ?>) item.get("metadata");
            String module = Objects.toString(metadata.get("sourceModule"), "unrecorded");
            if (source != null && !source.isBlank() && !source.equals(module)) return;
            String searchable = relative + " " + metadata.values();
            if (!keyword.isEmpty() && !searchable.toLowerCase(Locale.ROOT).contains(keyword)) return;
            files.add(item);
        });
        files.sort(Comparator.comparingLong((Map<String, Object> file) -> ((Number) file.get("lastModified")).longValue())
                .reversed().thenComparing(file -> String.valueOf(file.get("path"))));
        int start = (int) Math.min(files.size(), (long) (current - 1) * size);
        int end = Math.min(files.size(), start + size);
        return Map.of("fileList", new ArrayList<>(files.subList(start, end)), "count", files.size(),
                "page", current, "pageSize", size, "hasMore", end < files.size());
    }

    private Path metadataPath(Path base, String path) {
        try {
            String key = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(path.getBytes(StandardCharsets.UTF_8)));
            Path directory = base.resolve(".artifacts");
            if (Files.isSymbolicLink(directory)) throw new IllegalArgumentException("元数据目录含符号链接");
            return directory.resolve(key + ".json");
        } catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> metadata(Path base, String path) {
        try {
            Path file = metadataPath(base, path);
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > 16 * 1024) return Map.of();
            Map<String, Object> result = mapper.readValue(Files.readString(file), Map.class);
            return result == null ? Map.of() : result;
        } catch (Exception ignored) { return Map.of(); }
    }

    private void record(Path base, Path target, Map<String, String> source) throws IOException {
        String relative = relative(base, target);
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("id", UUID.randomUUID().toString());
        record.put("createdAt", Instant.now().toString());
        record.put("category", category(relative));
        for (String key : List.of("title", "sourceModule", "sourceId", "sessionId", "hostName")) {
            String value = source.get(key);
            if (value != null && !value.isBlank()) {
                if (value.length() > 512) throw new IllegalArgumentException("成果来源信息过长");
                record.put(key, value.trim());
            }
        }
        String module = String.valueOf(record.getOrDefault("sourceModule", "manual"));
        if (!SOURCES.contains(module)) throw new IllegalArgumentException("未知成果来源");
        record.put("sourceModule", module);
        Path metadata = metadataPath(base, relative);
        Files.createDirectories(metadata.getParent());
        Path temporary = Files.createTempFile(metadata.getParent(), ".pending-", ".json");
        try {
            mapper.writeValue(temporary.toFile(), record);
            try { Files.move(temporary, metadata, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException ignored) { Files.move(temporary, metadata, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }

    public void recordManual(String userId, Path target) throws IOException {
        record(root(userId), target, Map.of("title", target.getFileName().toString(), "sourceModule", "manual"));
    }

    public void removeMetadata(String userId, Path target) throws IOException {
        Path base = root(userId);
        Files.deleteIfExists(metadataPath(base, relative(base, target)));
    }

    public Map<String, Object> archive(String userId, ArchiveRequest request) throws IOException {
        if (request == null || !CATEGORIES.contains(request.category())) throw new IllegalArgumentException("未知成果分类");
        String name = request.filename();
        if (name == null || name.isBlank() || name.length() > 200 || name.startsWith(".")
                || name.contains("/") || name.contains("\\")) throw new IllegalArgumentException("无效成果文件名");
        Path base = root(userId);
        Path directory = resolve(userId, request.category(), true);
        Files.createDirectories(directory);
        Path target = directory.resolve(UUID.randomUUID().toString().substring(0, 8) + "-" + name);
        Map<String, String> source = new LinkedHashMap<>(request.metadata() == null ? Map.of() : request.metadata());
        long maxBytes = maxUploadBytes();
        Path input = null;
        byte[] content = null;
        if (request.sourcePath() != null && !request.sourcePath().isBlank()) {
            if (!"task-results".equals(request.category())) throw new IllegalArgumentException("任务文件应归档到任务输出");
            input = resolve(userId, request.sourcePath(), false);
            Path relative = base.relativize(input);
            if (relative.getNameCount() < 4 || !"ai-tasks".equals(relative.getName(0).toString())
                    || !"files".equals(relative.getName(2).toString()) || !Files.isRegularFile(input, LinkOption.NOFOLLOW_LINKS))
                throw new IllegalArgumentException("请选择任务公开输出文件");
            if (Files.size(input) > maxBytes) throw new IllegalArgumentException("成果文件超过归档上限");
            source.put("sourceModule", "task-output");
            source.put("sourceId", relative.getName(1).toString());
        } else {
            if (request.content() == null || request.content().isBlank()) throw new IllegalArgumentException("成果内容为空");
            content = request.content().getBytes(StandardCharsets.UTF_8);
            if (content.length > maxBytes) throw new IllegalArgumentException("成果内容超过归档上限");
        }
        Files.createFile(target);
        try {
            if (input != null) Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
            else Files.write(target, content, StandardOpenOption.TRUNCATE_EXISTING);
            record(base, target, source);
            return item(base, target, Files.readAttributes(target, BasicFileAttributes.class));
        } catch (Exception error) {
            Files.deleteIfExists(target);
            removeMetadata(userId, target);
            throw error;
        }
    }

    public record ArchiveRequest(String category, String filename, String content, String sourcePath, Map<String, String> metadata) { }
}
