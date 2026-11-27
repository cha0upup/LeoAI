package org.leo.web.controller.platform.admin;

import jakarta.servlet.http.HttpServletRequest;
import org.leo.core.entity.AuditLog;
import org.leo.core.entity.AuditLogQuery;
import org.leo.core.util.ApiResponse;
import org.leo.service.audit.AuditLogService;
import org.leo.service.audit.AuditPolicyService;
import org.leo.web.exception.ApiException;
import org.leo.web.infrastructure.audit.WebAuditRecorder;
import org.leo.web.security.PermissionService;
import org.leo.web.util.AuditLogUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 审计日志管理控制器
 * 提供审计日志的查询、统计和管理功能
 * 
 * @author LeoSpring
 * @version 2.1
 */
@RestController
@RequestMapping("/platform/admin/audit-logs")
public class AuditLogController {
    
    private static final Logger logger = LoggerFactory.getLogger(AuditLogController.class);
    
    // 参数名常量
    private static final String PARAM_LOG_ID = "logId";
    private static final String PARAM_USER_ID = "userId";
    private static final String PARAM_AGENT_ID = "agentId";
    private static final String PARAM_OPERATION_TYPE = "operationType";
    private static final String PARAM_USER_NAME = "userName";
    private static final String PARAM_AGENT_NAME = "agentName";
    private static final String PARAM_SESSION_ID = "sessionId";
    private static final String PARAM_STATUS = "status";
    private static final String PARAM_CLIENT_IP = "clientIp";
    private static final String PARAM_KEYWORD = "keyword";
    private static final String PARAM_REMARK = "remark";
    private static final String PARAM_START_TIME = "startTime";
    private static final String PARAM_END_TIME = "endTime";
    private static final String PARAM_LOG_IDS = "logIds";
    private static final String PARAM_CONFIRM = "confirm";
    private static final String PARAM_LIMIT = "limit";
    private static final String PARAM_OFFSET = "offset";
    private static final String PARAM_DAYS = "days";
    private static final String PARAM_TEAM_ID = "teamId";
    
    // 默认值常量
    private static final int DEFAULT_DAYS = 30;
    
    private final AuditLogService auditLogService;
    private final AuditPolicyService auditPolicyService;
    private final PermissionService permissionService;
    private final WebAuditRecorder auditRecorder;
    
    public AuditLogController(AuditLogService auditLogService,
                              AuditPolicyService auditPolicyService,
                              PermissionService permissionService,
                              WebAuditRecorder auditRecorder) {
        this.auditLogService = auditLogService;
        this.auditPolicyService = auditPolicyService;
        this.permissionService = permissionService;
        this.auditRecorder = auditRecorder;
    }

    @ModelAttribute
    public void requireAdmin(HttpServletRequest request) {
        permissionService.requireAdmin(permissionService.requireLogin(request));
    }

    /**
     * 获取审计记录模式。
     */
    @RequestMapping(value = "/mode", method = RequestMethod.GET)
    public HashMap<String, Object> getAuditMode() {
        return auditResponse("获取审计模式失败", () -> {
            String mode = auditPolicyService.getMode();
            HashMap<String, Object> data = buildAuditModeView(mode);
            return ApiResponse.success(data);
        });
    }

    /**
     * 更新审计记录模式。
     */
    @RequestMapping(value = "/mode", method = RequestMethod.POST)
    public HashMap<String, Object> updateAuditMode(@RequestBody HashMap<String, Object> params,
                                                   HttpServletRequest request) {
        String requestedMode = textParam(params, "mode");
        if (requestedMode == null || requestedMode.isBlank()) {
            return ApiResponse.badRequest("mode参数不能为空");
        }
        String validMode;
        try {
            validMode = auditPolicyService.requireValidMode(requestedMode);
        } catch (IllegalArgumentException error) {
            return ApiResponse.badRequest(error.getMessage());
        }

        return auditResponse("更新审计模式失败", () -> {
            String oldMode = auditPolicyService.getMode();
            String newMode = auditPolicyService.updateMode(validMode);

            HashMap<String, Object> auditParams = new HashMap<String, Object>();
            auditParams.put("oldMode", oldMode);
            auditParams.put("newMode", newMode);
            auditParams.put("newModeLabel", auditPolicyService.getModeLabel(newMode));

            recordAuditChange(request, AuditPolicyService.OPERATION_AUDIT_MODE_CHANGE,
                    "变更审计模式",
                    request != null ? request.getRequestURI() : "/platform/admin/audit-logs/mode",
                    auditParams,
                    "审计模式已更新为" + auditPolicyService.getModeLabel(newMode));

            return ApiResponse.success("审计模式已更新", buildAuditModeView(newMode));
        });
    }
    
