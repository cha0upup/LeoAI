package org.leo.web.util;

import jakarta.servlet.http.HttpServletRequest;
import org.leo.core.entity.AiExecutionPolicy;
import org.leo.core.entity.User;
import org.leo.core.puppet.AbstractPuppetNode;
import org.leo.core.puppet.impl.JavaPuppetNode;
import org.leo.core.session.PuppetNodeSession;
import org.leo.core.session.PuppetNodeSessionContainer;
import org.leo.core.util.ApiResponse;
import org.leo.web.exception.ApiException;
import org.leo.web.security.PermissionPolicy;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.HashMap;
import java.util.Map;

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
            throw ApiException.badRequest("params不能为空");
        }
        Object paramObj = params.get(paramName);
        if (paramObj == null || paramObj.toString().isBlank()) {
            throw ApiException.badRequest(paramName + "不能为空");
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
     * 从参数中获取并验证Session和PuppetEntity
     * 
     * @param params 参数Map，必须包含sessionId
     * @return PuppetEntity实例
     * @throws IllegalArgumentException 如果参数无效或会话不存在
     */
    public static JavaPuppetNode getPuppetNode(Map<String, Object> params) {

        PuppetNodeSession session = getPuppetNodeSession(params);
        return getPuppetNode(session);
    }

    public static JavaPuppetNode getPuppetNode(String sessionId) {
        return getPuppetNode(getPuppetNodeSession(sessionId));
    }

    private static JavaPuppetNode getPuppetNode(PuppetNodeSession session) {

        JavaPuppetNode javaPuppetNode = session.getJavaPuppetNode();
        if (javaPuppetNode == null) {
            throw ApiException.notFound("Puppet实体不存在: " + session.getSessionId());
        }
        User currentUser = getCurrentUser();
        if (currentUser != null) {
            javaPuppetNode.setUser(currentUser);
        }

        return javaPuppetNode;
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
     * <p>确认流程由平台拦截器统一管控（前端弹窗），AI 对此完全无感知，
     * 工具调用结果对 AI 是透明的：放行时正常返回，拒绝时返回 user_rejected 信号。
     * 因此提示词中无需包含任何让 AI 主动请求用户确认的指令。
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
                1. 信息收集、只读分析、侦察类操作：直接调用工具执行，不要等待用户二次确认。
                2. 高影响操作（命令执行、文件写入、扫描、数据库写入、脚本、插件调用、容器卸载、平台配置变更）：
                   直接调用对应工具，平台会在必要时自动向用户请求确认，无需在回复中等待用户文字指令。
                3. 任何操作都只能在当前角色权限和用户明确目标范围内执行。
                4. 如果这是多步任务，先用自然语言简短说明当前阶段，再直接行动；不要固定写成模板化的计划清单。

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

    /** 函数式接口:供 handlePuppetCall 注入业务逻辑 */
    @FunctionalInterface
    public interface PuppetCall {
        Map<String, Object> apply(JavaPuppetNode node) throws Exception;
    }

    /**
     * 统一封装 puppet 调用样板:获取节点 → 执行回调 → 检查 code==200 → 返回 ApiResponse。
     * 收敛所有 controller 中重复的 try/catch + null/code 检查模板。
     *
     * @param params      请求体参数(必含 sessionId)
     * @param errorPrefix 失败消息前缀(如 "获取共享列表失败")
     * @param call        业务回调,接收已校验的 JavaPuppetNode,返回 puppet 端结果 Map
     */
    public static HashMap<String, Object> handlePuppetCall(
            Map<String, Object> params, String errorPrefix, PuppetCall call) {
        try {
            JavaPuppetNode node = getPuppetNode(params);
            Map<String, Object> result = call.apply(node);
            if (result == null) return ApiResponse.error(errorPrefix + ": 返回为空");
            Object codeObj = result.get("code");
            if (codeObj instanceof Number && ((Number) codeObj).intValue() == 200) return ApiResponse.success(result);
            Object msgObj = result.get("msg");
            return ApiResponse.error(msgObj != null ? msgObj.toString() : errorPrefix + ": 失败");
        } catch (ApiException ae) {
            // 参数/权限/未找到等明确异常,保留原状态码
            throw ae;
        } catch (Exception e) {
            return ApiResponse.error(errorPrefix + ": " + e.getMessage());
        }
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
