package org.leo.web.interceptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.leo.core.entity.User;
import org.leo.web.security.PermissionPolicy;
import org.leo.web.security.PermissionService;
import org.leo.web.security.AdminOnlyEndpoint;
import org.leo.web.security.RoleAwareAdminEndpoint;
import org.leo.core.util.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * 登录与权限拦截器，仅作用于 /platform/** API 路径。
 * 路径级别的排除（login/status/setup）由 WebConfig 中的 excludePathPatterns 控制。
 */
@Component
public class LoginInterceptor implements HandlerInterceptor {
    private static final Logger logger = LoggerFactory.getLogger(LoginInterceptor.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private final PermissionService permissionService;

    public LoginInterceptor(PermissionService permissionService) {
        this.permissionService = permissionService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        if (!(handler instanceof HandlerMethod handlerMethod)) {
            writeJsonError(response, HttpServletResponse.SC_FORBIDDEN, ApiResponse.forbidden("禁止访问"));
            return false;
        }

        User user = permissionService.getCurrentUser(request);
        if (user == null) {
            writeJsonError(response, HttpServletResponse.SC_UNAUTHORIZED, ApiResponse.unauthorized("用户未登录"));
            return false;
        }

        String requestPath = request.getRequestURI().substring(request.getContextPath().length());
        if (user.requiresPasswordChange() && !isPasswordChangePath(requestPath)) {
            HashMap<String, Object> body = ApiResponse.forbidden("请先更新密码后继续使用");
            body.put("data", Map.of("passwordChangeRequired", true));
            writeJsonError(response, HttpServletResponse.SC_FORBIDDEN, body);
            return false;
        }

        boolean adminNamespace = requestPath.startsWith("/platform/admin/");
        boolean roleAwareEndpoint = !adminNamespace
                || handlerMethod.hasMethodAnnotation(RoleAwareAdminEndpoint.class)
                || handlerMethod.getBeanType().isAnnotationPresent(RoleAwareAdminEndpoint.class);
        if (adminNamespace && !roleAwareEndpoint) {
            if (!PermissionPolicy.isAdmin(user)) {
                writeJsonError(response, HttpServletResponse.SC_FORBIDDEN, ApiResponse.forbidden("管理员权限不足"));
                return false;
            }
        }

        boolean adminOnlyEndpoint = handlerMethod.hasMethodAnnotation(AdminOnlyEndpoint.class)
                || handlerMethod.getBeanType().isAnnotationPresent(AdminOnlyEndpoint.class);
        if (adminOnlyEndpoint && !PermissionPolicy.isAdmin(user)) {
            writeJsonError(response, HttpServletResponse.SC_FORBIDDEN,
                    ApiResponse.forbidden("管理员权限不足"));
            return false;
        }

        return true;
    }

    private boolean isPasswordChangePath(String requestPath) {
        return "/platform/user/change-password".equals(requestPath)
                || "/platform/user/logout".equals(requestPath);
    }

    private void writeJsonError(HttpServletResponse response, int status, Object body) {
        response.setStatus(status);
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/json;charset=UTF-8");
        try {
            OBJECT_MAPPER.writeValue(response.getWriter(), body);
        } catch (IOException e) {
            logger.error("写入登录拦截器错误响应失败", e);
        }
    }
}
