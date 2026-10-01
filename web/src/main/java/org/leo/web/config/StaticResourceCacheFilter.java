package org.leo.web.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Keeps the SPA entry fresh while allowing content-hashed build assets to be cached permanently.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class StaticResourceCacheFilter extends OncePerRequestFilter {

    static final String HTML_CACHE_CONTROL = "no-cache, no-store, must-revalidate";
    static final String HASHED_ASSET_CACHE_CONTROL = "public, max-age=31536000, immutable";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (isCacheableRequest(request)) {
            String requestPath = requestPath(request);
            if (requestPath.startsWith("/assets/")) {
                response.setHeader(HttpHeaders.CACHE_CONTROL, HASHED_ASSET_CACHE_CONTROL);
            } else if (isSpaEntryRequest(requestPath)) {
                response.setHeader(HttpHeaders.CACHE_CONTROL, HTML_CACHE_CONTROL);
                response.setHeader(HttpHeaders.PRAGMA, "no-cache");
                response.setDateHeader(HttpHeaders.EXPIRES, 0L);
            }
        }
        filterChain.doFilter(request, response);
    }

    private boolean isCacheableRequest(HttpServletRequest request) {
        return "GET".equalsIgnoreCase(request.getMethod())
                || "HEAD".equalsIgnoreCase(request.getMethod());
    }

    private String requestPath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        return contextPath == null || contextPath.isEmpty() ? uri : uri.substring(contextPath.length());
    }

    private boolean isSpaEntryRequest(String requestPath) {
        if ("/".equals(requestPath) || "/index.html".equals(requestPath)) {
            return true;
        }
        if (requestPath.startsWith("/platform/") || requestPath.startsWith("/agent-node/")) {
            return false;
        }
        int lastSlash = requestPath.lastIndexOf('/');
        return !requestPath.substring(lastSlash + 1).contains(".");
    }
}
