package org.leo.web.controller.platform.admin;

import jakarta.servlet.http.HttpServletRequest;
import org.leo.core.entity.AuditLog;
import org.leo.core.entity.AuditLogQuery;
import org.leo.core.entity.User;
import org.leo.service.audit.AuditLogService;
import org.leo.service.audit.AuditPolicyService;
import org.leo.core.util.ApiResponse;
import org.leo.web.security.PermissionService;
import org.leo.web.util.AuditLogUtil;
import org.leo.web.util.ControllerUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
    private static final String PARAM_PUPPET_ID = "puppetId";
    private static final String PARAM_OPERATION_TYPE = "operationType";
    private static final String PARAM_USER_NAME = "userName";
    private static final String PARAM_PUPPET_NAME = "puppetName";
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
    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 1000;
    private static final int DEFAULT_OFFSET = 0;
    private static final int DEFAULT_DAYS = 30;
    
    private final AuditLogService auditLogService;
    private final AuditPolicyService auditPolicyService;
    private final PermissionService permissionService;
    
    public AuditLogController(AuditLogService auditLogService,
                              AuditPolicyService auditPolicyService,
                              PermissionService permissionService) {
        this.auditLogService = auditLogService;
        this.auditPolicyService = auditPolicyService;
        this.permissionService = permissionService;
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
        try {
            String mode = auditPolicyService.getMode();
            HashMap<String, Object> data = buildAuditModeView(mode);
            return ApiResponse.success(data);
        } catch (Exception e) {
            logger.error("获取审计模式失败: {}", e.getMessage(), e);
            return ApiResponse.error("获取审计模式失败: " + e.getMessage());
        }
    }

    /**
     * 更新审计记录模式。
     */
    @RequestMapping(value = "/mode", method = RequestMethod.POST)
    public HashMap<String, Object> updateAuditMode(@RequestBody HashMap<String, Object> params,
                                                   HttpServletRequest request) {
        try {
            if (params == null || params.get("mode") == null || params.get("mode").toString().isBlank()) {
                return ApiResponse.badRequest("mode参数不能为空");
            }
            String oldMode = auditPolicyService.getMode();
            String newMode = auditPolicyService.updateMode(params.get("mode").toString());

            HashMap<String, Object> auditParams = new HashMap<String, Object>();
            auditParams.put("oldMode", oldMode);
            auditParams.put("newMode", newMode);
            auditParams.put("newModeLabel", auditPolicyService.getModeLabel(newMode));

            User currentUser = ControllerUtil.getCurrentUser(request);
            AuditLogUtil.logSystemOperation(
                    currentUser,
                    AuditPolicyService.OPERATION_AUDIT_MODE_CHANGE,
                    "变更审计模式",
                    request != null ? request.getRequestURI() : "/platform/admin/audit-logs/mode",
                    auditParams,
                    ApiResponse.CODE_SUCCESS,
                    "审计模式已更新为" + auditPolicyService.getModeLabel(newMode),
                    "SUCCESS",
                    null,
                    AuditLogUtil.getClientIp(request),
                    true
            );

            return ApiResponse.success("审计模式已更新", buildAuditModeView(newMode));
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        } catch (Exception e) {
            logger.error("更新审计模式失败: {}", e.getMessage(), e);
            return ApiResponse.error("更新审计模式失败: " + e.getMessage());
        }
    }
    
    /**
     * 获取所有审计日志（分页）
     */
    @RequestMapping(value = "", method = RequestMethod.GET)
    public HashMap<String, Object> getAllAuditLogs(@RequestParam Map<String, String> params) {
        try {
            AuditLogQuery query = buildQuery(params, true);
            List<AuditLog> logs = auditLogService.searchAuditLogs(query);
            Integer total = auditLogService.countAuditLogs(query);
            
            HashMap<String, Object> data = new HashMap<String, Object>();
            data.put("logs", logs != null ? logs : new ArrayList<AuditLog>());
            data.put("total", total != null ? total : 0);
            data.put("limit", query.getLimit());
            data.put("offset", query.getOffset());
            
            return ApiResponse.success(data);
        } catch (Exception e) {
            logger.error("获取审计日志列表失败: {}", e.getMessage(), e);
            return ApiResponse.error("获取审计日志列表失败: " + e.getMessage());
        }
    }
    
    /**
     * 根据ID获取单条审计日志
     */
    @RequestMapping(value = "/detail", method = RequestMethod.GET)
    public HashMap<String, Object> getAuditLogById(@RequestParam(PARAM_LOG_ID) String logId) {
        try {
            if (logId == null || logId.isBlank()) {
                return ApiResponse.badRequest("logId参数不能为空");
            }
            
            AuditLog log = auditLogService.findAuditLogById(logId);
            if (log == null) {
                return ApiResponse.notFound("审计日志不存在: " + logId);
            }
            
            return ApiResponse.success(log);
        } catch (Exception e) {
            logger.error("获取审计日志详情失败: {}", e.getMessage(), e);
            return ApiResponse.error("获取审计日志详情失败: " + e.getMessage());
        }
    }
    
    /**
     * 统计审计日志总数
     */
    @RequestMapping(value = "/count", method = RequestMethod.GET)
    public HashMap<String, Object> getAuditLogCount(@RequestParam Map<String, String> params) {
        try {
            AuditLogQuery query = buildQuery(params, false);
            HashMap<String, Object> data = new HashMap<String, Object>();
            data.put("count", auditLogService.countAuditLogs(query));
            
            return ApiResponse.success(data);
        } catch (Exception e) {
            logger.error("统计审计日志数量失败: {}", e.getMessage(), e);
            return ApiResponse.error("统计审计日志数量失败: " + e.getMessage());
        }
    }

    @RequestMapping(value = "/statistics/user", method = RequestMethod.GET)
    public HashMap<String, Object> getUserStatistics(@RequestParam(PARAM_USER_ID) String userId) {
        try {
            if (userId == null || userId.isBlank()) {
                return ApiResponse.badRequest("userId参数不能为空");
            }
            return ApiResponse.success(auditLogService.getUserStatistics(userId));
        } catch (Exception e) {
            logger.error("获取用户审计统计失败: {}", e.getMessage(), e);
            return ApiResponse.error("获取用户审计统计失败: " + e.getMessage());
        }
    }

    @RequestMapping(value = "/statistics/team", method = RequestMethod.GET)
    public HashMap<String, Object> getTeamStatistics(@RequestParam(PARAM_TEAM_ID) String teamId) {
        try {
            if (teamId == null || teamId.isBlank()) {
                return ApiResponse.badRequest("teamId参数不能为空");
            }
            return ApiResponse.success(auditLogService.getTeamStatistics(teamId));
        } catch (Exception e) {
            logger.error("获取团队审计统计失败: {}", e.getMessage(), e);
            return ApiResponse.error("获取团队审计统计失败: " + e.getMessage());
        }
    }

    @RequestMapping(value = "/statistics/puppet", method = RequestMethod.GET)
    public HashMap<String, Object> getPuppetStatistics(@RequestParam(PARAM_PUPPET_ID) String puppetId) {
        try {
            if (puppetId == null || puppetId.isBlank()) {
                return ApiResponse.badRequest("puppetId参数不能为空");
            }
            return ApiResponse.success(auditLogService.getPuppetStatistics(puppetId));
        } catch (Exception e) {
            logger.error("获取主机审计统计失败: {}", e.getMessage(), e);
            return ApiResponse.error("获取主机审计统计失败: " + e.getMessage());
        }
    }

    @RequestMapping(value = "/statistics/operations", method = RequestMethod.GET)
    public HashMap<String, Object> getOperationStatistics() {
        try {
            return ApiResponse.success(auditLogService.getOperationStatistics());
        } catch (Exception e) {
            logger.error("获取操作类型审计统计失败: {}", e.getMessage(), e);
            return ApiResponse.error("获取操作类型审计统计失败: " + e.getMessage());
        }
    }

    @RequestMapping(value = "/statistics/trend", method = RequestMethod.GET)
    public HashMap<String, Object> getTrendStatistics(
            @RequestParam(value = PARAM_DAYS, required = false) Integer days) {
        try {
            return ApiResponse.success(auditLogService.getTrendStatistics(days));
        } catch (Exception e) {
            logger.error("获取审计趋势统计失败: {}", e.getMessage(), e);
            return ApiResponse.error("获取审计趋势统计失败: " + e.getMessage());
        }
    }
    
    /**
     * 删除指定天数之前的旧日志
     */
    @RequestMapping(value = "/cleanup", method = RequestMethod.POST)
    public HashMap<String, Object> cleanupOldAuditLogs(@RequestBody HashMap<String, Object> params,
                                                       HttpServletRequest request) {
        try {
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
            AuditLogUtil.logSystemOperation(
                    ControllerUtil.getCurrentUser(request),
                    AuditPolicyService.OPERATION_AUDIT_LOG_CLEANUP,
                    "清理审计日志",
                    request != null ? request.getRequestURI() : "/platform/admin/audit-logs/cleanup",
                    auditParams,
                    ApiResponse.CODE_SUCCESS,
                    "清理审计日志，删除 " + deleted + " 条",
                    "SUCCESS",
                    null,
                    AuditLogUtil.getClientIp(request),
                    true
            );
            
            logger.info("清理审计日志完成，删除{}天前的日志，共删除{}条", days, deleted);
            return ApiResponse.success("清理完成，共删除 " + deleted + " 条日志", data);
        } catch (Exception e) {
            logger.error("清理审计日志失败: {}", e.getMessage(), e);
            return ApiResponse.error("清理审计日志失败: " + e.getMessage());
        }
    }

    /**
     * 删除指定审计日志。
     */
    @RequestMapping(value = "/delete", method = RequestMethod.POST)
    public HashMap<String, Object> deleteAuditLogs(@RequestBody HashMap<String, Object> params,
                                                   HttpServletRequest request) {
        try {
            List<String> logIds = parseLogIds(params);
            if (logIds.isEmpty()) {
                return ApiResponse.badRequest("logIds参数不能为空");
            }
            Integer deleted = auditLogService.deleteAuditLogsByIds(logIds);

            HashMap<String, Object> data = new HashMap<String, Object>();
            data.put("deleted", deleted);
            data.put("logIds", logIds);

            HashMap<String, Object> auditParams = new HashMap<String, Object>();
            auditParams.put("logIds", logIds);
            auditParams.put("deleted", deleted);
            AuditLogUtil.logSystemOperation(
                    ControllerUtil.getCurrentUser(request),
                    AuditPolicyService.OPERATION_AUDIT_LOG_DELETE,
                    "删除审计日志",
                    "selected:" + logIds.size(),
                    auditParams,
                    ApiResponse.CODE_SUCCESS,
                    "删除审计日志，删除 " + deleted + " 条",
                    "SUCCESS",
                    null,
                    AuditLogUtil.getClientIp(request),
                    true
            );

            return ApiResponse.success("删除完成，共删除 " + deleted + " 条日志", data);
        } catch (Exception e) {
            logger.error("删除审计日志失败: {}", e.getMessage(), e);
            return ApiResponse.error("删除审计日志失败: " + e.getMessage());
        }
    }

    /**
     * 按当前筛选条件删除审计日志。
     */
    @RequestMapping(value = "/delete-filtered", method = RequestMethod.POST)
    public HashMap<String, Object> deleteFilteredAuditLogs(@RequestBody HashMap<String, Object> params,
                                                           HttpServletRequest request) {
        try {
            if (params == null || !"DELETE".equals(String.valueOf(params.get(PARAM_CONFIRM)))) {
                return ApiResponse.badRequest("confirm参数必须为DELETE");
            }
            AuditLogQuery query = buildQuery(params, false);
            if (!auditLogService.hasDeleteFilter(query)) {
                return ApiResponse.badRequest("按筛选条件删除至少需要一个筛选条件");
            }
            Integer matched = auditLogService.countAuditLogs(query);
            Integer deleted = auditLogService.deleteAuditLogsByFilter(query);

            HashMap<String, Object> data = new HashMap<String, Object>();
            data.put("matched", matched);
            data.put("deleted", deleted);
            data.put("filter", queryToMap(query));

            HashMap<String, Object> auditParams = new HashMap<String, Object>();
            auditParams.put("filter", queryToMap(query));
            auditParams.put("matched", matched);
            auditParams.put("deleted", deleted);
            AuditLogUtil.logSystemOperation(
                    ControllerUtil.getCurrentUser(request),
                    AuditPolicyService.OPERATION_AUDIT_LOG_DELETE,
                    "按筛选条件删除审计日志",
                    "filtered:" + deleted,
                    auditParams,
                    ApiResponse.CODE_SUCCESS,
                    "按筛选条件删除审计日志，删除 " + deleted + " 条",
                    "SUCCESS",
                    null,
                    AuditLogUtil.getClientIp(request),
                    true
            );

            return ApiResponse.success("删除完成，共删除 " + deleted + " 条日志", data);
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        } catch (Exception e) {
            logger.error("按筛选条件删除审计日志失败: {}", e.getMessage(), e);
            return ApiResponse.error("按筛选条件删除审计日志失败: " + e.getMessage());
        }
    }
    
    /**
     * 获取所有操作类型列表
     */
    @RequestMapping(value = "/operation-types", method = RequestMethod.GET)
    public HashMap<String, Object> getOperationTypes() {
        try {
            return ApiResponse.success(auditPolicyService.getKnownOperationTypes());
        } catch (Exception e) {
            logger.error("获取操作类型列表失败: {}", e.getMessage(), e);
            return ApiResponse.error("获取操作类型列表失败: " + e.getMessage());
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
        AuditLogQuery query = new AuditLogQuery();
        query.setUserId(textParam(params, PARAM_USER_ID));
        query.setUserName(textParam(params, PARAM_USER_NAME));
        query.setPuppetId(textParam(params, PARAM_PUPPET_ID));
        query.setPuppetName(textParam(params, PARAM_PUPPET_NAME));
        query.setSessionId(textParam(params, PARAM_SESSION_ID));
        query.setOperationType(textParam(params, PARAM_OPERATION_TYPE));
        query.setStatus(textParam(params, PARAM_STATUS));
        query.setClientIp(textParam(params, PARAM_CLIENT_IP));
        query.setKeyword(textParam(params, PARAM_KEYWORD));
        query.setRemark(textParam(params, PARAM_REMARK));
        query.setStartTime(textParam(params, PARAM_START_TIME));
        query.setEndTime(textParam(params, PARAM_END_TIME));
        query.setLimit(includePaging ? normalizeLimit(integerParam(params, PARAM_LIMIT)) : MAX_LIMIT);
        query.setOffset(includePaging ? normalizeOffset(integerParam(params, PARAM_OFFSET)) : 0);
        return query;
    }

    private HashMap<String, Object> queryToMap(AuditLogQuery query) {
        HashMap<String, Object> map = new HashMap<String, Object>();
        putIfPresent(map, PARAM_USER_ID, query.getUserId());
        putIfPresent(map, PARAM_USER_NAME, query.getUserName());
        putIfPresent(map, PARAM_PUPPET_ID, query.getPuppetId());
        putIfPresent(map, PARAM_PUPPET_NAME, query.getPuppetName());
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
        if (params == null || !params.containsKey(key)) {
            return null;
        }
        Object value = params.get(key);
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        return text.isBlank() ? null : text;
    }

    private Integer integerParam(Map<?, ?> params, String key) {
        String text = textParam(params, key);
        if (text == null) {
            return null;
        }
        try {
            return Integer.valueOf(Integer.parseInt(text));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private int normalizeLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    private int normalizeOffset(Integer offset) {
        return offset == null || offset < 0 ? DEFAULT_OFFSET : offset;
    }

    private List<String> parseLogIds(HashMap<String, Object> params) {
        List<String> ids = new ArrayList<String>();
        if (params == null || !params.containsKey(PARAM_LOG_IDS)) {
            return ids;
        }
        Object raw = params.get(PARAM_LOG_IDS);
        if (raw instanceof List<?>) {
            for (Object item : (List<?>) raw) {
                appendLogId(ids, item);
            }
        } else if (raw instanceof String) {
            String[] parts = ((String) raw).split(",");
            for (String part : parts) {
                appendLogId(ids, part);
            }
        } else {
            appendLogId(ids, raw);
        }
        return ids.stream().distinct().toList();
    }

    private void appendLogId(List<String> ids, Object value) {
        if (value == null) {
            return;
        }
        String text = value.toString().trim();
        if (!text.isBlank()) {
            ids.add(text);
        }
    }
}
