package org.leo.web.controller.puppetnode.file;


import org.leo.core.puppet.AbstractPuppetNode;
import org.leo.core.puppet.capability.FileCapable;
import org.leo.core.util.ApiResponse;
import org.leo.web.exception.ApiException;
import org.leo.web.util.AuditLogUtil;
import org.leo.web.util.ControllerUtil;
import org.leo.core.util.session.PuppetNodeSessionWorkDirUtil;
import org.leo.core.repository.session.PuppetSessionFileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.File;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.leo.core.entity.User;

@RestController
@RequestMapping("/puppet-node/file")
public class PuppetNodeFileController {
    private static final Logger logger = LoggerFactory.getLogger(PuppetNodeFileController.class);
    private final PuppetSessionFileRepository fileRepository;

    public PuppetNodeFileController(PuppetSessionFileRepository fileRepository) {
        this.fileRepository = fileRepository;
    }


    // 文件预览大小（1MB）
    private static final long PREVIEW_SIZE = 1024 * 1024L;
    private static final Set<Integer> SUCCESS_CODES = Set.of(200);
    private static final Set<Integer> CHUNK_SUCCESS_CODES = Set.of(100, 200);

    /**
     * 获取目标节点文件系统能力。前端以此决定路径分隔符、根目录和大小写语义。
     */
    @RequestMapping(value = "/profile", method = RequestMethod.POST)
    public HashMap<String, Object> fileSystemProfile(@RequestBody HashMap<String, Object> params) {
        FileCapable node = getFileNode(params);
        Map<String, Object> results = puppetCall(node::getFileSystemProfile, "获取文件系统能力失败");
        return ApiResponse.success(results);
    }


    /**
     * 获取文件列表（成功时将列表结果结构化存储到 root/sessions/{sessionId}/file-lists）
     */
    @RequestMapping(value = "/list", method = RequestMethod.POST)
    public HashMap<String, Object> listFiles(@RequestBody HashMap<String, Object> params) {
        String path = ControllerUtil.getRequiredStringParam(params, "path");
        FileCapable node = getFileNode(params);
        Map<String, Object> results = puppetCall(() -> node.getFileList(path), "获取文件列表失败");
        if (results != null) {
            String sessionId = (String) params.get("sessionId");
            if (sessionId != null && !sessionId.isBlank()) {
                try {
                    fileRepository.saveFileList(sessionId, path, results);
                } catch (Exception ex) {
                    logger.warn("写入会话文件列表失败, sessionId={}, path={}: {}", sessionId, path, ex.getMessage());
                }
            }
        }
        return ApiResponse.success(results);
    }

