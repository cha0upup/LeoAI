package org.leo.service.audit;

import org.leo.core.entity.AuditLog;
import org.leo.core.entity.Agent;
import org.leo.core.entity.User;
import org.leo.core.agent.AbstractAgentNode;
import org.leo.core.session.AgentNodeSession;
import org.leo.core.session.AgentNodeSessionContainer;
import org.leo.core.util.json.JsonUtil;
import org.leo.service.user.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class AgentAuditService {

    private static final Logger logger = LoggerFactory.getLogger(AgentAuditService.class);
    private final AuditLogService auditLogService;
    private final AuditPolicyService auditPolicyService;
    private final UserService userService;

    public AgentAuditService(AuditLogService auditLogService,
                              AuditPolicyService auditPolicyService,
                              UserService userService) {
        this.auditLogService = auditLogService;
        this.auditPolicyService = auditPolicyService;
        this.userService = userService;
    }

    public void logSuccess(String sessionId,
                           AbstractAgentNode node,
                           String operationType,
                           String operationName,
                           String operationPath,
                           Map<String, Object> requestParams,
                           String responseMessage) {
        logOperation(sessionId, node, operationType, operationName, operationPath,
                requestParams, 200, responseMessage, "SUCCESS", null);
    }

    public void logFailure(String sessionId,
                           AbstractAgentNode node,
                           String operationType,
                           String operationName,
                           String operationPath,
                           Map<String, Object> requestParams,
                           String errorMessage) {
        logOperation(sessionId, node, operationType, operationName, operationPath,
                requestParams, null, null, "FAILED", errorMessage);
    }

    public void logOperation(String sessionId,
                             AbstractAgentNode node,
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
            fillAgent(auditLog, node);
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

    private void fillUser(AuditLog auditLog, String sessionId, AbstractAgentNode node) {
        User user = node == null ? null : node.getUser();
        if (user == null && sessionId != null && !sessionId.isBlank()) {
            AgentNodeSession session = AgentNodeSessionContainer.getSession(sessionId);
            if (session != null) {
                user = userService.getUserById(session.getCreateByUser());
            }
        }
        if (user != null) {
            auditLog.setUserId(user.getUserId());
            auditLog.setUserName(user.getUserName());
        }
    }

    private void fillAgent(AuditLog auditLog, AbstractAgentNode node) {
        Agent agent = node == null ? null : node.getAgent();
        if (agent != null) {
            auditLog.setAgentId(agent.getAgentId());
            auditLog.setAgentName(agent.getAgentName());
        }
    }

}
