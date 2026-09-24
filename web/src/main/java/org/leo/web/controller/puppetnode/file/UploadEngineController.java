package org.leo.web.controller.puppetnode.file;

import org.leo.core.entity.User;
import org.leo.core.puppet.AbstractPuppetNode;
import org.leo.core.puppet.capability.FileCapable;
import org.leo.service.UploadEngineService;
import org.leo.core.util.ApiResponse;
import org.leo.web.util.ControllerUtil;
import org.leo.web.util.AuditLogUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/puppet-node/file/upload-engine")
public class UploadEngineController {
    private static final Logger logger = LoggerFactory.getLogger(UploadEngineController.class);

    private final UploadEngineService uploadEngineService;

    public UploadEngineController(UploadEngineService uploadEngineService) {
        this.uploadEngineService = uploadEngineService;
    }

    /**
     * 从平台侧 VFS 读取文件，并在后台分块上传到目标机。
     *
     * 入参（JSON）：
     * - sessionId: string (必填)
     * - vfsPath: string (必填) 平台侧 VFS 中相对根目录的路径。admin 用户可访问整个 VFS；普通用户仅可访问 users/<userId>/... 和 skills/...
     * - filePath: string (必填) 远端目标文件路径
     * - chunkSize: number (可选) 分块大小（byte），默认 1048576，上限 1048576
     */
    @RequestMapping(value = "/start", method = RequestMethod.POST)
    public HashMap<String, Object> start(HttpServletRequest request,
                                         @RequestBody HashMap<String, Object> params) {
        try {
            User user = (User) request.getSession().getAttribute("user");
            if (user == null) {
                return ApiResponse.unauthorized("用户未登录");
            }
            String userId = user.getUserId();
            String sessionId = ControllerUtil.getRequiredStringParam(params, "sessionId");
            String vfsPath = ControllerUtil.getRequiredStringParam(params, "vfsPath");
            String filePath = ControllerUtil.getRequiredStringParam(params, "filePath");
            FileCapable fileNode = ControllerUtil.requireCapability(params, FileCapable.class);
            AbstractPuppetNode auditNode = ControllerUtil.getAbstractPuppetNode(sessionId);
            Path sourceFile = uploadEngineService.resolveVfsFilePath(vfsPath);
            uploadEngineService.validateReadPermission(userId, user.getPrivilege(), sourceFile);
            if (!Files.exists(sourceFile) || !Files.isRegularFile(sourceFile)) {
                return ApiResponse.notFound("平台侧文件不存在: " + vfsPath);
            }

            int resolvedChunkSize = params.get("chunkSize") == null ? (1024 * 1024) : ((Number) params.get("chunkSize")).intValue();
            Map<String, Object> data = uploadEngineService.start(
                    fileNode,
                    userId,
                    sessionId,
                    filePath,
                    sourceFile.toFile(),
                    sourceFile.getFileName().toString(),
                    resolvedChunkSize
            );
            AuditLogUtil.logSuccess(auditNode, "FILE_UPLOAD", "上传文件", vfsPath + " -> " + filePath, params,
                    ApiResponse.CODE_SUCCESS, "开始上传文件", AuditLogUtil.getClientIp(request));
            return ApiResponse.success(data);
        } catch (Exception e) {
            logger.warn("upload-engine start failed: {}", e.getMessage());
            return ApiResponse.error("启动上传任务失败: " + e.getMessage());
        }
    }

    /**
     * 查询任务进度。
     * 入参：taskId
     */
    @RequestMapping(value = "/progress", method = RequestMethod.POST)
    public HashMap<String, Object> progress(HttpServletRequest request,
                                            @RequestBody HashMap<String, Object> params) {
        try {
            User user = (User) request.getSession().getAttribute("user");
            if (user == null) {
                return ApiResponse.unauthorized("用户未登录");
            }
            String taskId = ControllerUtil.getRequiredStringParam(params, "taskId");
            Map<String, Object> data = uploadEngineService.progress(user.getUserId(), taskId);
            return ApiResponse.success(data);
        } catch (Exception e) {
            return ApiResponse.error("查询上传进度失败: " + e.getMessage());
        }
    }

    /**
     * 取消任务。
     * 入参：taskId
     */
    @RequestMapping(value = "/cancel", method = RequestMethod.POST)
    public HashMap<String, Object> cancel(HttpServletRequest request,
                                          @RequestBody HashMap<String, Object> params) {
        try {
            User user = (User) request.getSession().getAttribute("user");
            if (user == null) {
                return ApiResponse.unauthorized("用户未登录");
            }
            String taskId = ControllerUtil.getRequiredStringParam(params, "taskId");
            Map<String, Object> data = uploadEngineService.cancel(user.getUserId(), taskId);
            AuditLogUtil.logSystemOperation(user, "FILE_UPLOAD_CANCEL", "取消上传任务", taskId, params,
                    ApiResponse.CODE_SUCCESS, "取消上传任务", "SUCCESS", null,
                    AuditLogUtil.getClientIp(request), false);
            return ApiResponse.success(data);
        } catch (Exception e) {
            return ApiResponse.error("取消上传任务失败: " + e.getMessage());
        }
    }