    /**
     * 返回审计日志摘要及同一筛选范围的成功和失败数。
     */
    @RequestMapping(value = "", method = RequestMethod.GET)
    public HashMap<String, Object> getAllAuditLogs(@RequestParam Map<String, String> params) {
        return auditResponse("获取审计日志列表失败",
                () -> ApiResponse.success(auditLogService.getSummaryPage(buildQuery(params, true))));
    }
    
    /**
     * 根据ID获取单条审计日志
     */
    @RequestMapping(value = "/detail", method = RequestMethod.GET)
    public HashMap<String, Object> getAuditLogById(@RequestParam(PARAM_LOG_ID) String logId) {
        return auditResponse("获取审计日志详情失败", () -> {
            if (logId == null || logId.isBlank()) {
                return ApiResponse.badRequest("logId参数不能为空");
            }
            
            AuditLog log = auditLogService.findAuditLogById(logId);
            if (log == null) {
                return ApiResponse.notFound("审计日志不存在: " + logId);
            }
            
            return ApiResponse.success(log);
        });
    }
    
    @RequestMapping(value = "/statistics/user", method = RequestMethod.GET)
    public HashMap<String, Object> getUserStatistics(@RequestParam(PARAM_USER_ID) String userId) {
        return auditResponse("获取用户审计统计失败", () -> {
            if (userId == null || userId.isBlank()) {
                return ApiResponse.badRequest("userId参数不能为空");
            }
            return ApiResponse.success(auditLogService.getUserStatistics(userId));
        });
    }

    @RequestMapping(value = "/statistics/team", method = RequestMethod.GET)
    public HashMap<String, Object> getTeamStatistics(@RequestParam(PARAM_TEAM_ID) String teamId) {
        return auditResponse("获取团队审计统计失败", () -> {
            if (teamId == null || teamId.isBlank()) {
                return ApiResponse.badRequest("teamId参数不能为空");
            }
            return ApiResponse.success(auditLogService.getTeamStatistics(teamId));
        });
    }

    @RequestMapping(value = "/statistics/agent", method = RequestMethod.GET)
    public HashMap<String, Object> getAgentStatistics(@RequestParam(PARAM_AGENT_ID) String agentId) {
        return auditResponse("获取主机审计统计失败", () -> {
            if (agentId == null || agentId.isBlank()) {
                return ApiResponse.badRequest("agentId参数不能为空");
            }
            return ApiResponse.success(auditLogService.getAgentStatistics(agentId));
        });
    }

    @RequestMapping(value = "/statistics/operations", method = RequestMethod.GET)
    public HashMap<String, Object> getOperationStatistics(
            @RequestParam(value = PARAM_DAYS, required = false) Integer days) {
        return auditResponse("获取操作类型审计统计失败",
                () -> ApiResponse.success(auditLogService.getOperationStatistics(days)));
    }

    @RequestMapping(value = "/statistics/trend", method = RequestMethod.GET)
    public HashMap<String, Object> getTrendStatistics(
            @RequestParam(value = PARAM_DAYS, required = false) Integer days) {
        return auditResponse("获取审计趋势统计失败",
                () -> ApiResponse.success(auditLogService.getTrendStatistics(days)));
    }
    
