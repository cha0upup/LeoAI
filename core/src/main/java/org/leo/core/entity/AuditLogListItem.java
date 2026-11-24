package org.leo.core.entity;

/** 审计列表只展示索引信息；请求与响应正文由详情查询返回。 */
public record AuditLogListItem(String logId, String userId, String userName, String agentId,
                               String agentName, String operationType, String operationName,
                               String operationPath, String status, String clientIp, String createTime) {
}
