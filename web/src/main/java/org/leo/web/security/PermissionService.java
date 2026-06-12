package org.leo.web.security;

import jakarta.servlet.http.HttpServletRequest;
import org.leo.core.entity.Puppet;
import org.leo.core.entity.User;
import org.leo.core.session.PuppetNodeSession;
import org.leo.service.PuppetService;
import org.leo.web.exception.ApiException;
import org.springframework.stereotype.Service;

/**
 * Web 层权限入口，负责把纯规则和需要查库的链路判断组合起来。
 */
@Service
public class PermissionService {

    private static final int MAX_PARENT_DEPTH = 100;
    private static final String SESSION_ATTR_USER = "user";

    private final PuppetService puppetService;

    public PermissionService(PuppetService puppetService) {
        this.puppetService = puppetService;
    }

    public User getCurrentUser(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        Object user = request.getSession().getAttribute(SESSION_ATTR_USER);
        return user instanceof User ? (User) user : null;
    }

    public User requireLogin(HttpServletRequest request) {
        User user = getCurrentUser(request);
        if (user == null) {
            throw ApiException.unauthorized("用户未登录");
        }
        return user;
    }

    public void requireAdmin(User user) {
        if (!PermissionPolicy.isAdmin(user)) {
            throw ApiException.forbidden("管理员权限不足");
        }
    }

    public void requireSessionAccess(PuppetNodeSession session, User user, String sessionId) {
        if (!PermissionPolicy.canAccessSession(session, user)) {
            throw ApiException.forbidden("无权限访问此会话: " + sessionId);
        }
    }

    public Puppet requireAccessiblePuppetChain(String puppetId, User user) {
        String normalizedPuppetId = requireText(puppetId, "缺少 puppetId");
        Puppet puppet = puppetService.findPuppetById(normalizedPuppetId);
        if (puppet == null) {
            throw ApiException.notFound("Puppet不存在，puppetId: " + normalizedPuppetId);
        }
        if (!canAccessPuppetChain(puppet, user)) {
            throw ApiException.forbidden("无权限访问此Puppet");
        }
        return puppet;
    }

    public boolean canAccessPuppetChain(Puppet puppet, User user) {
        Puppet current = puppet;
        int depth = 0;
        while (current != null && depth < MAX_PARENT_DEPTH) {
            if (!PermissionPolicy.canAccessPuppet(current, user)) {
                return false;
            }
            String parentId = current.getParentPuppetId();
            if (parentId == null || parentId.isBlank() || "root".equals(parentId)) {
                return true;
            }
            current = puppetService.findPuppetById(parentId);
            depth++;
        }
        return false;
    }

    public static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest(message);
        }
        return value.trim();
    }
}
