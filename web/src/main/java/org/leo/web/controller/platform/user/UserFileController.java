package org.leo.web.controller.platform.user;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.leo.core.entity.User;
import org.leo.core.util.ApiResponse;
import org.leo.core.util.session.PuppetNodeSessionWorkDirUtil;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.stream.Stream;

@RestController
@RequestMapping("/platform/user/file")
public class UserFileController {
    private static final long DEFAULT_PREVIEW_SIZE = 64 * 1024L;
    private static final long MAX_PREVIEW_SIZE = 1024 * 1024L;
    private static final int RECENT_FILE_LIMIT = 8;
    private static final int TOP_DIRECTORY_LIMIT = 6;

    @RequestMapping(value = "/overview", method = RequestMethod.GET)
    public HashMap<String, Object> overview(HttpServletRequest request) {
        try {
            User user = getUserFromSession(request);
            if (user == null) {
                return ApiResponse.unauthorized("用户未登录");
            }

            Path base = getUserBasePath(user.getUserId());
            Files.createDirectories(base);

            WorkspaceOverviewStats stats = collectWorkspaceOverview(base);

            List<HashMap<String, Object>> recentFiles = stats.files.stream()
                    .sorted(Comparator.comparingLong(this::getLastModified).reversed())
                    .limit(RECENT_FILE_LIMIT)
                    .map(path -> toItem(user.getUserId(), path))
                    .toList();

            List<HashMap<String, Object>> topDirectories = stats.directories.stream()
                    .sorted(Comparator.comparingLong(this::getLastModified).reversed())
                    .limit(TOP_DIRECTORY_LIMIT)
                    .map(path -> toDirectorySummary(user.getUserId(), path))
                    .toList();

            HashMap<String, Object> data = new HashMap<>();
            data.put("rootPath", "");
            data.put("totalFiles", stats.totalFiles);
            data.put("totalDirectories", stats.totalDirectories);
            data.put("totalBytes", stats.totalBytes);
            data.put("rootItems", countDirectChildren(base));
            data.put("updatedAt", stats.updatedAt);
            data.put("recentFiles", recentFiles);
            data.put("topDirectories", topDirectories);
            return ApiResponse.success(data);
        } catch (Exception e) {
            return ApiResponse.error("获取用户空间概览失败: " + e.getMessage());
        }
    }

    @RequestMapping(value = "/list", method = RequestMethod.GET)
    public HashMap<String, Object> list(HttpServletRequest request,
                                        @RequestParam(value = "path", required = false) String relativePath) {
        try {
            User user = getUserFromSession(request);
            if (user == null) {
                return ApiResponse.unauthorized("用户未登录");
            }
            Path target = resolveUserPath(user.getUserId(), relativePath, true);
            if (!Files.exists(target)) {
                return ApiResponse.notFound("目录不存在");
            }
            if (!Files.isDirectory(target)) {
                return ApiResponse.badRequest("path不是目录");
            }

            List<HashMap<String, Object>> fileList = new ArrayList<>();
            try (var stream = Files.list(target)) {
                stream.sorted(Comparator.comparing(p -> p.getFileName().toString().toLowerCase()))
                        .forEach(path -> fileList.add(toItem(user.getUserId(), path)));
            }
            HashMap<String, Object> data = new HashMap<>();
            data.put("path", toUserRelativePath(user.getUserId(), target));
            data.put("count", fileList.size());
            data.put("fileList", fileList);
            return ApiResponse.success(data);
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        } catch (Exception e) {
            return ApiResponse.error("获取文件列表失败: " + e.getMessage());
        }
    }

