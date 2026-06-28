package org.leo.web.interceptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.leo.core.entity.User;
import org.leo.web.security.PermissionPolicy;
import org.leo.web.controller.platform.admin.UserController;
import org.leo.core.util.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;

/**
 * 登录与权限拦截器，仅作用于 /platform/** API 路径。
 * 路径级别的排除（login/status）由 WebConfig 中的 excludePathPatterns 控制。
 */
@Component
public class LoginInterceptor implements HandlerInterceptor {
    private static final Logger logger = LoggerFactory.getLogger(LoginInterceptor.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        if (!(handler instanceof HandlerMethod handlerMethod)) {
            writeJsonError(response, HttpServletResponse.SC_FORBIDDEN, ApiResponse.forbidden("禁止访问"));
            return false;
        }

        User user = (User) request.getSession().getAttribute("user");
        if (user == null) {
            writeJsonError(response, HttpServletResponse.SC_FORBIDDEN, ApiResponse.forbidden("用户未登录"));
            return false;
        }

        boolean aiModelWrite = request.getRequestURI().startsWith(request.getContextPath() + "/platform/admin/ai-models")
                && !"GET".equalsIgnoreCase(request.getMethod());
        boolean aiProviderWrite = request.getRequestURI().startsWith(request.getContextPath() + "/platform/admin/ai-providers")
                && !"GET".equalsIgnoreCase(request.getMethod());
        if (handlerMethod.getBean() instanceof UserController || aiModelWrite || aiProviderWrite) {
            if (!PermissionPolicy.isAdmin(user)) {
                writeJsonError(response, HttpServletResponse.SC_UNAUTHORIZED, ApiResponse.unauthorized("管理员权限不足"));
                return false;
            }
        }

        return true;
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
