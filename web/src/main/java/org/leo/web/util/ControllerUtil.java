package org.leo.web.util;

import jakarta.servlet.http.HttpServletRequest;
import org.leo.core.entity.AiExecutionPolicy;
import org.leo.core.entity.User;
import org.leo.core.puppet.AbstractPuppetNode;
import org.leo.core.puppet.capability.PuppetNodeCapabilityRegistry;
import org.leo.core.runtime.CapabilityStatus;
import org.leo.core.session.PuppetNodeSession;
import org.leo.core.session.PuppetNodeSessionContainer;
import org.leo.core.util.ApiResponse;
import org.leo.web.exception.ApiException;
import org.leo.web.security.PermissionPolicy;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 控制器工具类
 * 提供控制器中常用的公共方法，减少代码重复
 * 
 * @author LeoSpring
 * @version 2.0
 */
public class ControllerUtil {
    
    private static final String PARAM_SESSION_ID = "sessionId";
    private static final String SESSION_ATTR_USER = "user";
    private static final Set<String> GENERIC_AUDIT_SKIP_PARTS = new LinkedHashSet<>(Set.of(
            "_LIST",
            "_QUERY",
            "_PROGRESS",
            "_TASKS",
            "_STATUS",
            "_INFO",
            "_DETAIL",
            "_META",
            "_STATS",
            "_SUMMARY",
            "_POLL"
    ));
    private static final Set<String> GENERIC_AUDIT_ACTION_PARTS = new LinkedHashSet<>(Set.of(
            "_ADD",
            "_CANCEL",
            "_CHMOD",
            "_CLEAR",
            "_CONNECT",
            "_COPY",
            "_CREATE",
            "_DELETE",
            "_DISCONNECT",
            "_DOWNLOAD",
            "_DUMP",
            "_EDIT",
            "_EXEC",
            "_EXPORT",
            "_IMPORT",
            "_INVOKE",
            "_KILL",
            "_MONITOR",
            "_MOVE",
            "_PAUSE",
            "_REMOVE",
            "_RENAME",
            "_RESTART",
            "_RESUME",
            "_RUN",
            "_SEND",
            "_START",
            "_STOP",
            "_TOGGLE",
            "_TOUCH",
            "_UNPAUSE",
            "_UPDATE",
            "_UPLOAD",
            "_WRITE"
    ));
    
    /**
     * 获取必需的字符串参数
     * 
     * @param params 参数Map
     * @param paramName 参数名
     * @return 参数值（字符串）
     * @throws IllegalArgumentException 如果参数不存在或为空
     */
    public static String getRequiredStringParam(Map<String, Object> params, String paramName) {
        if (params == null) {
            throw new IllegalArgumentException("params不能为空");
        }
        Object paramObj = params.get(paramName);
        if (paramObj == null || paramObj.toString().isBlank()) {
            throw new IllegalArgumentException(paramName + "不能为空");
        }
        String value = paramObj.toString();
        return value;
    }
    
    /**
     * 获取可选的字符串参数
     * 
     * @param params 参数Map
     * @param paramName 参数名
     * @return 参数值（字符串），如果不存在则返回null
     */
    public static String getOptionalStringParam(Map<String, Object> params, String paramName) {
        if (params == null) {
            return null;
        }
        Object paramObj = params.get(paramName);
        if (paramObj == null) {
            return null;
        }
        String value = paramObj.toString();
        return value != null && !value.isBlank() ? value : null;
    }
    

    public static AiExecutionPolicy buildAiExecutionPolicy(HttpServletRequest request) {
        return AiExecutionPolicy.from(getCurrentUser(request));
    }

    /**
     * 获取通用 PuppetNode，用于支持多类型节点的控制器。
     */
    public static AbstractPuppetNode getAbstractPuppetNode(Map<String, Object> params) {
        PuppetNodeSession session = getPuppetNodeSession(params);
        return getAbstractPuppetNode(session);
    }

    public static AbstractPuppetNode getAbstractPuppetNode(String sessionId) {
        return getAbstractPuppetNode(getPuppetNodeSession(sessionId));
    }

    public static <T> T requireCapability(Map<String, Object> params, Class<T> capabilityType) {
        PuppetNodeSession session = getPuppetNodeSession(params);
        return requireCapability(session, capabilityType);
    }

    public static <T> T requireCapability(String sessionId, Class<T> capabilityType) {
        PuppetNodeSession session = getPuppetNodeSession(sessionId);
        return requireCapability(session, capabilityType);
    }

