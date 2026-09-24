package org.leo.web.controller.puppetnode.file;

import org.leo.core.util.ApiResponse;
import org.leo.web.util.ControllerUtil;
import org.leo.core.puppet.AbstractPuppetNode;
import org.leo.core.puppet.capability.FileCapable;
import org.leo.service.DownloadEngineService;
import org.leo.web.util.AuditLogUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import org.leo.core.entity.User;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/puppet-node/file/download-engine")
public class DownloadEngineController {
    private static final Logger logger = LoggerFactory.getLogger(DownloadEngineController.class);

    private final DownloadEngineService downloadEngineService;

    public DownloadEngineController(DownloadEngineService downloadEngineService) {
        this.downloadEngineService = downloadEngineService;
    }

    /**
     * 启动（或自动续传）一个文件下载任务。
     *
     * 入参（JSON）：
     * - sessionId: string (必填)
     * - filePath: string (必填) 远端文件路径（目标机上的路径）
     * - threads: number (可选) 并发线程数，默认 4，上限 16
     * - chunkSize: number (可选) 分块大小（byte），默认 1048576（1MB），上限 1048576（受 FileDownloadComponent 限制）
     */
    @RequestMapping(value = "/start", method = RequestMethod.POST)
    public HashMap<String, Object> start(HttpServletRequest request, @RequestBody HashMap<String, Object> params) {
        try {
            User user = (User) request.getSession().getAttribute("user");
            if (user == null) {
                return ApiResponse.unauthorized("用户未登录");
            }
            String userId = user.getUserId();
            String sessionId = ControllerUtil.getRequiredStringParam(params, "sessionId");

            String filePath = ControllerUtil.getRequiredStringParam(params, "filePath");
            FileCapable fileNode = ControllerUtil.requireCapability(params, FileCapable.class);
            AbstractPuppetNode auditNode = ControllerUtil.getAbstractPuppetNode(sessionId);

            int threads = params.get("threads") == null ? 4 : ((Number) params.get("threads")).intValue();
            int chunkSize = params.get("chunkSize") == null ? (1024 * 1024) : ((Number) params.get("chunkSize")).intValue();

            Map<String, Object> data = downloadEngineService.startOrResume(fileNode, userId, sessionId, filePath, threads, chunkSize);
            AuditLogUtil.logSuccess(auditNode, "FILE_DOWNLOAD", "下载文件", filePath, params,
                    ApiResponse.CODE_SUCCESS, "开始下载文件", AuditLogUtil.getClientIp(request));
            return ApiResponse.success(data);
        } catch (Exception e) {
            logger.warn("download-engine start failed: {}", e.getMessage());
            return ApiResponse.error("启动下载任务失败: " + e.getMessage());
        }
    }

    /**
     * 查询任务进度。
     * 入参：sessionId, taskId
     */
    @RequestMapping(value = "/progress", method = RequestMethod.POST)
    public HashMap<String, Object> progress(HttpServletRequest request, @RequestBody HashMap<String, Object> params) {
        try {
            User user = (User) request.getSession().getAttribute("user");
            if (user == null) {
                return ApiResponse.unauthorized("用户未登录");
            }
            String userId = user.getUserId();
            String taskId = ControllerUtil.getRequiredStringParam(params, "taskId");
            Map<String, Object> data = downloadEngineService.progress(userId, taskId);
            return ApiResponse.success(data);
        } catch (Exception e) {
            return ApiResponse.error("查询下载进度失败: " + e.getMessage());
        }
    }

    /** 暂停任务，保留已下载分块，可通过 resume 继续。 */
    @RequestMapping(value = "/pause", method = RequestMethod.POST)
    public HashMap<String, Object> pause(HttpServletRequest request, @RequestBody HashMap<String, Object> params) {
        try {
            User user = (User) request.getSession().getAttribute("user");
            if (user == null) {
                return ApiResponse.unauthorized("用户未登录");
            }
            String taskId = ControllerUtil.getRequiredStringParam(params, "taskId");
            Map<String, Object> data = downloadEngineService.pause(user.getUserId(), taskId);
            AuditLogUtil.logSystemOperation(user, "FILE_DOWNLOAD_PAUSE", "暂停下载任务", taskId, params,
                    ApiResponse.CODE_SUCCESS, "暂停下载任务", "SUCCESS", null,
                    AuditLogUtil.getClientIp(request), false);
            return ApiResponse.success(data);
        } catch (Exception e) {
            return ApiResponse.error("暂停下载任务失败: " + e.getMessage());
        }
    }

