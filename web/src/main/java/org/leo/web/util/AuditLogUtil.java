package org.leo.web.util;

import jakarta.servlet.http.HttpServletRequest;
import org.leo.core.entity.AuditLog;
import org.leo.core.entity.Puppet;
import org.leo.core.entity.User;
import org.leo.core.puppet.AbstractPuppetNode;
import org.leo.service.audit.AuditLogService;
import org.leo.service.audit.AuditPolicyService;
import org.leo.service.audit.AuditParameterSanitizer;
import org.leo.core.util.json.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;


import java.util.Map;

/**
 * 审计日志工具类
 * 提供便捷的审计日志记录方法
 * 
 * @author LeoSpring
 * @version 2.1
 */
@Component
public class AuditLogUtil {
    private static final Logger logger = LoggerFactory.getLogger(AuditLogUtil.class);
    
    private static AuditLogService auditLogService;
    private static AuditPolicyService auditPolicyService;
    private static ApplicationContext applicationContext;
    
    @Autowired
    public void setAuditLogService(AuditLogService auditLogService) {
        AuditLogUtil.auditLogService = auditLogService;
    }

    @Autowired
    public void setAuditPolicyService(AuditPolicyService auditPolicyService) {
        AuditLogUtil.auditPolicyService = auditPolicyService;
    }
    
    @Autowired
    public void setApplicationContext(ApplicationContext applicationContext) {
        AuditLogUtil.applicationContext = applicationContext;
    }
    
    /**
     * 获取AuditLogService实例
     */
    private static AuditLogService getAuditLogService() {
        if (auditLogService == null && applicationContext != null) {
            try {
                auditLogService = applicationContext.getBean(AuditLogService.class);
            } catch (Exception e) {
                logger.warn("无法获取AuditLogService实例: {}", e.getMessage());
            }
        }
        return auditLogService;
    }

    /**
     * 获取AuditPolicyService实例
     */
    private static AuditPolicyService getAuditPolicyService() {
        if (auditPolicyService == null && applicationContext != null) {
            try {
                auditPolicyService = applicationContext.getBean(AuditPolicyService.class);
            } catch (Exception e) {
                logger.warn("无法获取AuditPolicyService实例: {}", e.getMessage());
            }
        }
        return auditPolicyService;
    }
    
