package org.leo.web.controller.platform.user;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.leo.core.entity.User;
import org.leo.core.util.ApiResponse;
import org.leo.core.util.session.AgentNodeSessionWorkDirUtil;
import org.leo.web.service.UserArtifactService;
import org.leo.web.util.FileDownloadResponse;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
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
    private final UserArtifactService artifacts;

    public UserFileController(UserArtifactService artifacts) {
        this.artifacts = artifacts;
    }

    @RequestMapping(value = "/overview", method = RequestMethod.GET)
    public HashMap<String, Object> overview(HttpServletRequest request) {
        try {
            User user = getUserFromSession(request);
            if (user == null) {
                return ApiResponse.unauthorized("用户未登录");
            }

            HashMap<String, Object> data = new HashMap<>(artifacts.overview(user.getUserId()));
            data.put("rootPath", "");
            data.put("maxUploadBytes", artifacts.maxUploadBytes());
            return ApiResponse.success(data);
        } catch (Exception e) {
            return ApiResponse.error("获取用户空间概览失败: " + e.getMessage());
        }
    }

    @RequestMapping(value = "/list", method = RequestMethod.GET)
    public HashMap<String, Object> list(HttpServletRequest request,
                                        @RequestParam(value = "path", required = false) String relativePath,
                                        @RequestParam(defaultValue = "false") boolean recursive,
                                        @RequestParam(defaultValue = "1") int page,
                                        @RequestParam(defaultValue = "50") int pageSize,
                                        @RequestParam(required = false) String search,
                                        @RequestParam(required = false) String source) {
        try {
            User user = getUserFromSession(request);
            if (user == null) {
                return ApiResponse.unauthorized("用户未登录");
            }
            if (recursive) return ApiResponse.success(artifacts.list(user.getUserId(), relativePath, search, source, page, pageSize));
            Path target = artifacts.resolve(user.getUserId(), relativePath, true);
            if (!Files.exists(target)) {
                return ApiResponse.notFound("目录不存在");
            }
            if (!Files.isDirectory(target)) {
                return ApiResponse.badRequest("path不是目录");
            }

            List<HashMap<String, Object>> fileList = new ArrayList<>();
            try (var stream = Files.list(target)) {
                stream.filter(path -> !path.getFileName().toString().startsWith(".")
                                && !"ai-tasks".equals(path.getFileName().toString()) && !Files.isSymbolicLink(path))
                        .sorted(Comparator.comparing(p -> p.getFileName().toString().toLowerCase()))
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

    @RequestMapping(value = "/archive", method = RequestMethod.POST)
    public HashMap<String, Object> archive(HttpServletRequest request, @RequestBody UserArtifactService.ArchiveRequest body) {
        User user = getUserFromSession(request);
        if (user == null) return ApiResponse.unauthorized("用户未登录");
        try { return ApiResponse.success(artifacts.archive(user.getUserId(), body)); }
        catch (IllegalArgumentException error) { return ApiResponse.badRequest(error.getMessage()); }
        catch (Exception error) { return ApiResponse.error("归档失败: " + error.getMessage()); }
    }

    @RequestMapping(value = "/upload", method = RequestMethod.POST)
    public HashMap<String, Object> upload(HttpServletRequest request,
                                          @RequestPart("file") MultipartFile file,
                                          @RequestParam(value = "path", required = false) String relativeDir,
                                          @RequestParam(value = "filename", required = false) String filename,
                                          @RequestParam(value = "overwrite", required = false) Boolean overwrite) {
        try {
            User user = getUserFromSession(request);
            if (user == null) {
                return ApiResponse.unauthorized("用户未登录");
            }
            if (file == null || file.isEmpty()) {
                return ApiResponse.badRequest("上传文件不能为空");
            }
            long maxUploadBytes = artifacts.maxUploadBytes();
            if (file.getSize() > maxUploadBytes) {
                return ApiResponse.badRequest("文件超过单文件上传上限（" + (maxUploadBytes / 1024 / 1024) + " MB）");
            }

            String uploadName = sanitizeFileName(filename);
            if (uploadName == null || uploadName.isEmpty()) {
                uploadName = sanitizeFileName(file.getOriginalFilename());
            }
            if (uploadName == null || uploadName.isEmpty()) {
                return ApiResponse.badRequest("文件名不能为空");
            }

            Path dir = artifacts.resolve(user.getUserId(), relativeDir, true);
            Files.createDirectories(dir);
            Path target = artifacts.resolve(user.getUserId(), toUserRelativePath(user.getUserId(), dir.resolve(uploadName)), true);
            if (!target.startsWith(getUserWorkspacePath(user.getUserId()))) {
                return ApiResponse.forbidden("非法路径");
            }
            boolean overwriteExisting = Boolean.TRUE.equals(overwrite);
            if (Files.exists(target) && !overwriteExisting) {
                return ApiResponse.conflict("同名文件已存在，请确认后覆盖上传");
            }
            StandardOpenOption[] writeOptions = overwriteExisting
                    ? new StandardOpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING}
                    : new StandardOpenOption[]{StandardOpenOption.CREATE_NEW};
            try (InputStream input = file.getInputStream();
                 OutputStream output = Files.newOutputStream(target, writeOptions)) {
                input.transferTo(output);
            }
            artifacts.recordManual(user.getUserId(), target);

            HashMap<String, Object> data = new HashMap<>();
            data.put("path", toUserRelativePath(user.getUserId(), target));
            data.put("size", Files.size(target));
            return ApiResponse.success("上传成功", data);
        } catch (FileAlreadyExistsException e) {
            return ApiResponse.conflict("同名文件已存在，请确认后覆盖上传");
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
            FileDownloadResponse.write(target, downloadName, response);
        } catch (IllegalArgumentException e) {
            response.setStatus(400);
        } catch (Exception e) {
            response.setStatus(500);
        }
    }

    @RequestMapping(value = "/create-file", method = RequestMethod.POST)
    public HashMap<String, Object> createFile(HttpServletRequest request,
                                              @RequestParam("path") String relativePath,
                                              @RequestParam(value = "content", required = false) String content,
                                              @RequestParam(value = "overwrite", required = false) Boolean overwrite) {
        try {
            User user = getUserFromSession(request);
            if (user == null) {
                return ApiResponse.unauthorized("用户未登录");
            }
            Path target = artifacts.resolve(user.getUserId(), relativePath, true);
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            boolean overwriteExisting = Boolean.TRUE.equals(overwrite);
            if (Files.exists(target) && !overwriteExisting) {
                return ApiResponse.conflict("同名文件已存在，请确认后覆盖创建");
            }
            byte[] data = content == null ? new byte[0] : content.getBytes(StandardCharsets.UTF_8);
            if (data.length > artifacts.maxUploadBytes()) return ApiResponse.badRequest("成果内容超过归档上限");
            if (overwriteExisting) {
                Files.write(target, data, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            } else {
                Files.write(target, data, StandardOpenOption.CREATE_NEW);
            }
            artifacts.recordManual(user.getUserId(), target);

            HashMap<String, Object> result = new HashMap<>();
            result.put("path", toUserRelativePath(user.getUserId(), target));
            result.put("size", Files.size(target));
            return ApiResponse.success("文件创建成功", result);
        } catch (FileAlreadyExistsException e) {
            return ApiResponse.conflict("同名文件已存在，请确认后覆盖创建");
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
            Path target = artifacts.resolve(user.getUserId(), relativePath, true);
            if (Files.exists(target) && !Files.isDirectory(target)) {
                return ApiResponse.conflict("同名文件已存在，无法创建目录");
            }
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
                n = Math.max(0, in.read(bytes));
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
            Path target = artifacts.resolve(user.getUserId(), relativePath, true);
            if (target.equals(getUserWorkspacePath(user.getUserId()))) return ApiResponse.badRequest("请选择具体成果");
            if (!Files.exists(target)) {
                return ApiResponse.notFound("文件或目录不存在");
            }
            boolean recursiveDelete = Boolean.TRUE.equals(recursive);
            if (Files.isDirectory(target) && !recursiveDelete) {
                return ApiResponse.badRequest("目录删除需要设置recursive=true");
            }

            if (Files.isDirectory(target)) {
                deleteRecursively(user.getUserId(), target);
            } else {
                Files.deleteIfExists(target);
                artifacts.removeMetadata(user.getUserId(), target);
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

    private Path getUserWorkspacePath(String userId) {
        return AgentNodeSessionWorkDirUtil.getUserWorkspaceDir(userId)
                .toPath()
                .toAbsolutePath()
                .normalize();
    }

    private Path resolveUserPath(String userId, String relativePath, boolean asDirectory) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("用户信息无效");
        }
        Path resolved = artifacts.resolve(userId, relativePath, false);
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

    private Path getParentPath(String userId, Path path) {
        Path parent = path.getParent();
        return parent == null ? getUserWorkspacePath(userId) : parent;
    }

    private String toUserRelativePath(String userId, Path path) {
        Path base = getUserWorkspacePath(userId);
        Path p = path.toAbsolutePath().normalize();
        if (!p.startsWith(base)) {
            return "";
        }
        return base.relativize(p).toString().replace(File.separatorChar, '/');
    }

    private void deleteRecursively(String userId, Path rootPath) throws Exception {
        try (Stream<Path> walk = Files.walk(rootPath)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                    artifacts.removeMetadata(userId, path);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }

}