    @RequestMapping(value = "/upload", method = RequestMethod.POST)
    public HashMap<String, Object> upload(HttpServletRequest request,
                                          @RequestPart("file") MultipartFile file,
                                          @RequestParam(value = "path", required = false) String relativeDir,
                                          @RequestParam(value = "filename", required = false) String filename) {
        try {
            User user = getUserFromSession(request);
            if (user == null) {
                return ApiResponse.unauthorized("用户未登录");
            }
            if (file == null || file.isEmpty()) {
                return ApiResponse.badRequest("上传文件不能为空");
            }

            String uploadName = sanitizeFileName(filename);
            if (uploadName == null || uploadName.isEmpty()) {
                uploadName = sanitizeFileName(file.getOriginalFilename());
            }
            if (uploadName == null || uploadName.isEmpty()) {
                return ApiResponse.badRequest("文件名不能为空");
            }

            Path dir = resolveUserPath(user.getUserId(), relativeDir, true);
            Files.createDirectories(dir);
            Path target = dir.resolve(uploadName).normalize();
            if (!target.startsWith(getUserBasePath(user.getUserId()))) {
                return ApiResponse.forbidden("非法路径");
            }
            Files.write(target, file.getBytes(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

            HashMap<String, Object> data = new HashMap<>();
            data.put("path", toUserRelativePath(user.getUserId(), target));
            data.put("size", Files.size(target));
            return ApiResponse.success("上传成功", data);
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        } catch (Exception e) {
            return ApiResponse.error("上传文件失败: " + e.getMessage());
        }
    }

    @RequestMapping(value = "/download", method = RequestMethod.GET)
    public void download(HttpServletRequest request,
                         @RequestParam("path") String relativePath,
                         @RequestParam(value = "filename", required = false) String filename,
                         HttpServletResponse response) {
        try {
            User user = getUserFromSession(request);
            if (user == null) {
                response.setStatus(401);
                return;
            }
            Path target = resolveUserPath(user.getUserId(), relativePath, false);
            if (!Files.exists(target) || !Files.isRegularFile(target)) {
                response.setStatus(404);
                return;
            }
            String downloadName = sanitizeFileName(filename);
            if (downloadName == null || downloadName.isEmpty()) {
                downloadName = target.getFileName().toString();
            }
            String encoded = URLEncoder.encode(downloadName, StandardCharsets.UTF_8.name()).replaceAll("\\+", "%20");

            response.setStatus(200);
            response.setContentType("application/octet-stream");
            response.setHeader("Content-Disposition", "attachment; filename*=UTF-8''" + encoded);
            response.setHeader("Content-Length", String.valueOf(Files.size(target)));

            try (InputStream in = Files.newInputStream(target);
                 ServletOutputStream out = response.getOutputStream()) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) >= 0) {
                    out.write(buf, 0, n);
                }
                out.flush();
            }
        } catch (IllegalArgumentException e) {
            response.setStatus(400);
        } catch (Exception e) {
            response.setStatus(500);
        }
    }

    @RequestMapping(value = "/create-file", method = RequestMethod.POST)
    public HashMap<String, Object> createFile(HttpServletRequest request,
                                              @RequestParam("path") String relativePath,
                                              @RequestParam(value = "content", required = false) String content) {
        try {
            User user = getUserFromSession(request);
            if (user == null) {
                return ApiResponse.unauthorized("用户未登录");
            }
            Path target = resolveUserPath(user.getUserId(), relativePath, false);
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            byte[] data = content == null ? new byte[0] : content.getBytes(StandardCharsets.UTF_8);
            Files.write(target, data, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

            HashMap<String, Object> result = new HashMap<>();
            result.put("path", toUserRelativePath(user.getUserId(), target));
            result.put("size", Files.size(target));
            return ApiResponse.success("文件创建成功", result);
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        } catch (Exception e) {
            return ApiResponse.error("创建文件失败: " + e.getMessage());
        }
    }

    @RequestMapping(value = "/create-dir", method = RequestMethod.POST)
    public HashMap<String, Object> createDir(HttpServletRequest request,
                                             @RequestParam("path") String relativePath) {
        try {
            User user = getUserFromSession(request);
            if (user == null) {
                return ApiResponse.unauthorized("用户未登录");
            }
            Path target = resolveUserPath(user.getUserId(), relativePath, true);
            Files.createDirectories(target);

            HashMap<String, Object> result = new HashMap<>();
            result.put("path", toUserRelativePath(user.getUserId(), target));
            return ApiResponse.success("目录创建成功", result);
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        } catch (Exception e) {
            return ApiResponse.error("创建目录失败: " + e.getMessage());
        }
    }

    @RequestMapping(value = "/preview", method = RequestMethod.GET)
    public HashMap<String, Object> preview(HttpServletRequest request,
                                           @RequestParam("path") String relativePath,
                                           @RequestParam(value = "size", required = false) Long size) {
        try {
            User user = getUserFromSession(request);
            if (user == null) {
                return ApiResponse.unauthorized("用户未登录");
            }
            Path target = resolveUserPath(user.getUserId(), relativePath, false);
            if (!Files.exists(target) || !Files.isRegularFile(target)) {
                return ApiResponse.notFound("文件不存在");
            }
            long previewSize = size == null ? DEFAULT_PREVIEW_SIZE : Math.min(Math.max(size, 1L), MAX_PREVIEW_SIZE);
            long fileSize = Files.size(target);
            int readSize = (int) Math.min(previewSize, fileSize);
            byte[] bytes = new byte[readSize];
            int n;
            try (InputStream in = Files.newInputStream(target)) {
                n = in.read(bytes);
            }
            if (n < 0) {
                n = 0;
            }
            byte[] actual = n == bytes.length ? bytes : java.util.Arrays.copyOf(bytes, n);
            String base64 = Base64.getEncoder().encodeToString(actual);
            String text = new String(actual, StandardCharsets.UTF_8);

            HashMap<String, Object> result = new HashMap<>();
            result.put("path", toUserRelativePath(user.getUserId(), target));
            result.put("size", fileSize);
            result.put("previewSize", n);
            result.put("truncated", fileSize > n);
            result.put("content", text);
            result.put("contentBase64", base64);
            return ApiResponse.success(result);
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        } catch (Exception e) {
            return ApiResponse.error("预览文件失败: " + e.getMessage());
        }
    }

    @RequestMapping(value = "/delete", method = RequestMethod.POST)
    public HashMap<String, Object> delete(HttpServletRequest request,
                                          @RequestParam("path") String relativePath,
                                          @RequestParam(value = "recursive", required = false) Boolean recursive) {
        try {
            User user = getUserFromSession(request);
            if (user == null) {
                return ApiResponse.unauthorized("用户未登录");
            }
            Path target = resolveUserPath(user.getUserId(), relativePath, false);
            if (!Files.exists(target)) {
                return ApiResponse.notFound("文件或目录不存在");
            }
            boolean recursiveDelete = Boolean.TRUE.equals(recursive);
            if (Files.isDirectory(target) && !recursiveDelete) {
                return ApiResponse.badRequest("目录删除需要设置recursive=true");
            }

            if (Files.isDirectory(target)) {
                deleteRecursively(target);
            } else {
                Files.deleteIfExists(target);
            }

            HashMap<String, Object> result = new HashMap<>();
            result.put("path", toUserRelativePath(user.getUserId(), target));
            result.put("deleted", true);
            result.put("recursive", recursiveDelete);
            return ApiResponse.success("删除成功", result);
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        } catch (Exception e) {
            return ApiResponse.error("删除失败: " + e.getMessage());
        }
    }

    private User getUserFromSession(HttpServletRequest request) {
        return (User) request.getSession().getAttribute("user");
    }

    private Path getUserBasePath(String userId) {
        File root = PuppetNodeSessionWorkDirUtil.getRootDir();
        return new File(new File(root, "users"), userId).toPath().toAbsolutePath().normalize();
    }

    private Path resolveUserPath(String userId, String relativePath, boolean asDirectory) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("用户信息无效");
        }
        Path base = getUserBasePath(userId);
        Path resolved = base;
        if (relativePath != null && !relativePath.isBlank()) {
            String normalized = relativePath.trim().replace('\\', '/');
            if (normalized.startsWith("/")) {
                normalized = normalized.substring(1);
            }
            resolved = base.resolve(Paths.get(normalized)).normalize();
        }
        if (!resolved.startsWith(base)) {
            throw new IllegalArgumentException("非法路径");
        }
        if (asDirectory && Files.exists(resolved) && !Files.isDirectory(resolved)) {
            throw new IllegalArgumentException("目标路径不是目录");
        }
        return resolved;
    }

    private String sanitizeFileName(String fileName) {
        if (fileName == null) {
            return null;
        }
        String safe = new File(fileName).getName();
        if (safe.contains("..") || safe.contains("/") || safe.contains("\\") || !safe.equals(fileName)) {
            throw new IllegalArgumentException("文件名包含非法字符");
        }
        return safe.trim();
    }

    private HashMap<String, Object> toItem(String userId, Path path) {
        HashMap<String, Object> item = new HashMap<>();
        item.put("name", path.getFileName().toString());
        item.put("path", toUserRelativePath(userId, path));
        item.put("parentPath", toUserRelativePath(userId, getParentPath(userId, path)));
        item.put("isDirectory", Files.isDirectory(path));
        try {
            item.put("size", Files.isDirectory(path) ? 0L : Files.size(path));
            item.put("lastModified", Files.getLastModifiedTime(path).toMillis());
        } catch (Exception e) {
            item.put("size", 0L);
            item.put("lastModified", 0L);
        }
        return item;
    }

    private WorkspaceOverviewStats collectWorkspaceOverview(Path base) throws Exception {
        WorkspaceOverviewStats stats = new WorkspaceOverviewStats();
        try (Stream<Path> walk = Files.walk(base)) {
            walk.forEach(path -> collectWorkspacePath(base, path, stats));
        }
        return stats;
    }

    private void collectWorkspacePath(Path base, Path path, WorkspaceOverviewStats stats) {
        stats.updatedAt = Math.max(stats.updatedAt, getLastModified(path));
        if (path.equals(base)) {
            return;
        }
        if (Files.isDirectory(path)) {
            stats.totalDirectories++;
            stats.directories.add(path);
            return;
        }
        if (Files.isRegularFile(path)) {
            stats.totalFiles++;
            stats.totalBytes += safeSize(path);
            stats.files.add(path);
        }
    }

    private HashMap<String, Object> toDirectorySummary(String userId, Path path) {
        HashMap<String, Object> item = toItem(userId, path);
        DirectorySummaryStats stats = new DirectorySummaryStats();
        try (Stream<Path> walk = Files.walk(path)) {
            walk.forEach(child -> {
                if (child.equals(path)) {
                    return;
                }
                if (Files.isDirectory(child)) {
                    stats.directories++;
                } else if (Files.isRegularFile(child)) {
                    stats.files++;
                    stats.bytes += safeSize(child);
                }
            });
        } catch (Exception ignored) {
            // Keep workspace overview resilient when one directory cannot be read.
        }
        item.put("files", stats.files);
        item.put("directories", stats.directories);
        item.put("bytes", stats.bytes);
        return item;
    }

    private Path getParentPath(String userId, Path path) {
        Path parent = path.getParent();
        return parent == null ? getUserBasePath(userId) : parent;
    }

    private long countDirectChildren(Path path) {
        try (Stream<Path> children = Files.list(path)) {
            return children.count();
        } catch (Exception e) {
            return 0L;
        }
    }

    private long safeSize(Path path) {
        try {
            return Files.size(path);
        } catch (Exception e) {
            return 0L;
        }
    }

    private long getLastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (Exception e) {
            return 0L;
        }
    }

    private String toUserRelativePath(String userId, Path path) {
        Path base = getUserBasePath(userId);
        Path p = path.toAbsolutePath().normalize();
        if (!p.startsWith(base)) {
            return "";
        }
        String rel = base.relativize(p).toString().replace(File.separatorChar, '/');
        return rel;
    }

    private void deleteRecursively(Path rootPath) throws Exception {
        try (Stream<Path> walk = Files.walk(rootPath)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }

    private static class WorkspaceOverviewStats {
        private long totalFiles;
        private long totalDirectories;
        private long totalBytes;
        private long updatedAt;
        private final List<Path> files = new ArrayList<>();
        private final List<Path> directories = new ArrayList<>();
    }

    private static class DirectorySummaryStats {
        private long files;
        private long directories;
        private long bytes;
    }
}
