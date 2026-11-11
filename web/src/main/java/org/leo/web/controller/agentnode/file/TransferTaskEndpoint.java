package org.leo.web.controller.agentnode.file;

import jakarta.servlet.http.HttpServletRequest;
import org.leo.core.entity.User;
import org.leo.core.util.ApiResponse;
import org.leo.web.util.AuditLogUtil;
import org.leo.web.util.ControllerUtil;

import java.util.HashMap;
import java.util.Map;

/** Shared HTTP boundary for transfer task operations with the legacy server-error contract. */
final class TransferTaskEndpoint {
    private TransferTaskEndpoint() {
    }

    @FunctionalInterface
    interface TaskAction {
        Map<String, Object> apply(User user, String taskId) throws Exception;
    }

    static HashMap<String, Object> execute(HttpServletRequest request, Map<String, Object> params,
                                           String errorPrefix, TaskAction action) {
        try {
            User user = ControllerUtil.getCurrentUser(request);
            if (user == null) {
                return ApiResponse.unauthorized("用户未登录");
            }
            String taskId = ControllerUtil.getRequiredStringParam(params, "taskId");
            return ApiResponse.success(action.apply(user, taskId));
        } catch (Exception e) {
            return ApiResponse.error(errorPrefix + ": " + e.getMessage());
        }
    }

    static HashMap<String, Object> mutate(HttpServletRequest request, Map<String, Object> params,
                                          String operationType, String description, TaskAction action) {
        return execute(request, params, description + "失败", (user, taskId) -> {
            Map<String, Object> data = action.apply(user, taskId);
            AuditLogUtil.logSystemOperation(user, operationType, description, taskId, params,
                    ApiResponse.CODE_SUCCESS, description, "SUCCESS", null,
                    AuditLogUtil.getClientIp(request), false);
            return data;
        });
    }
}