    /**
     * 下载服务器端落盘在用户目录内的文件（供前端直接下载）。
     *
     * 约束：
     * - 只允许访问 root/users/{userId}/ 下的文件（防止路径穿越）
     *
     * 参数（Query）：
     * - path: 相对于 root/users/{userId}/ 的相对路径，例如：
     *   - downloads/{taskId}/xxx.log
     * - filename: 可选，下载时展示的文件名（不传则使用本地文件名）
     */
    @RequestMapping(value = "/download-local", method = RequestMethod.GET)
    public void downloadLocalFile(HttpServletRequest request, String path, String filename, HttpServletResponse response) {
        try {
            User user = (User) request.getSession().getAttribute("user");
            if (user == null) {
                response.setStatus(401);
                return;
            }
            if (path == null || path.isBlank()) {
                response.setStatus(400);
                return;
            }

            File root = PuppetNodeSessionWorkDirUtil.getRootDir();
            Path base = new File(new File(root, "users"), user.getUserId()).toPath().toAbsolutePath().normalize();
            Path resolved = base.resolve(Paths.get(path.trim())).normalize();
            if (!resolved.startsWith(base)) {
                response.setStatus(403);
                return;
            }
            File f = resolved.toFile();
            if (!f.exists() || !f.isFile()) {
                response.setStatus(404);
                return;
            }

            String downloadName = (filename != null && !filename.isBlank()) ? filename.trim() : f.getName();
            String encoded = URLEncoder.encode(downloadName, StandardCharsets.UTF_8.name()).replaceAll("\\+", "%20");

            response.setStatus(200);
            response.setContentType("application/octet-stream");
            response.setHeader("Content-Disposition", "attachment; filename*=UTF-8''" + encoded);
            response.setHeader("Content-Length", String.valueOf(f.length()));

            Map<String, Object> auditParams = new HashMap<>();
            auditParams.put("path", path);
            auditParams.put("filename", downloadName);
            auditParams.put("size", f.length());
            AuditLogUtil.logSystemOperation(user, "FILE_DOWNLOAD_LOCAL", "下载本地文件", path, auditParams,
                    ApiResponse.CODE_SUCCESS, "下载本地文件", "SUCCESS", null,
                    AuditLogUtil.getClientIp(request), false);

            try (InputStream in = Files.newInputStream(resolved);
                 ServletOutputStream out = response.getOutputStream()) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) >= 0) {
                    out.write(buf, 0, n);
                }
                out.flush();
            }
        } catch (Exception e) {
            logger.warn("下载本地文件失败: {}", e.getMessage());
            try {
                response.setStatus(500);
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * 文件分块上传
     */
    @RequestMapping(value = "/upload-chunk", method = RequestMethod.POST)
    public HashMap<String, Object> fileUploadChunk(@RequestBody HashMap<String, Object> params) {
        String filePath = ControllerUtil.getRequiredStringParam(params, "filePath");
        if (params.get("offset") == null || params.get("data") == null) {
            throw ApiException.badRequest("缺少必要参数: offset 或 data");
        }

        FileCapable node = getFileNode(params);
        AbstractPuppetNode auditNode = asAuditNode(node);
        long offset = ControllerUtil.toLong(params.get("offset"));
        Object dataObj = params.get("data");
        if (!(dataObj instanceof String)) {
            throw ApiException.badRequest("data参数必须是Base64字符串");
        }
        byte[] data = Base64.getDecoder().decode((String) dataObj);

        if (offset == 0) {
            logger.debug("开始分块上传文件: {}, 偏移量: {}, 数据大小: {}", filePath, offset, data.length);
        }
        Map<String, Object> results = puppetCall(() -> node.fileUploadChunk(filePath, offset, data), "文件分块上传失败");
        if (offset == 0) {
            AuditLogUtil.logSuccess(auditNode, "FILE_UPLOAD", "上传文件", filePath, auditParams(params),
                    ApiResponse.CODE_SUCCESS, "开始上传文件", AuditLogUtil.getClientIp());
        }
        return ApiResponse.success(results != null ? results : new HashMap<>());
    }

    /**
     * 分片预览：支持 offset + size 按需加载文件内容。
     * 统一响应结构：{ data(base64), size(文件总大小), truncated(是否截断) }
     */
    @RequestMapping(value = "/preview-chunk", method = RequestMethod.POST)
    public HashMap<String, Object> filePreviewChunk(@RequestBody HashMap<String, Object> params) {
        String path = ControllerUtil.getRequiredStringParam(params, "path");
        FileCapable node = getFileNode(params);
        AbstractPuppetNode auditNode = asAuditNode(node);

        long offset = params.get("offset") != null ? ControllerUtil.toLong(params.get("offset")) : 0L;
        long size = params.get("size") != null ? ControllerUtil.toLong(params.get("size")) : PREVIEW_SIZE;
        // 限制单次最大读取 2MB
        size = Math.min(size, 2 * 1024 * 1024L);

        final long finalSize = size;
        Map<String, Object> results = puppetCall(
                () -> node.fileDownloadChunk(path, finalSize, offset),
                "文件分片预览失败",
                CHUNK_SUCCESS_CODES
        );
        if (offset == 0L) {
            AuditLogUtil.logSuccess(auditNode, "FILE_READ", "读取文件", path, params,
                    ApiResponse.CODE_SUCCESS, "读取文件成功", AuditLogUtil.getClientIp());
        }
        return ApiResponse.success(normalizeChunkResult(results));
    }

    /**
     * 文件预览（同时将预览内容按服务器路径结构存入会话 root/sessions/{sessionId}/file 下）
     * 统一响应结构：{ data(base64), size(文件总大小), truncated(是否截断) }
     */
    @RequestMapping(value = "/preview", method = RequestMethod.POST)
    public HashMap<String, Object> filePreview(@RequestBody HashMap<String, Object> params) {
        String path = ControllerUtil.getRequiredStringParam(params, "path");
        FileCapable node = getFileNode(params);
        AbstractPuppetNode auditNode = asAuditNode(node);
        Map<String, Object> results = puppetCall(
                () -> node.fileDownloadChunk(path, PREVIEW_SIZE, 0L),
                "文件预览失败",
                CHUNK_SUCCESS_CODES
        );
        if (results != null) {
            Object codeObj = results.get("code");
            int code = codeObj instanceof Number ? ((Number) codeObj).intValue() : 0;
            if (code == 200 || code == 100) {
                AuditLogUtil.logSuccess(auditNode, "FILE_READ", "读取文件", path, params,
                        ApiResponse.CODE_SUCCESS, "读取文件成功", AuditLogUtil.getClientIp());
                Object dataObj = results.get("data");
                byte[] chunkData = null;
                if (dataObj instanceof byte[]) {
                    chunkData = (byte[]) dataObj;
                } else if (dataObj instanceof String) {
                    chunkData = Base64.getDecoder().decode((String) dataObj);
                }
                if (chunkData != null && chunkData.length > 0) {
                    String sessionId = (String) params.get("sessionId");
                    if (sessionId != null && !sessionId.isBlank()) {
                        try {
                            fileRepository.appendDownloadChunk(sessionId, path, 0L, chunkData);
                        } catch (Exception ex) {
                            logger.warn("预览写入会话文件目录失败, sessionId={}, path={}: {}", sessionId, path, ex.getMessage());
                        }
                    }
                }
            }
        }
        return ApiResponse.success(normalizeChunkResult(results));
    }

    /**
     * 将底层组件返回的 {code, data, size} 标准化为前端友好结构 {data(base64), size, truncated}
     */
    private HashMap<String, Object> normalizeChunkResult(Map<String, Object> raw) {
        HashMap<String, Object> normalized = new HashMap<>();
        if (raw == null) {
            return normalized;
        }

        // 解析内层 code：200=完整, 100=截断
        Object codeObj = raw.get("code");
        int innerCode = codeObj instanceof Number ? ((Number) codeObj).intValue() : 0;
        normalized.put("truncated", innerCode == 100);

        // 文件总大小。
        Object sizeObj = raw.get("length");
        Long totalSize = null;
        if (sizeObj instanceof Number) {
            totalSize = ((Number) sizeObj).longValue();
            normalized.put("size", totalSize);
        }

        // data：确保为 base64 字符串
        Object dataObj = raw.get("data");
        if (dataObj instanceof byte[]) {
            normalized.put("data", Base64.getEncoder().encodeToString((byte[]) dataObj));
        } else if (dataObj instanceof String) {
            normalized.put("data", dataObj);
        } else if (totalSize != null && totalSize == 0L && innerCode == 200) {
            normalized.put("data", "");
        }

        normalized.put("nextOffset", raw.get("nextOffset"));

        return normalized;
    }

    /**
     * 编辑文件
     */
    @RequestMapping(value = "/edit", method = RequestMethod.POST)
    public HashMap<String, Object> editFile(@RequestBody HashMap<String, Object> params) {
        String path = ControllerUtil.getRequiredStringParam(params, "path");
        String content = (String) params.get("content");
        FileCapable node = getFileNode(params);
        AbstractPuppetNode auditNode = asAuditNode(node);
        Map<String, Object> results = puppetCall(() -> node.editFile(path, content), "编辑文件失败");
        AuditLogUtil.logSuccess(auditNode, "FILE_EDIT", "编辑文件", path, auditParams(params),
                ApiResponse.CODE_SUCCESS, "编辑文件成功", AuditLogUtil.getClientIp());
        return ApiResponse.success(results);
    }

    /**
     * 新建文件
     */
    @RequestMapping(value = "/new-file", method = RequestMethod.POST)
    public HashMap<String, Object> newFile(@RequestBody HashMap<String, Object> params) {
        String path = ControllerUtil.getRequiredStringParam(params, "path");
        String content = (String) params.get("content");
        FileCapable node = getFileNode(params);
        AbstractPuppetNode auditNode = asAuditNode(node);
        Map<String, Object> results = puppetCall(() -> node.createFile(path, content), "新建文件失败");
        AuditLogUtil.logSuccess(auditNode, "FILE_NEW", "新建文件", path, auditParams(params),
                ApiResponse.CODE_SUCCESS, "新建文件成功", AuditLogUtil.getClientIp());
        return ApiResponse.success(results);
    }

    /**
     * 移动文件
     */
    @RequestMapping(value = "/move", method = RequestMethod.POST)
    public HashMap<String, Object> moveFile(@RequestBody HashMap<String, Object> params) {
        String path = ControllerUtil.getRequiredStringParam(params, "path");
        String newPath = ControllerUtil.getRequiredStringParam(params, "newPath");
        String conflictStrategy = normalizeConflictStrategy(params.get("conflictStrategy"));
        FileCapable node = getFileNode(params);
        AbstractPuppetNode auditNode = asAuditNode(node);
        Map<String, Object> results = puppetCall(() -> node.moveFile(path, newPath, conflictStrategy), "移动文件失败");
        AuditLogUtil.logSuccess(auditNode, "FILE_MOVE", "移动文件", path + " -> " + newPath, params,
                ApiResponse.CODE_SUCCESS, "移动文件成功", AuditLogUtil.getClientIp());
        return ApiResponse.success(results);
    }

    /**
     * 复制文件
     */
    @RequestMapping(value = "/copy", method = RequestMethod.POST)
    public HashMap<String, Object> copyFile(@RequestBody HashMap<String, Object> params) {
        String path = ControllerUtil.getRequiredStringParam(params, "path");
        String destPath = ControllerUtil.getRequiredStringParam(params, "destPath");
        String conflictStrategy = normalizeConflictStrategy(params.get("conflictStrategy"));
        FileCapable node = getFileNode(params);
        AbstractPuppetNode auditNode = asAuditNode(node);
        Map<String, Object> results = puppetCall(() -> node.copyFile(path, destPath, conflictStrategy), "复制文件失败");
        AuditLogUtil.logSuccess(auditNode, "FILE_COPY", "复制文件", path + " -> " + destPath, params,
                ApiResponse.CODE_SUCCESS, "复制文件成功", AuditLogUtil.getClientIp());
        return ApiResponse.success(results);
    }

    /**
     * 删除文件
     */
    @RequestMapping(value = "/delete", method = RequestMethod.POST)
    public HashMap<String, Object> deleteFile(@RequestBody HashMap<String, Object> params) {
        String path = ControllerUtil.getRequiredStringParam(params, "path");
        FileCapable node = getFileNode(params);
        AbstractPuppetNode auditNode = asAuditNode(node);
        Map<String, Object> results = puppetCall(() -> node.deleteFile(path), "删除文件失败");
        AuditLogUtil.logSuccess(auditNode, "FILE_DELETE", "删除文件", path, params,
                ApiResponse.CODE_SUCCESS, "删除文件成功", AuditLogUtil.getClientIp());
        return ApiResponse.success(results);
    }

    /**
     * 新建目录
     */
    @RequestMapping(value = "/new-dir", method = RequestMethod.POST)
    public HashMap<String, Object> newDir(@RequestBody HashMap<String, Object> params) {
        String path = ControllerUtil.getRequiredStringParam(params, "path");
        FileCapable node = getFileNode(params);
        AbstractPuppetNode auditNode = asAuditNode(node);
        Map<String, Object> results = puppetCall(() -> node.createDir(path), "新建目录失败");
        AuditLogUtil.logSuccess(auditNode, "FILE_NEW_DIR", "新建目录", path, params,
                ApiResponse.CODE_SUCCESS, "新建目录成功", AuditLogUtil.getClientIp());
        return ApiResponse.success(results);
    }

    /**
     * 文件压缩
     */
    @RequestMapping(value = "/compress", method = RequestMethod.POST)
    public HashMap<String, Object> compress(@RequestBody HashMap<String, Object> params) {
        String src = ControllerUtil.getRequiredStringParam(params, "src");
        String des = ControllerUtil.getRequiredStringParam(params, "des");
        FileCapable node = getFileNode(params);
        AbstractPuppetNode auditNode = asAuditNode(node);

        // 获取排除文件模式（正则表达式，String类型）
        String excludePattern = null;
        Object excludeObj = params.get("exclude");
        if (excludeObj instanceof String) {
            String excludeStr = (String) excludeObj;
            if (!excludeStr.isBlank()) {
                excludePattern = excludeStr;
            }
        }

        final String finalExclude = excludePattern;
        Map<String, Object> results = puppetCall(() -> node.compressFile(src, des, finalExclude), "文件压缩失败");
        AuditLogUtil.logSuccess(auditNode, "FILE_COMPRESS", "文件压缩", src + " -> " + des, params,
                ApiResponse.CODE_SUCCESS, "文件压缩成功", AuditLogUtil.getClientIp());
        return ApiResponse.success(results != null ? results : new HashMap<>());
    }

    /**
     * 文件解压
     */
    @RequestMapping(value = "/decompress", method = RequestMethod.POST)
    public HashMap<String, Object> decompress(@RequestBody HashMap<String, Object> params) {
        String src = ControllerUtil.getRequiredStringParam(params, "src");
        String des = ControllerUtil.getRequiredStringParam(params, "des");
        String format = ControllerUtil.getRequiredStringParam(params, "format");

        FileCapable node = getFileNode(params);
        AbstractPuppetNode auditNode = asAuditNode(node);
        Map<String, Object> results = puppetCall(() -> node.decompressFile(src, des, format), "文件解压失败");
        String operationPath = src + " -> " + des + " (" + format + ")";
        AuditLogUtil.logSuccess(auditNode, "FILE_DECOMPRESS", "文件解压", operationPath, params,
                ApiResponse.CODE_SUCCESS, "文件解压成功", AuditLogUtil.getClientIp());
        return ApiResponse.success(results != null ? results : new HashMap<>());
    }

    /**
     * 获取文件MD5
     */
    @RequestMapping(value = "/md5", method = RequestMethod.POST)
    public HashMap<String, Object> getFileMD5(@RequestBody HashMap<String, Object> params) {
        String path = ControllerUtil.getRequiredStringParam(params, "path");
        FileCapable node = getFileNode(params);
        Map<String, Object> results = puppetCall(() -> node.getFileMD5(path), "获取文件MD5失败");
        return ApiResponse.success(results);
    }

    // ── 私有辅助 ──────────────────────────────────────────────────────────────

    /**
     * 包装 puppet 节点调用，将 checked Exception 转为 ApiException（RuntimeException），
     * 由 GlobalExceptionHandler 统一处理并返回正确 HTTP 状态码。
     */
    @SuppressWarnings("unchecked")
    private <T> T puppetCall(PuppetAction<T> action, String errorPrefix) {
        return puppetCall(action, errorPrefix, SUCCESS_CODES);
    }

    private <T> T puppetCall(PuppetAction<T> action, String errorPrefix, Set<Integer> acceptedCodes) {
        try {
            T result = action.execute();
            if (!(result instanceof Map<?, ?> resultMap)) {
                if (result == null) {
                    throw ApiException.serverError(errorPrefix + ": 节点返回为空");
                }
                return result;
            }

            Object codeValue = resultMap.get("code");
            Integer code = null;
            if (codeValue instanceof Number number) {
                code = number.intValue();
            } else if (codeValue != null) {
                try {
                    code = Integer.parseInt(codeValue.toString());
                } catch (NumberFormatException ignored) {
                    // 统一落入无效响应处理。
                }
            }
            if (code == null || !acceptedCodes.contains(code)) {
                Object message = resultMap.get("msg");
                throw ApiException.serverError(errorPrefix + ": "
                        + (message != null ? message : "节点返回状态异常(" + codeValue + ")"));
            }
            return result;
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.serverError(errorPrefix + ": " + e.getMessage());
        }
    }

    @FunctionalInterface
    private interface PuppetAction<T> {
        T execute() throws Exception;
    }

    private FileCapable getFileNode(Map<String, Object> params) {
        return ControllerUtil.requireCapability(params, FileCapable.class);
    }

    private AbstractPuppetNode asAuditNode(FileCapable node) {
        return node instanceof AbstractPuppetNode ? (AbstractPuppetNode) node : null;
    }

    private Map<String, Object> auditParams(Map<String, Object> params) {
        Map<String, Object> safe = new HashMap<>(params);
        Object data = safe.remove("data");
        if (data instanceof String encoded) {
            safe.put("encodedDataLength", encoded.length());
        }
        Object content = safe.remove("content");
        if (content instanceof String text) {
            safe.put("contentLength", text.length());
        }
        return safe;
    }

    /** 规范化 conflictStrategy：仅允许 overwrite / autorename / skip。 */
    private static String normalizeConflictStrategy(Object raw) {
        if (raw == null) {
            throw ApiException.badRequest("缺少必要参数: conflictStrategy");
        }
        String s = raw.toString().trim();
        if (s.isEmpty()) {
            throw ApiException.badRequest("缺少必要参数: conflictStrategy");
        }
        if ("overwrite".equals(s) || "autorename".equals(s) || "skip".equals(s)) {
            return s;
        }
        throw ApiException.badRequest("conflictStrategy仅支持 overwrite、autorename、skip");
    }
}
