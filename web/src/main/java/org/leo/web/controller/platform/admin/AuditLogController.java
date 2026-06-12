package org.leo.web.controller.platform.admin;

import org.leo.core.entity.AuditLog;
import org.leo.service.audit.AuditLogService;
import org.leo.core.util.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

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
    
    @Autowired
    public AuditLogController(AuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }
    
    /**
     * 获取所有审计日志（分页）
     */
    @RequestMapping(value = "", method = RequestMethod.GET)
    public HashMap<String, Object> getAllAuditLogs(
            @RequestParam(value = PARAM_LIMIT, required = false) Integer limit,
            @RequestParam(value = PARAM_OFFSET, required = false) Integer offset) {
        try {
            // 参数验证和默认值设置
            if (limit == null || limit <= 0) {
                limit = DEFAULT_LIMIT;
            }
            if (limit > MAX_LIMIT) {
                limit = MAX_LIMIT;
            }
            if (offset == null || offset < 0) {
                offset = DEFAULT_OFFSET;
            }
            
            List<AuditLog> logs = auditLogService.findAllAuditLogs(limit, offset);
            Integer total = auditLogService.countAllAuditLogs();
            
            HashMap<String, Object> data = new HashMap<String, Object>();
            data.put("logs", logs != null ? logs : new ArrayList<AuditLog>());
            data.put("total", total != null ? total : 0);
            data.put("limit", limit);
            data.put("offset", offset);
            
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
     * 根据用户ID查询审计日志
     */
    @RequestMapping(value = "/user", method = RequestMethod.GET)
    public HashMap<String, Object> getAuditLogsByUserId(
            @RequestParam(PARAM_USER_ID) String userId,
            @RequestParam(value = PARAM_LIMIT, required = false) Integer limit,
            @RequestParam(value = PARAM_OFFSET, required = false) Integer offset) {
        try {
            if (userId == null || userId.isBlank()) {
                return ApiResponse.badRequest("userId参数不能为空");
            }
            
            // 参数验证和默认值设置
            if (limit == null || limit <= 0) {
                limit = DEFAULT_LIMIT;
            }
            if (limit > MAX_LIMIT) {
                limit = MAX_LIMIT;
            }
            if (offset == null || offset < 0) {
                offset = DEFAULT_OFFSET;
            }
            
            List<AuditLog> logs = auditLogService.findAuditLogsByUserId(userId, limit, offset);
            Integer total = auditLogService.countAuditLogsByUserId(userId);
            
            HashMap<String, Object> data = new HashMap<String, Object>();
            data.put("logs", logs != null ? logs : new ArrayList<AuditLog>());
            data.put("total", total != null ? total : 0);
            data.put("limit", limit);
            data.put("offset", offset);
            
            return ApiResponse.success(data);
        } catch (Exception e) {
            logger.error("根据用户ID查询审计日志失败: {}", e.getMessage(), e);
            return ApiResponse.error("根据用户ID查询审计日志失败: " + e.getMessage());
        }
    }
    
    /**
     * 根据主机ID查询审计日志
     */
    @RequestMapping(value = "/puppet", method = RequestMethod.GET)
    public HashMap<String, Object> getAuditLogsByPuppetId(
            @RequestParam(PARAM_PUPPET_ID) String puppetId,
            @RequestParam(value = PARAM_LIMIT, required = false) Integer limit,
            @RequestParam(value = PARAM_OFFSET, required = false) Integer offset) {
        try {
            if (puppetId == null || puppetId.isBlank()) {
                return ApiResponse.badRequest("puppetId参数不能为空");
            }
            
            // 参数验证和默认值设置
            if (limit == null || limit <= 0) {
                limit = DEFAULT_LIMIT;
            }
            if (limit > MAX_LIMIT) {
                limit = MAX_LIMIT;
            }
            if (offset == null || offset < 0) {
                offset = DEFAULT_OFFSET;
            }
            
            List<AuditLog> logs = auditLogService.findAuditLogsByPuppetId(puppetId, limit, offset);
            Integer total = auditLogService.countAuditLogsByPuppetId(puppetId);
            
            HashMap<String, Object> data = new HashMap<String, Object>();
            data.put("logs", logs != null ? logs : new ArrayList<AuditLog>());
            data.put("total", total != null ? total : 0);
            data.put("limit", limit);
            data.put("offset", offset);
            
            return ApiResponse.success(data);
        } catch (Exception e) {
            logger.error("根据主机ID查询审计日志失败: {}", e.getMessage(), e);
            return ApiResponse.error("根据主机ID查询审计日志失败: " + e.getMessage());
        }
    }
    
    /**
     * 根据操作类型查询审计日志
     */
    @RequestMapping(value = "/operation-type", method = RequestMethod.GET)
    public HashMap<String, Object> getAuditLogsByOperationType(
            @RequestParam(PARAM_OPERATION_TYPE) String operationType,
            @RequestParam(value = PARAM_LIMIT, required = false) Integer limit,
            @RequestParam(value = PARAM_OFFSET, required = false) Integer offset) {
        try {
            if (operationType == null || operationType.isBlank()) {
                return ApiResponse.badRequest("operationType参数不能为空");
            }
            
            // 参数验证和默认值设置
            if (limit == null || limit <= 0) {
                limit = DEFAULT_LIMIT;
            }
            if (limit > MAX_LIMIT) {
                limit = MAX_LIMIT;
            }
            if (offset == null || offset < 0) {
                offset = DEFAULT_OFFSET;
            }
            
            List<AuditLog> logs = auditLogService.findAuditLogsByOperationType(operationType, limit, offset);
            
            HashMap<String, Object> data = new HashMap<String, Object>();
            data.put("logs", logs != null ? logs : new ArrayList<AuditLog>());
            data.put("limit", limit);
            data.put("offset", offset);
            
            return ApiResponse.success(data);
        } catch (Exception e) {
            logger.error("根据操作类型查询审计日志失败: {}", e.getMessage(), e);
            return ApiResponse.error("根据操作类型查询审计日志失败: " + e.getMessage());
        }
    }
    
    /**
     * 统计审计日志总数
     */
    @RequestMapping(value = "/count", method = RequestMethod.GET)
    public HashMap<String, Object> getAuditLogCount(
            @RequestParam(value = PARAM_USER_ID, required = false) String userId,
            @RequestParam(value = PARAM_PUPPET_ID, required = false) String puppetId) {
        try {
            HashMap<String, Object> data = new HashMap<String, Object>();
            
            if (userId != null && !userId.isBlank()) {
                Integer count = auditLogService.countAuditLogsByUserId(userId);
                data.put("userId", userId);
                data.put("count", count != null ? count : 0);
            } else if (puppetId != null && !puppetId.isBlank()) {
                Integer count = auditLogService.countAuditLogsByPuppetId(puppetId);
                data.put("puppetId", puppetId);
                data.put("count", count != null ? count : 0);
            } else {
                Integer count = auditLogService.countAllAuditLogs();
                data.put("count", count != null ? count : 0);
            }
            
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
    public HashMap<String, Object> cleanupOldAuditLogs(@RequestBody HashMap<String, Object> params) {
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
            
            logger.info("清理审计日志完成，删除{}天前的日志，共删除{}条", days, deleted);
            return ApiResponse.success("清理完成，共删除 " + deleted + " 条日志", data);
        } catch (Exception e) {
            logger.error("清理审计日志失败: {}", e.getMessage(), e);
            return ApiResponse.error("清理审计日志失败: " + e.getMessage());
        }
    }
    
    /**
     * 获取所有操作类型列表
     */
    @RequestMapping(value = "/operation-types", method = RequestMethod.GET)
    public HashMap<String, Object> getOperationTypes() {
        try {
            List<String> operationTypes = new ArrayList<String>();
            operationTypes.add("FILE_LIST");
            operationTypes.add("FILE_LIST_ROOT");
            operationTypes.add("FILE_EDIT");
            operationTypes.add("FILE_NEW");
            operationTypes.add("FILE_MOVE");
            operationTypes.add("FILE_COPY");
            operationTypes.add("FILE_DELETE");
            operationTypes.add("FILE_NEW_DIR");
            operationTypes.add("FILE_COMPRESS");
            operationTypes.add("FILE_DECOMPRESS");
            operationTypes.add("FILE_MD5");
            operationTypes.add("COMMAND_EXEC");
            operationTypes.add("COMPONENT_INVOKE");
            operationTypes.add("SQL_EXEC");
            operationTypes.add("SCREENSHOT");
            operationTypes.add("PLUGIN_INVOKE");
            operationTypes.add("PROXY_START");
            operationTypes.add("PROXY_STOP");
            
            return ApiResponse.success(operationTypes);
        } catch (Exception e) {
            logger.error("获取操作类型列表失败: {}", e.getMessage(), e);
            return ApiResponse.error("获取操作类型列表失败: " + e.getMessage());
        }
    }
}