    /** 取消任务并将任务置为终态。 */
    @RequestMapping(value = "/cancel", method = RequestMethod.POST)
    public HashMap<String, Object> cancel(HttpServletRequest request, @RequestBody HashMap<String, Object> params) {
        try {
            User user = (User) request.getSession().getAttribute("user");
            if (user == null) {
                return ApiResponse.unauthorized("用户未登录");
            }
            String taskId = ControllerUtil.getRequiredStringParam(params, "taskId");
            Map<String, Object> data = downloadEngineService.cancel(user.getUserId(), taskId);
            AuditLogUtil.logSystemOperation(user, "FILE_DOWNLOAD_CANCEL", "取消下载任务", taskId, params,
                    ApiResponse.CODE_SUCCESS, "取消下载任务", "SUCCESS", null,
                    AuditLogUtil.getClientIp(request), false);
            return ApiResponse.success(data);
        } catch (Exception e) {
            return ApiResponse.error("取消下载任务失败: " + e.getMessage());
        }
    }

    /**
     * 显式恢复任务（等价于 startOrResume，但必须提供 taskId）。
     * 入参：sessionId, taskId
     */
    @RequestMapping(value = "/resume", method = RequestMethod.POST)
    public HashMap<String, Object> resume(HttpServletRequest request, @RequestBody HashMap<String, Object> params) {
        try {
            User user = (User) request.getSession().getAttribute("user");
            if (user == null) {
                return ApiResponse.unauthorized("用户未登录");
            }
            String userId = user.getUserId();
            String taskId = ControllerUtil.getRequiredStringParam(params, "taskId");
            String sessionId = ControllerUtil.getRequiredStringParam(params, "sessionId");
            FileCapable fileNode = ControllerUtil.requireCapability(sessionId, FileCapable.class);
            AbstractPuppetNode auditNode = ControllerUtil.getAbstractPuppetNode(sessionId);
            Map<String, Object> data = downloadEngineService.resume(fileNode, userId, sessionId, taskId);
            AuditLogUtil.logSuccess(auditNode, "FILE_DOWNLOAD_RESUME", "恢复下载任务", taskId, params,
                    ApiResponse.CODE_SUCCESS, "恢复下载任务", AuditLogUtil.getClientIp(request));
            return ApiResponse.success(data);
        } catch (Exception e) {
            return ApiResponse.error("恢复下载任务失败: " + e.getMessage());
        }
    }

    /** Retry a failed task while retaining verified chunks already stored locally. */
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
            Map<String, Object> data = downloadEngineService.retry(
                    fileNode, user.getUserId(), sessionId, taskId);
            AuditLogUtil.logSystemOperation(user, "FILE_DOWNLOAD_RETRY", "重试下载任务",
                    taskId, params, ApiResponse.CODE_SUCCESS, "重试下载任务",
                    "SUCCESS", null, AuditLogUtil.getClientIp(request), false);
            return ApiResponse.success(data);
        } catch (Exception e) {
            return ApiResponse.error("重试下载任务失败: " + e.getMessage());
        }
    }

    /** Remove task metadata and partial data; a committed download file is retained. */
    @RequestMapping(value = "/remove", method = RequestMethod.POST)
    public HashMap<String, Object> remove(HttpServletRequest request,
                                          @RequestBody HashMap<String, Object> params) {
        try {
            User user = (User) request.getSession().getAttribute("user");
            if (user == null) {
                return ApiResponse.unauthorized("用户未登录");
            }
            String taskId = ControllerUtil.getRequiredStringParam(params, "taskId");
            Map<String, Object> data = downloadEngineService.remove(user.getUserId(), taskId);
            AuditLogUtil.logSystemOperation(user, "FILE_DOWNLOAD_REMOVE", "清理下载任务",
                    taskId, params, ApiResponse.CODE_SUCCESS, "清理下载任务",
                    "SUCCESS", null, AuditLogUtil.getClientIp(request), false);
            return ApiResponse.success(data);
        } catch (Exception e) {
            return ApiResponse.error("清理下载任务失败: " + e.getMessage());
        }
    }

    /**
     * 按会话查询下载任务列表（管理接口）。
     * 入参：sessionId
     */
    @RequestMapping(value = "/tasks", method = RequestMethod.POST)
    public HashMap<String, Object> tasks(HttpServletRequest request, @RequestBody HashMap<String, Object> params) {
        try {
            User user = (User) request.getSession().getAttribute("user");
            if (user == null) {
                return ApiResponse.unauthorized("用户未登录");
            }
            String userId = user.getUserId();
            String sessionId = ControllerUtil.getRequiredStringParam(params, "sessionId");
            Map<String, Object> data = downloadEngineService.listBySessionId(userId, sessionId);
            return ApiResponse.success(data);
        } catch (Exception e) {
            return ApiResponse.error("查询下载任务列表失败: " + e.getMessage());
        }
    }
}