    /**
     * 删除指定天数之前的旧日志
     */
    @RequestMapping(value = "/cleanup", method = RequestMethod.POST)
    public HashMap<String, Object> cleanupOldAuditLogs(@RequestBody HashMap<String, Object> params,
                                                       HttpServletRequest request) {
        return auditResponse("清理审计日志失败", () -> {
            Integer days = null;
            if (params != null && params.containsKey(PARAM_DAYS)) {
                Object daysObj = params.get(PARAM_DAYS);
                if (daysObj instanceof Integer) {
                    days = (Integer) daysObj;
                } else if (daysObj instanceof Number) {
                    days = ((Number) daysObj).intValue();
                } else if (daysObj != null) {
                    try {
                        days = Integer.parseInt(daysObj.toString());
                    } catch (NumberFormatException e) {
                        // 忽略，使用默认值
                    }
                }
            }
            
            if (days == null || days <= 0) {
                days = DEFAULT_DAYS;
            }
            
            Integer deleted = auditLogService.deleteOldAuditLogs(days);
            
            HashMap<String, Object> data = new HashMap<String, Object>();
            data.put("deleted", deleted != null ? deleted : 0);
            data.put("days", days);

            HashMap<String, Object> auditParams = new HashMap<String, Object>();
            auditParams.put("days", days);
            auditParams.put("deleted", deleted);
            recordAuditChange(request, AuditPolicyService.OPERATION_AUDIT_LOG_CLEANUP,
                    "清理审计日志",
                    request != null ? request.getRequestURI() : "/platform/admin/audit-logs/cleanup",
                    auditParams,
                    "清理审计日志，删除 " + deleted + " 条");
            
            logger.info("清理审计日志完成，删除{}天前的日志，共删除{}条", days, deleted);
            return ApiResponse.success("清理完成，共删除 " + deleted + " 条日志", data);
        });
    }

    /**
     * 删除指定审计日志。
     */
    @RequestMapping(value = "/delete", method = RequestMethod.POST)
    public HashMap<String, Object> deleteAuditLogs(@RequestBody HashMap<String, Object> params,
                                                   HttpServletRequest request) {
        return auditResponse("删除审计日志失败", () -> {
            Object rawLogIds = params == null ? null : params.get(PARAM_LOG_IDS);
            if (!(rawLogIds instanceof List<?> ids) || ids.stream().anyMatch(id -> !(id instanceof String))) {
                return ApiResponse.badRequest("logIds 必须是字符串数组");
            }
            List<String> logIds = ids.stream().map(String.class::cast).map(String::trim)
                    .filter(id -> !id.isBlank()).distinct().toList();
            if (logIds.isEmpty()) {
                return ApiResponse.badRequest("logIds参数不能为空");
            }
            Integer deleted = auditLogService.deleteAuditLogsByIds(logIds);

            HashMap<String, Object> data = new HashMap<String, Object>();
            data.put("deleted", deleted);
            data.put("logIds", logIds);

            recordAuditChange(request, AuditPolicyService.OPERATION_AUDIT_LOG_DELETE,
                    "删除审计日志", "selected:" + logIds.size(), data,
                    "删除审计日志，删除 " + deleted + " 条");

            return ApiResponse.success("删除完成，共删除 " + deleted + " 条日志", data);
        });
    }

    /**
     * 按当前筛选条件删除审计日志。
     */
    @RequestMapping(value = "/delete-filtered", method = RequestMethod.POST)
    public HashMap<String, Object> deleteFilteredAuditLogs(@RequestBody HashMap<String, Object> params,
                                                           HttpServletRequest request) {
        return auditResponse("按筛选条件删除审计日志失败", () -> {
            if (params == null || !"DELETE".equals(String.valueOf(params.get(PARAM_CONFIRM)))) {
                return ApiResponse.badRequest("confirm参数必须为DELETE");
            }
            AuditLogQuery query = buildQuery(params, false);
            if (!auditLogService.hasDeleteFilter(query)) {
                return ApiResponse.badRequest("按筛选条件删除至少需要一个筛选条件");
            }
            int deleted = auditLogService.deleteAuditLogsByFilter(query);

            HashMap<String, Object> data = new HashMap<String, Object>();
            // 保留客户端字段；matched 与 deleted 均对应实际执行删除时匹配的日志。
            data.put("matched", deleted);
            data.put("deleted", deleted);
            data.put("filter", queryToMap(query));

            recordAuditChange(request, AuditPolicyService.OPERATION_AUDIT_LOG_DELETE,
                    "按筛选条件删除审计日志", "filtered:" + deleted, data,
                    "按筛选条件删除审计日志，删除 " + deleted + " 条");

            return ApiResponse.success("删除完成，共删除 " + deleted + " 条日志", data);
        });
    }