    public static <T> T requireCapability(PuppetNodeSession session, Class<T> capabilityType) {
        if (capabilityType == null) {
            throw ApiException.badRequest("capabilityType不能为空");
        }
        AbstractPuppetNode node = getAbstractPuppetNode(session);
        if (PuppetNodeCapabilityRegistry.supports(node, capabilityType)) {
            return capabilityType.cast(node);
        }
        String nodeType = node.getPuppet() != null ? node.getPuppet().getType() : node.getClass().getSimpleName();
        String capabilityName = PuppetNodeCapabilityRegistry.capabilityName(capabilityType);
        CapabilityStatus status = capabilityName != null
                ? PuppetNodeCapabilityRegistry.getStatus(node, capabilityName) : null;
        String reason = status != null && status.getReason() != null && !status.getReason().isBlank()
                ? ", reason=" + status.getReason() : "";
        throw ApiException.badRequest("当前 Puppet 类型不支持能力: " + capabilityType.getSimpleName()
                + " (type=" + safeText(nodeType, "unknown") + reason + ")");
    }

    private static AbstractPuppetNode getAbstractPuppetNode(PuppetNodeSession session) {
        AbstractPuppetNode node = session.getPuppetNode();
        if (node == null) {
            throw ApiException.notFound("Puppet实体不存在: " + session.getSessionId());
        }
        User currentUser = getCurrentUser();
        if (currentUser != null) {
            node.setUser(currentUser);
        }
        return node;
    }

    /**
     * 从参数中获取并验证当前登录用户对 Session 的访问权限。
     */
    public static PuppetNodeSession getPuppetNodeSession(Map<String, Object> params) {
        if (params == null) {
            throw new IllegalArgumentException("params不能为空");
        }
        Object sessionIdObj = params.get(PARAM_SESSION_ID);
        String sessionId = sessionIdObj == null ? null : sessionIdObj.toString();
        return getPuppetNodeSession(sessionId);
    }

