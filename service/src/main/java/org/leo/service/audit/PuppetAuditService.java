package org.leo.service.audit;

import org.leo.core.entity.AuditLog;
import org.leo.core.entity.Puppet;
import org.leo.core.entity.User;
import org.leo.core.puppet.AbstractPuppetNode;
import org.leo.core.session.PuppetNodeSession;
import org.leo.core.session.PuppetNodeSessionContainer;
import org.leo.core.util.json.JsonUtil;
import org.leo.service.user.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class PuppetAuditService {

    private static final Logger logger = LoggerFactory.getLogger(PuppetAuditService.class);
    private final AuditLogService auditLogService;
    private final AuditPolicyService auditPolicyService;
    private final UserService userService;

    public PuppetAuditService(AuditLogService auditLogService,
                              AuditPolicyService auditPolicyService,
                              UserService userService) {
        this.auditLogService = auditLogService;
        this.auditPolicyService = auditPolicyService;
        this.userService = userService;
    }

    public void logSuccess(String sessionId,
                           AbstractPuppetNode node,
                           String operationType,
                           String operationName,
                           String operationPath,
                           Map<String, Object> requestParams,
                           String responseMessage) {
        logOperation(sessionId, node, operationType, operationName, operationPath,
                requestParams, 200, responseMessage, "SUCCESS", null);
    }

    public void logFailure(String sessionId,
                           AbstractPuppetNode node,
                           String operationType,
                           String operationName,
                           String operationPath,
                           Map<String, Object> requestParams,
                           String errorMessage) {
        logOperation(sessionId, node, operationType, operationName, operationPath,
                requestParams, null, null, "FAILED", errorMessage);
    }

    public void logOperation(String sessionId,
                             AbstractPuppetNode node,
                             String operationType,
                             String operationName,
                             String operationPath,
                             Map<String, Object> requestParams,
                             Integer responseCode,
                             String responseMessage,
                             String status,
                             String errorMessage) {
        try {
            if (!auditPolicyService.shouldRecord(operationType, false)) {
                return;
            }
            AuditLog auditLog = new AuditLog();
            auditLog.setSessionId(sessionId);
            fillUser(auditLog, sessionId, node);
            fillPuppet(auditLog, node);
            auditLog.setOperationType(operationType);
            auditLog.setOperationName(operationName);
            auditLog.setOperationPath(operationPath);
            auditLog.setResponseCode(responseCode);
            auditLog.setResponseMessage(responseMessage);
            auditLog.setStatus(status);
            auditLog.setErrorMessage(errorMessage);
            auditLog.setRemark("AI_TOOL");
            if (requestParams != null) {
                Map<String, Object> sanitizedParams = AuditParameterSanitizer.sanitize(requestParams);
                try {
                    auditLog.setRequestParams(JsonUtil.toJsonString(sanitizedParams));
                } catch (Exception e) {
                    logger.warn("序列化 AI 审计参数失败: {}", e.getMessage());
                    auditLog.setRequestParams(sanitizedParams.toString());
                }
            }
            auditLogService.insertAuditLog(auditLog);
        } catch (Exception e) {
            logger.error("记录 AI 审计日志失败: {}", e.getMessage(), e);
        }
    }

    private void fillUser(AuditLog auditLog, String sessionId, AbstractPuppetNode node) {
        User user = node == null ? null : node.getUser();
        if (user == null && sessionId != null && !sessionId.isBlank()) {
            PuppetNodeSession session = PuppetNodeSessionContainer.getSession(sessionId);
            if (session != null) {
                user = userService.getUserById(session.getCreateByUser());
            }
        }
        if (user != null) {
            auditLog.setUserId(user.getUserId());
            auditLog.setUserName(user.getUserName());
        }
    }

    private void fillPuppet(AuditLog auditLog, AbstractPuppetNode node) {
        Puppet puppet = node == null ? null : node.getPuppet();
        if (puppet != null) {
            auditLog.setPuppetId(puppet.getPuppetId());
            auditLog.setPuppetName(puppet.getPuppetName());
        }
    }

}