    /**
     * 记录审计日志
     * 
     * @param puppetNode Puppet实体（包含用户和主机信息）
     * @param operationType 操作类型
     * @param operationName 操作名称
     * @param operationPath 操作路径
     * @param requestParams 请求参数
     * @param responseCode 响应码
     * @param responseMessage 响应消息
     * @param status 状态（SUCCESS, FAILED, ERROR）
     * @param errorMessage 错误信息
     * @param clientIp 客户端IP
     */
    public static void logOperation(AbstractPuppetNode puppetNode,
                                    String operationType,
                                    String operationName,
                                    String operationPath,
                                    Map<String, Object> requestParams,
                                    Integer responseCode,
                                    String responseMessage,
                                    String status,
                                    String errorMessage,
                                    String clientIp) {
        try {
            if (!shouldRecord(operationType, false)) {
                return;
            }
            
            AuditLog auditLog = new AuditLog();
            
            // 设置用户信息
            if (puppetNode != null && puppetNode.getUser() != null) {
                User user = puppetNode.getUser();
                auditLog.setUserId(user.getUserId());
                auditLog.setUserName(user.getUserName());
            }
            
            // 设置主机信息
            if (puppetNode != null && puppetNode.getPuppet() != null) {
                Puppet puppet = puppetNode.getPuppet();
                auditLog.setPuppetId(puppet.getPuppetId());
                auditLog.setPuppetName(puppet.getPuppetName());
            }
            
            // 设置会话ID（从请求参数中提取）
            if (requestParams != null && requestParams.containsKey("sessionId")) {
                Object sessionIdObj = requestParams.get("sessionId");
                if (sessionIdObj != null) {
                    auditLog.setSessionId(sessionIdObj.toString());
                }
            }

            writeAuditLog(auditLog, operationType, operationName, operationPath,
                    requestParams, responseCode, responseMessage, status, errorMessage, clientIp);
            
        } catch (Exception e) {
            logger.error("记录审计日志失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 记录系统侧操作，不要求绑定 Puppet 会话。
     */
    public static void logSystemOperation(User user,
                                          String operationType,
                                          String operationName,
                                          String operationPath,
                                          Map<String, Object> requestParams,
                                          Integer responseCode,
                                          String responseMessage,
                                          String status,
                                          String errorMessage,
                                          String clientIp,
                                          boolean forceRecord) {
        try {
            if (!shouldRecord(operationType, forceRecord)) {
                return;
            }
            AuditLog auditLog = new AuditLog();
            if (user != null) {
                auditLog.setUserId(user.getUserId());
                auditLog.setUserName(user.getUserName());
            }
            if (requestParams != null && requestParams.containsKey("sessionId")) {
                Object sessionIdObj = requestParams.get("sessionId");
                if (sessionIdObj != null) {
                    auditLog.setSessionId(sessionIdObj.toString());
                }
            }
            writeAuditLog(auditLog, operationType, operationName, operationPath,
                    requestParams, responseCode, responseMessage, status, errorMessage, clientIp);
        } catch (Exception e) {
            logger.error("记录系统审计日志失败: {}", e.getMessage(), e);
        }
    }
    
    /**
     * 记录成功操作的审计日志
     */
    public static void logSuccess(AbstractPuppetNode puppetNode,
                                  String operationType,
                                  String operationName,
                                  String operationPath,
                                  Map<String, Object> requestParams,
                                  Integer responseCode,
                                  String responseMessage,
                                  String clientIp) {
        logOperation(puppetNode, operationType, operationName, operationPath,
                    requestParams, responseCode, responseMessage, "SUCCESS", null, clientIp);
    }
    
    /**
     * 记录失败操作的审计日志
     */
    public static void logFailure(AbstractPuppetNode puppetNode,
                                  String operationType,
                                  String operationName,
                                  String operationPath,
                                  Map<String, Object> requestParams,
                                  String errorMessage,
                                  String clientIp) {
        logOperation(puppetNode, operationType, operationName, operationPath,
                    requestParams, null, null, "FAILED", errorMessage, clientIp);
    }
    
    /**
     * 记录错误操作的审计日志
     */
    public static void logError(AbstractPuppetNode puppetNode,
                               String operationType,
                               String operationName,
                               String operationPath,
                               Map<String, Object> requestParams,
                               String errorMessage,
                               String clientIp) {
        logOperation(puppetNode, operationType, operationName, operationPath,
                    requestParams, null, null, "ERROR", errorMessage, clientIp);
    }

    private static boolean shouldRecord(String operationType, boolean forceRecord) {
        AuditPolicyService service = getAuditPolicyService();
        if (service == null) {
            return true;
        }
        return service.shouldRecord(operationType, forceRecord);
    }

    private static void writeAuditLog(AuditLog auditLog,
                                      String operationType,
                                      String operationName,
                                      String operationPath,
                                      Map<String, Object> requestParams,
                                      Integer responseCode,
                                      String responseMessage,
                                      String status,
                                      String errorMessage,
                                      String clientIp) {
        AuditLogService service = getAuditLogService();
        if (service == null) {
            logger.warn("AuditLogService未初始化，无法记录审计日志");
            return;
        }

        auditLog.setOperationType(operationType);
        auditLog.setOperationName(operationName);
        auditLog.setOperationPath(operationPath);

        if (requestParams != null) {
            Map<String, Object> sanitizedParams = AuditParameterSanitizer.sanitize(requestParams);
            try {
                auditLog.setRequestParams(JsonUtil.toJsonString(sanitizedParams));
            } catch (Exception e) {
                logger.warn("序列化请求参数失败: {}", e.getMessage());
                auditLog.setRequestParams(sanitizedParams.toString());
            }
        }

        auditLog.setResponseCode(responseCode);
        auditLog.setResponseMessage(responseMessage);
        auditLog.setStatus(status != null ? status : "SUCCESS");
        auditLog.setErrorMessage(errorMessage);
        auditLog.setClientIp(clientIp);

        service.insertAuditLog(auditLog);
    }
    
    /**
     * 从HttpServletRequest获取客户端IP
     */
    public static String getClientIp(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("Proxy-Client-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("WL-Proxy-Client-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("HTTP_CLIENT_IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("HTTP_X_FORWARDED_FOR");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        return ip;
    }
    
    /**
     * 从RequestContextHolder获取客户端IP
     */
    public static String getClientIp() {
        try {
            ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attributes != null) {
                HttpServletRequest request = attributes.getRequest();
                return getClientIp(request);
            }
        } catch (Exception e) {
            logger.debug("获取客户端IP失败: {}", e.getMessage());
        }
        return null;
    }
    
}
