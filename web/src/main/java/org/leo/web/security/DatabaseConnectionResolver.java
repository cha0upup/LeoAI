package org.leo.web.security;

import org.leo.core.entity.AgentDatabaseConnection;
import org.leo.core.entity.User;
import org.leo.service.AgentDatabaseConnectionService;
import org.leo.web.exception.ApiException;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Resolves an inline database connection or a persisted profile at the web
 * authorization boundary.
 */
@Component
public final class DatabaseConnectionResolver {

    private static final String CONNECTION_ID = "connectionId";

    private final AgentDatabaseConnectionService connectionService;

    public DatabaseConnectionResolver(AgentDatabaseConnectionService connectionService) {
        this.connectionService = connectionService;
    }

    public Map<String, Object> resolve(Map<String, Object> supplied,
                                       String expectedAgentId,
                                       User currentUser) {
        if (supplied == null) {
            throw ApiException.badRequest("connection 不能为空");
        }
        String connectionId = reference(supplied);
        if (connectionId == null) {
            return new LinkedHashMap<String, Object>(supplied);
        }
        if (currentUser == null) {
            throw ApiException.unauthorized("用户未登录");
        }

        AgentDatabaseConnection saved = connectionService.findById(connectionId);
        if (saved == null) {
            throw ApiException.notFound("数据库连接配置不存在");
        }
        if (expectedAgentId == null || expectedAgentId.isBlank()
                || !Objects.equals(saved.getAgentId(), expectedAgentId)) {
            throw ApiException.forbidden("数据库连接配置与当前 Agent 不匹配");
        }
        return connectionService.toActiveConnectionSpec(saved).toMap();
    }

    public String reference(Map<String, Object> supplied) {
        if (supplied == null) return null;
        return text(supplied.get(CONNECTION_ID));
    }

    private String text(Object value) {
        if (value == null) return null;
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }
}
