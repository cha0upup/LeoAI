package org.leo.web.infrastructure.audit;

import org.leo.core.agent.AbstractAgentNode;
import org.leo.core.entity.Agent;
import org.leo.core.entity.AuditLog;
import org.leo.core.entity.User;
import org.leo.core.util.json.JsonUtil;
import org.leo.service.audit.AuditLogService;
import org.leo.service.audit.AuditParameterSanitizer;
import org.leo.service.audit.AuditPolicyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/** 按审计策略记录 Web 操作。审计写入失败不改变已执行操作的结果。 */
@Component
public class WebAuditRecorder {
    private static final Logger logger = LoggerFactory.getLogger(WebAuditRecorder.class);

    private final AuditLogService auditLogService;
    private final AuditPolicyService auditPolicyService;

    public WebAuditRecorder(AuditLogService auditLogService, AuditPolicyService auditPolicyService) {
        this.auditLogService = auditLogService;
        this.auditPolicyService = auditPolicyService;
    }

    public void logOperation(AbstractAgentNode agentNode,
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
            if (!auditPolicyService.shouldRecord(operationType, false)) {
                return;
            }

            AuditLog auditLog = new AuditLog();
            if (agentNode != null) {
                fillActor(auditLog, agentNode.getUser());
                Agent agent = agentNode.getAgent();
                if (agent != null) {
                    auditLog.setAgentId(agent.getAgentId());
                    auditLog.setAgentName(agent.getAgentName());
                }
            }

            writeAuditLog(auditLog, operationType, operationName, operationPath,
                    requestParams, responseCode, responseMessage, status, errorMessage, clientIp);
        } catch (Exception e) {
            logger.error("记录审计日志失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 记录系统侧操作，不要求绑定 Agent 会话。
     */
    public void logSystemOperation(User user,
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
            if (!auditPolicyService.shouldRecord(operationType, forceRecord)) {
                return;
            }
            AuditLog auditLog = new AuditLog();
            fillActor(auditLog, user);
            writeAuditLog(auditLog, operationType, operationName, operationPath,
                    requestParams, responseCode, responseMessage, status, errorMessage, clientIp);
        } catch (Exception e) {
            logger.error("记录系统审计日志失败: {}", e.getMessage(), e);
        }
    }

    private static void fillActor(AuditLog auditLog, User user) {
        if (user != null) {
            auditLog.setUserId(user.getUserId());
            auditLog.setUserName(user.getUserName());
        }
    }

    private void writeAuditLog(AuditLog auditLog,
                               String operationType,
                               String operationName,
                               String operationPath,
                               Map<String, Object> requestParams,
                               Integer responseCode,
                               String responseMessage,
                               String status,
                               String errorMessage,
                               String clientIp) {
        auditLog.setOperationType(operationType);
        auditLog.setOperationName(operationName);
        auditLog.setOperationPath(operationPath);

        if (requestParams != null) {
            Object sessionId = requestParams.get("sessionId");
            if (sessionId != null) {
                auditLog.setSessionId(sessionId.toString());
            }
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

        auditLogService.insertAuditLog(auditLog);
    }
}