    /**
     * 获取指定 Session，并强制执行会话权限隔离。
     */
    public static PuppetNodeSession getPuppetNodeSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw ApiException.badRequest("sessionId不能为空");
        }
        PuppetNodeSession session = PuppetNodeSessionContainer.getSession(sessionId.trim());
        if (session == null) {
            throw ApiException.notFound("会话不存在或已过期: " + sessionId);
        }
        User currentUser = getCurrentUser();
        if (!canAccessSession(session, currentUser)) {
            throw ApiException.forbidden("无权限访问此会话: " + sessionId);
        }
        return session;
    }

    /**
     * 获取当前 HTTP Session 中的登录用户。
     */
    public static User getCurrentUser() {
        try {
            ServletRequestAttributes attributes =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attributes == null) {
                return null;
            }
            return getCurrentUser(attributes.getRequest());
        } catch (Exception e) {
            return null;
        }
    }

    public static User getCurrentUser(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        Object user = request.getSession().getAttribute(SESSION_ATTR_USER);
        return user instanceof User ? (User) user : null;
    }

    /** 判断当前请求是否来自 admin 角色用户。 */
    public static boolean isAdmin(HttpServletRequest request) {
        return PermissionPolicy.isAdmin(getCurrentUser(request));
    }

    /**
     * 将执行上下文注入本轮用户消息，为 Agent 提供身份和权限范围说明。
     *
     * <p>真实权限由服务端工具授权层强制执行。提示词只用于帮助模型选择合理工具，
     * 不能扩大当前用户的角色或资源访问范围。
     */
    public static String buildAiPolicyPrompt(AiExecutionPolicy policy, String message) {
        AiExecutionPolicy safePolicy = policy != null ? policy : AiExecutionPolicy.defaultPolicy();
        String role     = safeText(safePolicy.getPrivilege(), "unknown");
        String userId   = safeText(safePolicy.getUserId(), "anonymous");
        String userName = safeText(safePolicy.getUserName(), "anonymous");

        return """
                【当前执行上下文】
                - 当前用户: %s (%s)
                - 当前角色: %s

                执行规范：
                1. 明确标记为只读的专用工具可直接调用，不要等待用户二次确认；普通命令入口 exec 即使只执行 ls/whoami/cat，也必须先做一次无感操作评估。
                2. 高影响操作（命令执行、文件写入、扫描、数据库写入、脚本、插件调用、容器卸载、平台配置变更）：
                   只能调用当前工具列表中已授权的能力；若工具返回权限不足，立即停止重试并向用户说明。
                3. 任何操作都只能在当前角色权限和用户明确目标范围内执行。
                4. 多阶段任务遵循 system 消息中的计划工具规则；简单任务直接行动，不额外输出固定计划清单。

                【用户请求】
                %s
                """.formatted(userName, userId, role, message);
    }

    /**
     * 会话权限模型：admin 可访问全部；普通用户仅可访问自己创建的会话。
     */
    public static boolean canAccessSession(PuppetNodeSession session, User user) {
        return PermissionPolicy.canAccessSession(session, user);
    }

    private static String safeText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
    
    /**
     * 将对象转换为long类型
     * 
     * @param obj 对象
     * @return long值
     * @throws NumberFormatException 如果无法转换
     */
    public static long toLong(Object obj) {
        if (obj instanceof Number) {
            return ((Number) obj).longValue();
        }
        return Long.parseLong(obj.toString());
    }

    // ==================== 控制器样板辅助 ====================

    /** 函数式接口:供 handleCapabilityCall 注入能力逻辑 */
    @FunctionalInterface
    public interface CapabilityCall<T> {
        Map<String, Object> apply(T node) throws Exception;
    }

    /**
     * 统一封装多类型 Puppet 能力调用样板:获取节点 → 校验能力 → 执行回调 → 检查 code==200 → 返回 ApiResponse。
     */
    public static <T> HashMap<String, Object> handleCapabilityCall(
            Map<String, Object> params, Class<T> capabilityType, String errorPrefix, CapabilityCall<T> call) {
        String operationType = inferOperationTypeFromRequest();
        return handleCapabilityCall(params, capabilityType, operationType, null, null, errorPrefix, call,
                shouldAuditGenericOperation(operationType));
    }

    /**
     * 统一封装多类型 Puppet 能力调用样板，并附带审计日志记录。
     */
    public static <T> HashMap<String, Object> handleCapabilityCall(
            Map<String, Object> params,
            Class<T> capabilityType,
            String operationType,
            String operationName,
            String operationPath,
            String errorPrefix,
            CapabilityCall<T> call) {
        return handleCapabilityCall(params, capabilityType, operationType, operationName, operationPath,
                errorPrefix, call, true);
    }

    private static <T> HashMap<String, Object> handleCapabilityCall(
            Map<String, Object> params,
            Class<T> capabilityType,
            String operationType,
            String operationName,
            String operationPath,
            String errorPrefix,
            CapabilityCall<T> call,
            boolean auditEnabled) {
        AbstractPuppetNode auditNode = null;
        String resolvedOperationType = safeText(operationType, inferOperationTypeFromRequest());
        String resolvedOperationName = safeText(operationName, normalizeOperationName(errorPrefix, resolvedOperationType));
        String resolvedOperationPath = safeText(operationPath, currentRequestPath());
        String clientIp = AuditLogUtil.getClientIp();

        try {
            PuppetNodeSession session = getPuppetNodeSession(params);
            auditNode = getAbstractPuppetNode(session);
            T node = requireCapability(session, capabilityType);
            Map<String, Object> result = call.apply(node);
            if (result == null) {
                String message = safeText(errorPrefix, "Puppet调用失败") + ": 返回为空";
                if (auditEnabled) {
                    AuditLogUtil.logFailure(auditNode, resolvedOperationType, resolvedOperationName,
                            resolvedOperationPath, params, message, clientIp);
                }
                return ApiResponse.error(message);
            }

            Integer responseCode = extractResponseCode(result);
            String responseMessage = extractResponseMessage(result);
            if (responseCode != null && responseCode.intValue() == ApiResponse.CODE_SUCCESS) {
                if (auditEnabled) {
                    AuditLogUtil.logSuccess(auditNode, resolvedOperationType, resolvedOperationName,
                            resolvedOperationPath, params, responseCode, responseMessage, clientIp);
                }
                return ApiResponse.success(result);
            }

            String message = safeText(responseMessage, safeText(errorPrefix, "Puppet调用失败") + ": 失败");
            if (auditEnabled) {
                AuditLogUtil.logOperation(auditNode, resolvedOperationType, resolvedOperationName,
                        resolvedOperationPath, params, responseCode, responseMessage, "FAILED", message, clientIp);
            }
            return ApiResponse.error(message);
        } catch (ApiException ae) {
            if (auditEnabled && auditNode != null) {
                AuditLogUtil.logOperation(auditNode, resolvedOperationType, resolvedOperationName,
                        resolvedOperationPath, params, ae.getCode(), ae.getMessage(), "FAILED",
                        ae.getMessage(), clientIp);
            }
            throw ae;
        } catch (Exception e) {
            String message = safeText(errorPrefix, "Puppet调用失败") + ": " + e.getMessage();
            if (auditEnabled && auditNode != null) {
                AuditLogUtil.logError(auditNode, resolvedOperationType, resolvedOperationName,
                        resolvedOperationPath, params, message, clientIp);
            }
            return ApiResponse.error(message);
        }
    }

    private static Integer extractResponseCode(Map<String, Object> result) {
        Object codeObj = result.get("code");
        return codeObj instanceof Number number ? number.intValue() : null;
    }

    private static String extractResponseMessage(Map<String, Object> result) {
        Object msgObj = result.get("msg");
        return msgObj == null ? null : msgObj.toString();
    }

    private static String normalizeOperationName(String errorPrefix, String operationType) {
        if (errorPrefix == null || errorPrefix.isBlank()) {
            return operationType;
        }
        String name = errorPrefix.trim();
        if (name.endsWith("失败") && name.length() > 2) {
            name = name.substring(0, name.length() - 2);
        }
        return name.isBlank() || "操作".equals(name) ? operationType : name;
    }

    private static String currentRequestPath() {
        try {
            ServletRequestAttributes attributes =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attributes == null) {
                return null;
            }
            return attributes.getRequest().getRequestURI();
        } catch (Exception e) {
            return null;
        }
    }

    private static String inferOperationTypeFromRequest() {
        String path = currentRequestPath();
        if (path == null || path.isBlank()) {
            return "PUPPET_OPERATION";
        }
        String normalizedPath = path.trim();
        int queryIndex = normalizedPath.indexOf('?');
        if (queryIndex >= 0) {
            normalizedPath = normalizedPath.substring(0, queryIndex);
        }
        normalizedPath = normalizedPath.replaceFirst("^/+", "");
        if (normalizedPath.startsWith("puppet-node/")) {
            normalizedPath = normalizedPath.substring("puppet-node/".length());
        }
        String operationType = normalizedPath
                .replaceAll("[^A-Za-z0-9]+", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_|_$", "")
                .toUpperCase(Locale.ROOT);
        return operationType.isBlank() ? "PUPPET_OPERATION" : operationType;
    }

    private static boolean shouldAuditGenericOperation(String operationType) {
        if (operationType == null || operationType.isBlank()) {
            return false;
        }
        String normalizedType = operationType.trim().toUpperCase(Locale.ROOT);
        for (String skipPart : GENERIC_AUDIT_SKIP_PARTS) {
            if (normalizedType.endsWith(skipPart) || normalizedType.contains(skipPart + "_")) {
                return false;
            }
        }
        for (String actionPart : GENERIC_AUDIT_ACTION_PARTS) {
            if (normalizedType.endsWith(actionPart) || normalizedType.contains(actionPart + "_")) {
                return true;
            }
        }
        return false;
    }

    // ==================== 通用参数读取 ====================

    /** 读字符串参数,空白返回 null */
    public static String getStr(Map<String, Object> params, String key) {
        Object val = params == null ? null : params.get(key);
        if (val == null) return null;
        String s = val.toString().trim();
        return s.isEmpty() ? null : s;
    }

    /** 读 int 参数,缺失/无效返回 def */
    public static int getInt(Map<String, Object> params, String key, int def) {
        Object val = params == null ? null : params.get(key);
        if (val == null) return def;
        if (val instanceof Number) return ((Number) val).intValue();
        try {
            return Integer.parseInt(val.toString().trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** 读 Integer,缺失/无效返回 null(用于 boxed 参数透传) */
    public static Integer getIntOrNull(Map<String, Object> params, String key) {
        Object val = params == null ? null : params.get(key);
        if (val == null) return null;
        if (val instanceof Number) return Integer.valueOf(((Number) val).intValue());
        try {
            return Integer.valueOf(Integer.parseInt(val.toString().trim()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 读 Long,缺失/无效返回 null */
    public static Long getLongOrNull(Map<String, Object> params, String key) {
        Object val = params == null ? null : params.get(key);
        if (val == null) return null;
        if (val instanceof Number) return Long.valueOf(((Number) val).longValue());
        try {
            return Long.valueOf(Long.parseLong(val.toString().trim()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 读 boolean,接受 true/"true"/1/"1" */
    public static boolean getBool(Map<String, Object> params, String key) {
        Object val = params == null ? null : params.get(key);
        if (val == null) return false;
        if (val instanceof Boolean) return ((Boolean) val).booleanValue();
        if (val instanceof Number) return ((Number) val).intValue() != 0;
        return "true".equalsIgnoreCase(val.toString().trim()) || "1".equals(val.toString().trim());
    }
}