    /**
     * 获取所有操作类型列表
     */
    @RequestMapping(value = "/operation-types", method = RequestMethod.GET)
    public HashMap<String, Object> getOperationTypes() {
        return auditResponse("获取操作类型列表失败",
                () -> ApiResponse.success(auditPolicyService.getKnownOperationTypes()));
    }

    private void recordAuditChange(HttpServletRequest request, String operationType, String operationName,
                                   String operationPath, Map<String, Object> details, String responseMessage) {
        auditRecorder.logSystemOperation(permissionService.getCurrentUser(request), operationType,
                operationName, operationPath, details, ApiResponse.CODE_SUCCESS, responseMessage,
                "SUCCESS", null, AuditLogUtil.getClientIp(request), true);
    }

    private HashMap<String, Object> auditResponse(String errorMessage, Supplier<HashMap<String, Object>> request) {
        try {
            return request.get();
        } catch (RuntimeException error) {
            throw ApiException.serverError(errorMessage, error);
        }
    }

    private HashMap<String, Object> buildAuditModeView(String mode) {
        HashMap<String, Object> data = new HashMap<String, Object>();
        data.put("mode", mode);
        data.put("label", auditPolicyService.getModeLabel(mode));
        data.put("options", auditPolicyService.getModeOptions());
        return data;
    }

    private AuditLogQuery buildQuery(Map<?, ?> params, boolean includePaging) {
        AuditLogQuery query = includePaging
                ? AuditLogQuery.page(integerParam(params, PARAM_LIMIT), integerParam(params, PARAM_OFFSET))
                : AuditLogQuery.page(AuditLogQuery.MAX_LIMIT, 0);
        query.setUserId(textParam(params, PARAM_USER_ID));
        query.setUserName(textParam(params, PARAM_USER_NAME));
        query.setAgentId(textParam(params, PARAM_AGENT_ID));
        query.setAgentName(textParam(params, PARAM_AGENT_NAME));
        query.setSessionId(textParam(params, PARAM_SESSION_ID));
        query.setOperationType(textParam(params, PARAM_OPERATION_TYPE));
        query.setStatus(textParam(params, PARAM_STATUS));
        query.setClientIp(textParam(params, PARAM_CLIENT_IP));
        query.setKeyword(textParam(params, PARAM_KEYWORD));
        query.setRemark(textParam(params, PARAM_REMARK));
        query.setStartTime(textParam(params, PARAM_START_TIME));
        query.setEndTime(textParam(params, PARAM_END_TIME));
        return query.normalized();
    }

    private HashMap<String, Object> queryToMap(AuditLogQuery query) {
        HashMap<String, Object> map = new HashMap<String, Object>();
        putIfPresent(map, PARAM_USER_ID, query.getUserId());
        putIfPresent(map, PARAM_USER_NAME, query.getUserName());
        putIfPresent(map, PARAM_AGENT_ID, query.getAgentId());
        putIfPresent(map, PARAM_AGENT_NAME, query.getAgentName());
        putIfPresent(map, PARAM_SESSION_ID, query.getSessionId());
        putIfPresent(map, PARAM_OPERATION_TYPE, query.getOperationType());
        putIfPresent(map, PARAM_STATUS, query.getStatus());
        putIfPresent(map, PARAM_CLIENT_IP, query.getClientIp());
        putIfPresent(map, PARAM_KEYWORD, query.getKeyword());
        putIfPresent(map, PARAM_REMARK, query.getRemark());
        putIfPresent(map, PARAM_START_TIME, query.getStartTime());
        putIfPresent(map, PARAM_END_TIME, query.getEndTime());
        return map;
    }

    private void putIfPresent(HashMap<String, Object> map, String key, String value) {
        if (value != null && !value.isBlank()) {
            map.put(key, value);
        }
    }

    private String textParam(Map<?, ?> params, String key) {
        Object value = params != null ? params.get(key) : null;
        return value != null ? value.toString() : null;
    }

    private Integer integerParam(Map<?, ?> params, String key) {
        String text = textParam(params, key);
        if (text == null) {
            return null;
        }
        try {
            return Integer.valueOf(text.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
