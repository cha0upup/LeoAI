package org.leo.web.service;

import org.leo.core.entity.User;
import org.leo.service.DatabaseConnectionProfileException;
import org.leo.service.DatabaseConnectionProfileService;
import org.leo.web.exception.ApiException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** HTTP-facing adapter for Agent-owned database profile management. */
@Service
public final class DatabaseConnectionManagementService {

    private final DatabaseConnectionProfileService profileService;

    public DatabaseConnectionManagementService(DatabaseConnectionProfileService profileService) {
        this.profileService = profileService;
    }

    public Map<String, Object> save(User user, String agentId, Map<String, Object> params) {
        return translate(() -> profileService.save(userId(user), agentId, params));
    }

    public List<Map<String, Object>> listByAgent(String agentId, User user) {
        return translate(() -> profileService.listByAgent(userId(user), agentId));
    }

    public void delete(String connectionId, String agentId, User user) {
        translate(() -> {
            profileService.delete(userId(user), agentId, connectionId);
            return null;
        });
    }

    public Map<String, Object> setEnabled(String connectionId,
                                          String agentId,
                                          boolean enabled,
                                          User user) {
        return translate(() -> profileService.setEnabled(
                userId(user), agentId, connectionId, enabled));
    }

    private String userId(User user) {
        if (user == null || user.getUserId() == null || user.getUserId().isBlank()) {
            throw ApiException.unauthorized("用户未登录");
        }
        return user.getUserId();
    }

    private <T> T translate(Supplier<T> action) {
        try {
            return action.get();
        } catch (DatabaseConnectionProfileException error) {
            throw switch (error.getKind()) {
                case VALIDATION -> ApiException.badRequest(error.getMessage());
                case NOT_FOUND -> ApiException.notFound(error.getMessage());
                case FORBIDDEN -> ApiException.forbidden(error.getMessage());
                case PERSISTENCE -> ApiException.serverError(error.getMessage());
            };
        }
    }
}
