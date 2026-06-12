package org.leo.web.util;

import jakarta.servlet.http.HttpServletRequest;
import org.leo.core.entity.AuditLog;
import org.leo.core.entity.Puppet;
import org.leo.core.entity.User;
import org.leo.core.puppet.impl.JavaPuppetNode;
import org.leo.service.audit.AuditLogService;
import org.leo.core.util.json.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;


import java.util.HashMap;
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
    private static ApplicationContext applicationContext;
    
    @Autowired
    public void setAuditLogService(AuditLogService auditLogService) {
        AuditLogUtil.auditLogService = auditLogService;
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
     * 记录审计日志
     * 
     * @param javaPuppetNode Puppet实体（包含用户和主机信息）
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
    public static void logOperation(JavaPuppetNode javaPuppetNode, 
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
            AuditLogService service = getAuditLogService();
            if (service == null) {
                logger.warn("AuditLogService未初始化，无法记录审计日志");
                return;
            }
            
            AuditLog auditLog = new AuditLog();
            
            // 设置用户信息
            if (javaPuppetNode != null && javaPuppetNode.getUser() != null) {
                User user = javaPuppetNode.getUser();
                auditLog.setUserId(user.getUserId());
                auditLog.setUserName(user.getUserName());
            }
            
            // 设置主机信息
            if (javaPuppetNode != null && javaPuppetNode.getPuppet() != null) {
                Puppet puppet = javaPuppetNode.getPuppet();
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
            
            // 设置操作信息
            auditLog.setOperationType(operationType);
            auditLog.setOperationName(operationName);
            auditLog.setOperationPath(operationPath);
            
            // 设置请求参数（JSON格式，敏感信息需脱敏）
            if (requestParams != null) {
                try {
                    // 脱敏处理：移除敏感信息
                    Map<String, Object> sanitizedParams = sanitizeParams(new HashMap<String, Object>(requestParams));
                    auditLog.setRequestParams(JsonUtil.toJsonString(sanitizedParams));
                } catch (Exception e) {
                    logger.warn("序列化请求参数失败: {}", e.getMessage());
                    auditLog.setRequestParams(requestParams.toString());
                }
            }
            
            // 设置响应信息
            auditLog.setResponseCode(responseCode);
            auditLog.setResponseMessage(responseMessage);
            auditLog.setStatus(status != null ? status : "SUCCESS");
            auditLog.setErrorMessage(errorMessage);
            auditLog.setClientIp(clientIp);
            
            // 记录日志（避免影响主流程性能，可以考虑异步处理）
            service.insertAuditLog(auditLog);
            
        } catch (Exception e) {
            logger.error("记录审计日志失败: {}", e.getMessage(), e);
        }
    }
    
    /**
     * 记录成功操作的审计日志
     */
    public static void logSuccess(JavaPuppetNode javaPuppetNode,
                                  String operationType,
                                  String operationName,
                                  String operationPath,
                                  Map<String, Object> requestParams,
                                  Integer responseCode,
                                  String responseMessage,
                                  String clientIp) {
        logOperation(javaPuppetNode, operationType, operationName, operationPath, 
                    requestParams, responseCode, responseMessage, "SUCCESS", null, clientIp);
    }
    
    /**
     * 记录失败操作的审计日志
     */
    public static void logFailure(JavaPuppetNode javaPuppetNode,
                                  String operationType,
                                  String operationName,
                                  String operationPath,
                                  Map<String, Object> requestParams,
                                  String errorMessage,
                                  String clientIp) {
        logOperation(javaPuppetNode, operationType, operationName, operationPath, 
                    requestParams, null, null, "FAILED", errorMessage, clientIp);
    }
    
    /**
     * 记录错误操作的审计日志
     */
    public static void logError(JavaPuppetNode javaPuppetNode,
                               String operationType,
                               String operationName,
                               String operationPath,
                               Map<String, Object> requestParams,
                               String errorMessage,
                               String clientIp) {
        logOperation(javaPuppetNode, operationType, operationName, operationPath, 
                    requestParams, null, null, "ERROR", errorMessage, clientIp);
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
    
    /**
     * 脱敏处理：移除敏感信息
     */
    private static Map<String, Object> sanitizeParams(Map<String, Object> params) {
        if (params == null) {
            return new HashMap<>();
        }
        Map<String, Object> sanitized = new HashMap<String, Object>(params);
        
        // 需要脱敏的字段名
        String[] sensitiveFields = {"password", "pwd", "passwd", "secret", "token", "key", "credential"};
        
        for (String field : sensitiveFields) {
            if (sanitized.containsKey(field)) {
                sanitized.put(field, "***");
            }
        }
        
        return sanitized;
    }
}