    @RequestMapping(value = "/pause", method = RequestMethod.POST)
    public HashMap<String, Object> pause(HttpServletRequest request,
                                         @RequestBody HashMap<String, Object> params) {
        try {
            User user = (User) request.getSession().getAttribute("user");
            if (user == null) {
                return ApiResponse.unauthorized("用户未登录");
            }
            String taskId = ControllerUtil.getRequiredStringParam(params, "taskId");
            Map<String, Object> data = uploadEngineService.pause(user.getUserId(), taskId);
            AuditLogUtil.logSystemOperation(user, "FILE_UPLOAD_PAUSE", "暂停上传任务",
                    taskId, params, ApiResponse.CODE_SUCCESS, "暂停上传任务",
                    "SUCCESS", null, AuditLogUtil.getClientIp(request), false);
            return ApiResponse.success(data);
        } catch (Exception e) {
            return ApiResponse.error("暂停上传任务失败: " + e.getMessage());
        }
    }

    @RequestMapping(value = "/resume", method = RequestMethod.POST)
    public HashMap<String, Object> resume(HttpServletRequest request,
                                          @RequestBody HashMap<String, Object> params) {
        try {
            User user = (User) request.getSession().getAttribute("user");
            if (user == null) {
                return ApiResponse.unauthorized("用户未登录");
            }
            String taskId = ControllerUtil.getRequiredStringParam(params, "taskId");
            String sessionId = ControllerUtil.getRequiredStringParam(params, "sessionId");
            FileCapable fileNode = ControllerUtil.requireCapability(sessionId, FileCapable.class);
            Map<String, Object> data = uploadEngineService.resume(
                    fileNode, user.getUserId(), sessionId, taskId);
            AuditLogUtil.logSuccess(ControllerUtil.getAbstractPuppetNode(sessionId),
                    "FILE_UPLOAD_RESUME", "恢复上传任务", taskId, params,
                    ApiResponse.CODE_SUCCESS, "恢复上传任务", AuditLogUtil.getClientIp(request));
            return ApiResponse.success(data);
        } catch (Exception e) {
            return ApiResponse.error("恢复上传任务失败: " + e.getMessage());
        }
    }

    @RequestMapping(value = "/retry", method = RequestMethod.POST)
    public HashMap<String, Object> retry(HttpServletRequest request,
                                         @RequestBody HashMap<String, Object> params) {
        try {
            User user = (User) request.getSession().getAttribute("user");
            if (user == null) {
                return ApiResponse.unauthorized("用户未登录");
            }
            String taskId = ControllerUtil.getRequiredStringParam(params, "taskId");
            String sessionId = ControllerUtil.getRequiredStringParam(params, "sessionId");
            FileCapable fileNode = ControllerUtil.requireCapability(sessionId, FileCapable.class);
            Map<String, Object> data = uploadEngineService.retry(
                    fileNode, user.getUserId(), sessionId, taskId);
            AuditLogUtil.logSuccess(ControllerUtil.getAbstractPuppetNode(sessionId),
                    "FILE_UPLOAD_RETRY", "重试上传任务", taskId, params,
                    ApiResponse.CODE_SUCCESS, "重试上传任务", AuditLogUtil.getClientIp(request));
            return ApiResponse.success(data);
        } catch (Exception e) {
            return ApiResponse.error("重试上传任务失败: " + e.getMessage());
        }
    }

    @RequestMapping(value = "/remove", method = RequestMethod.POST)
    public HashMap<String, Object> remove(HttpServletRequest request,
                                          @RequestBody HashMap<String, Object> params) {
        try {
            User user = (User) request.getSession().getAttribute("user");
            if (user == null) {
                return ApiResponse.unauthorized("用户未登录");
            }
            String taskId = ControllerUtil.getRequiredStringParam(params, "taskId");
            Map<String, Object> data = uploadEngineService.remove(user.getUserId(), taskId);
            AuditLogUtil.logSystemOperation(user, "FILE_UPLOAD_REMOVE", "清理上传任务",
                    taskId, params, ApiResponse.CODE_SUCCESS, "清理上传任务",
                    "SUCCESS", null, AuditLogUtil.getClientIp(request), false);
            return ApiResponse.success(data);
        } catch (Exception e) {
            return ApiResponse.error("清理上传任务失败: " + e.getMessage());
        }
    }

    /**
     * 按会话查询上传任务列表（仅返回当前进程中的任务）。
     * 入参：sessionId
     */
    @RequestMapping(value = "/tasks", method = RequestMethod.POST)
    public HashMap<String, Object> tasks(HttpServletRequest request,
                                         @RequestBody HashMap<String, Object> params) {
        try {
            User user = (User) request.getSession().getAttribute("user");
            if (user == null) {
                return ApiResponse.unauthorized("用户未登录");
            }
            String sessionId = ControllerUtil.getRequiredStringParam(params, "sessionId");
            Map<String, Object> data = uploadEngineService.listBySessionId(user.getUserId(), sessionId);
            return ApiResponse.success(data);
        } catch (Exception e) {
            return ApiResponse.error("查询上传任务列表失败: " + e.getMessage());
        }
    }
}
