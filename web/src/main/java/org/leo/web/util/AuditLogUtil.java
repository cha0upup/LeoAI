package org.leo.web.util;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletRequest;
import org.leo.core.agent.AbstractAgentNode;
import org.leo.core.entity.User;
import org.leo.web.infrastructure.audit.WebAuditRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/** 尚未迁移的节点入口使用静态审计适配层；新调用直接注入 WebAuditRecorder。 */
@Component
public class AuditLogUtil {
    private static final Logger logger = LoggerFactory.getLogger(AuditLogUtil.class);
    private static final AtomicReference<WebAuditRecorder> legacyRecorder = new AtomicReference<>();

    private final WebAuditRecorder recorder;

    public AuditLogUtil(WebAuditRecorder recorder) {
        this.recorder = recorder;
    }

    @PostConstruct
    void registerRecorder() {
        legacyRecorder.set(recorder);
    }

    @PreDestroy
    void unregisterRecorder() {
        legacyRecorder.compareAndSet(recorder, null);
    }

    public static void logOperation(AbstractAgentNode agentNode,
                                    String operationType,
                                    String operationName,
                                    String operationPath,
                                    Map<String, Object> requestParams,
                                    Integer responseCode,
                                    String responseMessage,
                                    String status,
                                    String errorMessage,
                                    String clientIp) {
        WebAuditRecorder current = getRecorder();
        if (current != null) {
            current.logOperation(agentNode, operationType, operationName, operationPath,
                    requestParams, responseCode, responseMessage, status, errorMessage, clientIp);
        }
    }

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
        WebAuditRecorder current = getRecorder();
        if (current != null) {
            current.logSystemOperation(user, operationType, operationName, operationPath,
                    requestParams, responseCode, responseMessage, status, errorMessage, clientIp, forceRecord);
        }
    }

    /**
     * 记录成功操作的审计日志
     */
    public static void logSuccess(AbstractAgentNode agentNode,
                                  String operationType,
                                  String operationName,
                                  String operationPath,
                                  Map<String, Object> requestParams,
                                  Integer responseCode,
                                  String responseMessage,
                                  String clientIp) {
        logOperation(agentNode, operationType, operationName, operationPath,
                    requestParams, responseCode, responseMessage, "SUCCESS", null, clientIp);
    }

    /**
     * 记录失败操作的审计日志
     */
    public static void logFailure(AbstractAgentNode agentNode,
                                  String operationType,
                                  String operationName,
                                  String operationPath,
                                  Map<String, Object> requestParams,
                                  String errorMessage,
                                  String clientIp) {
        logOperation(agentNode, operationType, operationName, operationPath,
                    requestParams, null, null, "FAILED", errorMessage, clientIp);
    }

    /**
     * 记录错误操作的审计日志
     */
    public static void logError(AbstractAgentNode agentNode,
                               String operationType,
                               String operationName,
                               String operationPath,
                               Map<String, Object> requestParams,
                               String errorMessage,
                               String clientIp) {
        logOperation(agentNode, operationType, operationName, operationPath,
                    requestParams, null, null, "ERROR", errorMessage, clientIp);
    }

    private static WebAuditRecorder getRecorder() {
        WebAuditRecorder current = legacyRecorder.get();
        if (current == null) {
            logger.warn("Web 审计记录器尚未初始化");
        }
        return current;
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
