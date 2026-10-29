package org.leo.web.interceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.leo.web.exception.ApiException;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/** Static pages can load during startup; APIs wait until every runner and ready listener succeeds. */
@Component
public class BootstrapReadinessInterceptor implements HandlerInterceptor {
    private final ApplicationAvailability availability;

    public BootstrapReadinessInterceptor(ApplicationAvailability availability) {
        this.availability = availability;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) return true;
        response.setHeader("Cache-Control", "no-store");
        if (availability.getReadinessState() != ReadinessState.ACCEPTING_TRAFFIC) {
            response.setHeader("Retry-After", "2");
            throw ApiException.serviceUnavailable("系统正在准备或停止服务，请稍后重试");
        }
        return true;
    }
}
