package org.leo.web.controller.platform.user;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.leo.core.entity.User;
import org.leo.core.util.ApiResponse;
import org.leo.web.application.artifact.UserArtifactService;
import org.leo.web.util.ControllerUtil;
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
import java.util.HashMap;

@RestController
@RequestMapping("/platform/user/file")
public class UserFileController {
    private final UserArtifactService artifacts;

    public UserFileController(UserArtifactService artifacts) {
        this.artifacts = artifacts;
    }

    @RequestMapping(value = "/overview", method = RequestMethod.GET)
    public HashMap<String, Object> overview(HttpServletRequest request) {
        try {
            User user = ControllerUtil.getCurrentUser(request);
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
            User user = ControllerUtil.getCurrentUser(request);
            if (user == null) {
                return ApiResponse.unauthorized("用户未登录");
            }
            if (recursive) return ApiResponse.success(artifacts.list(user.getUserId(), relativePath, search, source, page, pageSize));
            var directory = artifacts.listDirectory(user.getUserId(), relativePath);
            return directory == null ? ApiResponse.notFound("目录不存在") : ApiResponse.success(directory);
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        } catch (Exception e) {
            return ApiResponse.error("获取文件列表失败: " + e.getMessage());
        }
    }

    @RequestMapping(value = "/archive", method = RequestMethod.POST)
    public HashMap<String, Object> archive(HttpServletRequest request, @RequestBody UserArtifactService.ArchiveRequest body) {
        User user = ControllerUtil.getCurrentUser(request);
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
            User user = ControllerUtil.getCurrentUser(request);
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
            if (!target.startsWith(artifacts.root(user.getUserId()))) {
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
            User user = ControllerUtil.getCurrentUser(request);
            if (user == null) {
                response.setStatus(401);
                return;
            }
            Path target = artifacts.findFile(user.getUserId(), relativePath);
            if (target == null) {
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
            User user = ControllerUtil.getCurrentUser(request);
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
            User user = ControllerUtil.getCurrentUser(request);
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
            User user = ControllerUtil.getCurrentUser(request);
            if (user == null) {
                return ApiResponse.unauthorized("用户未登录");
            }
            var preview = artifacts.preview(user.getUserId(), relativePath, size);
            return preview == null ? ApiResponse.notFound("文件不存在") : ApiResponse.success(preview);
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
            User user = ControllerUtil.getCurrentUser(request);
            if (user == null) {
                return ApiResponse.unauthorized("用户未登录");
            }
            var deleted = artifacts.delete(user.getUserId(), relativePath, Boolean.TRUE.equals(recursive));
            return deleted == null ? ApiResponse.notFound("文件或目录不存在") : ApiResponse.success("删除成功", deleted);
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        } catch (Exception e) {
            return ApiResponse.error("删除失败: " + e.getMessage());
        }
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

    private String toUserRelativePath(String userId, Path path) {
        return toUserRelativePath(artifacts.root(userId), path);
    }

    private String toUserRelativePath(Path base, Path path) {
        Path p = path.toAbsolutePath().normalize();
        if (!p.startsWith(base)) {
            return "";
        }
        return base.relativize(p).toString().replace(File.separatorChar, '/');
    }

}
