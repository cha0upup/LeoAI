package org.leo.web.application.artifact;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.leo.core.infrastructure.persistence.session.AgentNodeSessionWorkDirUtil;
import org.leo.service.config.SystemConfigService;
import org.springframework.core.io.InputStreamSource;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
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
    private static final long DEFAULT_PREVIEW_SIZE = 64 * 1024L;
    private static final long MAX_PREVIEW_SIZE = 1024 * 1024L;
    private static final int MAX_METADATA_BYTES = 16 * 1024;
    private static final int RECENT_FILES_LIMIT = 8;
    private static final Comparator<ArtifactMatch> ARTIFACT_ORDER = Comparator
            .comparingLong((ArtifactMatch file) -> file.attributes().lastModifiedTime().toMillis()).reversed()
            .thenComparing(ArtifactMatch::relativePath).thenComparingLong(ArtifactMatch::sequence);
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

    private static Path root(String userId) {
        return AgentNodeSessionWorkDirUtil.getUserWorkspaceDir(userId).toPath().toAbsolutePath().normalize();
    }

    private static Path resolve(Path base, String relativePath, boolean artifactOnly) {
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

    /** Finds a workspace or public task output file; missing paths and directories return null. */
    public Path findFile(String userId, String relativePath) {
        return findFile(readRoot(userId), relativePath);
    }

    private Path readRoot(String userId) {
        if (userId == null || userId.isBlank()) throw new IllegalArgumentException("用户信息无效");
        return root(userId);
    }

    private static Path findFile(Path base, String relativePath) {
        Path target = resolve(base, relativePath, false);
        return Files.isRegularFile(target) ? target : null;
    }

    private void walk(Path base, String categoryFilter, BiConsumer<Path, BasicFileAttributes> consumer) throws IOException {
        Files.walkFileTree(base, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (dir.equals(base)) return FileVisitResult.CONTINUE;
                if (hasDotPrefix(dir) || "ai-tasks".equals(dir.getFileName().toString())) return FileVisitResult.SKIP_SUBTREE;
                if (outsideCategoryAtRoot(base, dir, categoryFilter)) return FileVisitResult.SKIP_SUBTREE;
                consumer.accept(dir, attrs);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (attrs.isRegularFile() && !hasDotPrefix(file)) consumer.accept(file, attrs);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFileFailed(Path path, IOException error) throws IOException {
                // Workspace entries may disappear between enumeration and opening.
                if (error instanceof NoSuchFileException) return FileVisitResult.CONTINUE;
                // The walker opens a directory before calling preVisitDirectory.
                if (!path.equals(base) && hasDotPrefix(path)) return FileVisitResult.CONTINUE;
                if (outsideCategoryAtRoot(base, path, categoryFilter)) return FileVisitResult.CONTINUE;
                throw error;
            }
        });
    }

    private static boolean hasDotPrefix(Path path) { return path.getFileName().toString().startsWith("."); }

    private boolean outsideCategoryAtRoot(Path base, Path path, String categoryFilter) {
        return categoryFilter != null && base.equals(path.getParent())
                && !categoryFilter.equals(category(relative(base, path)));
    }

    private String relative(Path base, Path path) { return base.relativize(path).toString().replace('\\', '/'); }

    private String category(String path) {
        int separator = path.indexOf('/');
        String first = separator < 0 ? path : path.substring(0, separator);
        return CATEGORIES.contains(first) ? first : "imports";
    }

    private Map<String, Object> fileInfo(Path base, Path path, String relative, BasicFileAttributes attrs) {
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

    private Map<String, Object> item(Path base, Path path, String relative, BasicFileAttributes attrs,
                                     Map<String, Object> loadedMetadata) {
        Map<String, Object> result = fileInfo(base, path, relative, attrs);
        result.put("category", category(relative));
        if (attrs.isRegularFile()) {
            result.put("metadata", loadedMetadata != null ? loadedMetadata : metadata(base, relative));
        }
        return result;
    }

    public Map<String, Object> overview(String userId, boolean includeRecent) throws IOException {
        Path base = root(userId);
        Map<String, long[]> categories = new LinkedHashMap<>();
        CATEGORIES.forEach(key -> categories.put(key, new long[2]));
        long[] totals = new long[4]; // files, directories, bytes, last modified
        PriorityQueue<ArtifactMatch> recent = new PriorityQueue<>(ARTIFACT_ORDER.reversed());
        walk(base, null, (path, attrs) -> {
            totals[3] = Math.max(totals[3], attrs.lastModifiedTime().toMillis());
            if (attrs.isDirectory()) { totals[1]++; return; }
            totals[0]++; totals[2] += attrs.size();
            String relative = relative(base, path);
            long[] counts = categories.get(category(relative));
            counts[0]++; counts[1] += attrs.size();
            if (includeRecent) {
                retainMatch(recent, RECENT_FILES_LIMIT, new ArtifactMatch(path, relative, attrs, null, totals[0]));
            }
        });
        return Map.of("totalFiles", totals[0], "totalDirectories", totals[1], "totalBytes", totals[2],
                "updatedAt", totals[3], "recentFiles", recent.stream()
                        .sorted(ARTIFACT_ORDER)
                        .map(match -> item(base, match.path(), match.relativePath(), match.attributes(), match.metadata())).toList(),
                "categories", categories.entrySet().stream().map(entry -> Map.of("path", entry.getKey(),
                        "files", entry.getValue()[0], "bytes", entry.getValue()[1])).toList());
    }

    /** Returns a visible directory listing, or null when the requested directory is missing. */
    public Map<String, Object> listDirectory(String userId, String relativePath) throws IOException {
        Path base = root(userId);
        Path target = resolve(base, relativePath, true);
        try {
            if (!Files.readAttributes(target, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).isDirectory()) {
                throw new IllegalArgumentException("path不是目录");
            }
        } catch (NoSuchFileException ignored) {
            return null;
        }
        List<Map<String, Object>> files = new ArrayList<>();
        try (var stream = Files.list(target)) {
            stream.filter(path -> !path.getFileName().toString().startsWith(".")
                            && !"ai-tasks".equals(path.getFileName().toString()))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString(), String.CASE_INSENSITIVE_ORDER))
                    .forEach(path -> {
                        BasicFileAttributes attrs;
                        try { attrs = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS); }
                        catch (NoSuchFileException ignored) { return; }
                        catch (Exception ignored) { attrs = null; }
                        if (attrs != null ? attrs.isSymbolicLink() : Files.isSymbolicLink(path)) return;
                        files.add(fileInfo(base, path, relative(base, path), attrs));
                    });
        } catch (NoSuchFileException ignored) {
            return null;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", relative(base, target));
        result.put("count", files.size());
        result.put("fileList", files);
        return result;
    }

    /** Returns a bounded preview, or null when the requested path is not a file. */
    public Map<String, Object> preview(String userId, String relativePath, Long size, boolean includeBase64) throws IOException {
        Path base = readRoot(userId);
        Path target = findFile(base, relativePath);
        if (target == null) return null;
        long previewSize = size == null ? DEFAULT_PREVIEW_SIZE : Math.min(Math.max(size, 1L), MAX_PREVIEW_SIZE);
        long fileSize;
        byte[] actual;
        try (var channel = Files.newByteChannel(target, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            fileSize = channel.size();
            actual = Channels.newInputStream(channel).readNBytes((int) Math.min(previewSize, fileSize));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", relative(base, target));
        result.put("size", fileSize);
        result.put("previewSize", actual.length);
        result.put("truncated", fileSize > actual.length);
        result.put("content", new String(actual, StandardCharsets.UTF_8));
        if (includeBase64) result.put("contentBase64", Base64.getEncoder().encodeToString(actual));
        return result;
    }

    private record ArtifactMatch(Path path, String relativePath, BasicFileAttributes attributes,
                                 Map<String, Object> metadata, long sequence) {}

    /** Retains the best matches up to the requested limit, with the worst retained match at the head. */
    private static void retainMatch(PriorityQueue<ArtifactMatch> window, long limit, ArtifactMatch match) {
        if (window.size() < limit) {
            window.add(match);
        } else if (ARTIFACT_ORDER.compare(match, window.peek()) < 0) {
            window.poll();
            window.add(match);
        }
    }

    public Map<String, Object> list(String userId, String category, String search, String source, int page, int pageSize) throws IOException {
        String categoryFilter = category == null || category.isBlank() ? null : category;
        if (categoryFilter != null && !CATEGORIES.contains(categoryFilter)) throw new IllegalArgumentException("未知成果分类");
        int size = Math.max(1, Math.min(200, pageSize));
        int current = Math.max(1, page);
        String keyword = search == null ? "" : search.trim().toLowerCase(Locale.ROOT);
        boolean filterSource = source != null && !source.isBlank();
        long offset = (long) (current - 1) * size;
        long windowSize = offset + size;
        PriorityQueue<ArtifactMatch> window = new PriorityQueue<>(ARTIFACT_ORDER.reversed());
        int[] matched = {0};
        Path base = root(userId);
        walk(base, categoryFilter, (path, attrs) -> {
            if (!attrs.isRegularFile()) return;
            String relative = relative(base, path);
            if (categoryFilter != null && !categoryFilter.equals(category(relative))) return;
            boolean searchMetadata = !keyword.isEmpty() && !relative.toLowerCase(Locale.ROOT).contains(keyword);
            Map<String, Object> details = null;
            if (filterSource || searchMetadata) {
                details = metadata(base, relative);
                if (filterSource && !source.equals(Objects.toString(details.get("sourceModule"), "unrecorded"))) return;
                if (searchMetadata && !(relative + " " + details.values()).toLowerCase(Locale.ROOT).contains(keyword)) return;
            }
            retainMatch(window, windowSize, new ArtifactMatch(path, relative, attrs, details, matched[0]++));
        });
        List<Map<String, Object>> pageFiles = new ArrayList<>();
        if (matched[0] > offset) {
            window.stream().sorted(ARTIFACT_ORDER).skip(offset)
                    .map(match -> item(base, match.path(), match.relativePath(), match.attributes(), match.metadata()))
                    .forEachOrdered(pageFiles::add);
        }
        return Map.of("fileList", pageFiles, "count", matched[0],
                "page", current, "pageSize", size, "hasMore", matched[0] > windowSize);
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
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return Map.of();
            byte[] bytes;
            try (InputStream input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                bytes = input.readNBytes(MAX_METADATA_BYTES + 1);
            }
            if (bytes.length > MAX_METADATA_BYTES) return Map.of();
            String json = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
            Map<String, Object> result = mapper.readValue(json, Map.class);
            return result == null ? Map.of() : result;
        } catch (Exception ignored) { return Map.of(); }
    }

    private static Map<String, String> validateSource(Map<String, String> source) {
        Map<String, String> validated = new LinkedHashMap<>();
        for (String key : List.of("title", "sourceModule", "sourceId", "sessionId", "hostName")) {
            String value = source.get(key);
            if (value != null && !value.isBlank()) {
                if (value.length() > 512) throw new IllegalArgumentException("成果来源信息过长");
                validated.put(key, value.trim());
            }
        }
        String module = validated.getOrDefault("sourceModule", "manual");
        if (!SOURCES.contains(module)) throw new IllegalArgumentException("未知成果来源");
        validated.put("sourceModule", module);
        return validated;
    }

    private Map<String, Object> writeMetadata(Path base, Path target, Map<String, String> validatedSource) throws IOException {
        String relative = relative(base, target);
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("id", UUID.randomUUID().toString());
        record.put("createdAt", Instant.now().toString());
        record.put("category", category(relative));
        record.putAll(validatedSource);
        Path metadata = metadataPath(base, relative);
        Files.createDirectories(metadata.getParent());
        Path temporary = Files.createTempFile(metadata.getParent(), ".pending-", ".json");
        try {
            mapper.writeValue(temporary.toFile(), record);
            try { Files.move(temporary, metadata, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException ignored) { Files.move(temporary, metadata, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
        return record;
    }

    public Map<String, Object> upload(String userId, String relativeDir, String filename,
                                      boolean overwrite, InputStreamSource content) throws IOException {
        Path base = root(userId);
        Path directory = resolve(base, relativeDir, true);
        Files.createDirectories(directory);
        Path target = resolve(base, relative(base, directory.resolve(filename)), true);
        return writeFile(base, target, overwrite, content);
    }

    public Map<String, Object> createFile(String userId, String relativePath,
                                          String content, boolean overwrite) throws IOException {
        Path base = root(userId);
        Path target = resolve(base, relativePath, true);
        Path parent = target.getParent();
        if (parent != null) Files.createDirectories(parent);
        return writeFile(base, target, overwrite, () -> {
            byte[] data = content == null ? new byte[0] : content.getBytes(StandardCharsets.UTF_8);
            if (data.length > maxUploadBytes()) throw new IllegalArgumentException("成果内容超过归档上限");
            return new ByteArrayInputStream(data);
        });
    }

    /** Checks conflicts before opening the source and publishes overwrites only after transfer completes. */
    private Map<String, Object> writeFile(Path base, Path target, boolean overwrite,
                                           InputStreamSource content) throws IOException {
        if (Files.exists(target) && !overwrite) throw new FileAlreadyExistsException(target.toString());
        if (overwrite) {
            boolean replacingFile = Files.exists(target, LinkOption.NOFOLLOW_LINKS);
            if (replacingFile && (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) || !Files.isWritable(target))) {
                throw new IOException("目标文件不可覆盖");
            }
            Path temporary = Files.createTempFile(target.getParent(), ".pending-", ".tmp");
            try {
                transferContent(temporary, content, StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS);
                if (replacingFile) preservePosixPermissions(target, temporary);
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally { Files.deleteIfExists(temporary); }
        } else {
            transferContent(target, content, StandardOpenOption.CREATE_NEW);
        }
        writeMetadata(base, target, validateSource(Map.of("title", target.getFileName().toString(), "sourceModule", "manual")));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", relative(base, target));
        result.put("size", Files.size(target));
        return result;
    }

    private static void preservePosixPermissions(Path existing, Path replacement) throws IOException {
        var view = Files.getFileAttributeView(existing, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (view == null) return;
        var attributes = view.readAttributes();
        if (!attributes.isRegularFile()) throw new IOException("目标文件不可覆盖");
        Files.setPosixFilePermissions(replacement, attributes.permissions());
    }

    private static void transferContent(Path target, InputStreamSource content, OpenOption... options) throws IOException {
        try (InputStream input = content.getInputStream(); var output = Files.newOutputStream(target, options)) {
            input.transferTo(output);
        }
    }

    public Map<String, Object> createDirectory(String userId, String relativePath) throws IOException {
        Path base = root(userId);
        Path target = resolve(base, relativePath, true);
        if (Files.exists(target) && !Files.isDirectory(target)) throw new FileAlreadyExistsException(target.toString());
        Files.createDirectories(target);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", relative(base, target));
        return result;
    }

    /** Deletes an artifact and its metadata; missing paths return null. */
    public Map<String, Object> delete(String userId, String relativePath, boolean recursive) throws IOException {
        Path base = root(userId);
        Path target = resolve(base, relativePath, true);
        if (target.equals(base)) throw new IllegalArgumentException("请选择具体成果");
        if (!Files.exists(target)) return null;
        if (Files.isDirectory(target)) {
            if (!recursive) throw new IllegalArgumentException("目录删除需要设置recursive=true");
            Files.walkFileTree(target, new SimpleFileVisitor<>() {
                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    deletePath(base, file);
                    return FileVisitResult.CONTINUE;
                }

                @Override public FileVisitResult postVisitDirectory(Path dir, IOException error) throws IOException {
                    if (error != null) throw error;
                    deletePath(base, dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } else {
            deletePath(base, target);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", relative(base, target));
        result.put("deleted", true);
        result.put("recursive", recursive);
        return result;
    }

    private void deletePath(Path base, Path target) throws IOException {
        Files.deleteIfExists(target);
        Files.deleteIfExists(metadataPath(base, relative(base, target)));
    }

    public Map<String, Object> archive(String userId, ArchiveRequest request) throws IOException {
        if (request == null || !CATEGORIES.contains(request.category())) throw new IllegalArgumentException("未知成果分类");
        String name = request.filename();
        if (name == null || name.isBlank() || name.length() > 200 || name.startsWith(".")
                || name.contains("/") || name.contains("\\")) throw new IllegalArgumentException("无效成果文件名");
        Path base = root(userId);
        Path directory = resolve(base, request.category(), true);
        Path target = directory.resolve(UUID.randomUUID().toString().substring(0, 8) + "-" + name);
        Map<String, String> source = new LinkedHashMap<>(request.metadata() == null ? Map.of() : request.metadata());
        long maxBytes = maxUploadBytes();
        Path input = null;
        byte[] content = null;
        FileAttribute<?>[] creationAttributes = {};
        if (request.sourcePath() != null && !request.sourcePath().isBlank()) {
            if (!"task-results".equals(request.category())) throw new IllegalArgumentException("任务文件应归档到任务输出");
            input = resolve(base, request.sourcePath(), false);
            Path relative = base.relativize(input);
            BasicFileAttributes attributes = null;
            if (relative.getNameCount() >= 4 && "ai-tasks".equals(relative.getName(0).toString())
                    && "files".equals(relative.getName(2).toString())) {
                try {
                    var attributeType = Files.getFileAttributeView(input, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS) != null
                            ? PosixFileAttributes.class : BasicFileAttributes.class;
                    attributes = Files.readAttributes(input, attributeType, LinkOption.NOFOLLOW_LINKS);
                } catch (IOException ignored) {
                    // A source whose type cannot be determined is not eligible for archive.
                }
            }
            if (attributes == null || !attributes.isRegularFile())
                throw new IllegalArgumentException("请选择任务公开输出文件");
            if (attributes.size() > maxBytes) throw new IllegalArgumentException("成果文件超过归档上限");
            if (attributes instanceof PosixFileAttributes posix) {
                creationAttributes = new FileAttribute<?>[]{PosixFilePermissions.asFileAttribute(posix.permissions())};
            }
            source.put("sourceModule", "task-output");
            source.put("sourceId", relative.getName(1).toString());
        } else {
            if (request.content() == null || request.content().isBlank()) throw new IllegalArgumentException("成果内容为空");
            content = request.content().getBytes(StandardCharsets.UTF_8);
            if (content.length > maxBytes) throw new IllegalArgumentException("成果内容超过归档上限");
        }
        var validatedSource = validateSource(source);
        Files.createDirectories(directory);
        boolean created = false;
        try {
            try (var channel = Files.newByteChannel(target, Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
                    creationAttributes)) {
                created = true;
                OutputStream output = Channels.newOutputStream(channel);
                if (input != null) copyArchiveSource(input, output, maxBytes);
                else output.write(content);
            }
            var metadata = writeMetadata(base, target, validatedSource);
            return item(base, target, relative(base, target), Files.readAttributes(target, BasicFileAttributes.class), metadata);
        } catch (Exception error) {
            if (created) deletePath(base, target);
            throw error;
        }
    }

    private static void copyArchiveSource(Path source, OutputStream output, long maxBytes) throws IOException {
        try (var input = Files.newInputStream(source, LinkOption.NOFOLLOW_LINKS)) {
            byte[] buffer = new byte[8192];
            long remaining = maxBytes;
            while (remaining > 0) {
                int read = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (read == -1) return;
                output.write(buffer, 0, read);
                remaining -= read;
            }
            if (input.read() != -1) throw new IllegalArgumentException("成果文件超过归档上限");
        }
    }

    public record ArchiveRequest(String category, String filename, String content, String sourcePath, Map<String, String> metadata) { }
}
