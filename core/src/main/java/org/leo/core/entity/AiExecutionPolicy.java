package org.leo.core.entity;

/**
 * AI 请求的执行策略。
 *
 * <p>该对象由 Web 请求入口设置，由 Agent 拦截器读取，用于统一控制
 * 基于用户角色的工具可见性。高影响工具授权统一由运行时会话状态维护。
 */
public class AiExecutionPolicy {

    public static final String PRIVILEGE_ADMIN = "admin";

    private String userId;
    private String userName;
    private String privilege;

    public AiExecutionPolicy() {
    }

    public AiExecutionPolicy(String userId, String userName, String privilege) {
        this.userId = userId;
        this.userName = userName;
        this.privilege = privilege;
    }

    public static AiExecutionPolicy defaultPolicy() {
        return new AiExecutionPolicy(null, null, null);
    }

    public static AiExecutionPolicy from(User user) {
        if (user == null) {
            return defaultPolicy();
        }
        return new AiExecutionPolicy(user.getUserId(), user.getUserName(), user.getPrivilege());
    }

    // ── 权限判断 ──────────────────────────────────────────────────────────────

    public boolean isAdmin() {
        return PRIVILEGE_ADMIN.equals(privilege);
    }

    // ── Getters / Setters ─────────────────────────────────────────────────────

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getUserName() {
        return userName;
    }

    public void setUserName(String userName) {
        this.userName = userName;
    }

    public String getPrivilege() {
        return privilege;
    }

    public void setPrivilege(String privilege) {
        this.privilege = privilege;
    }
}
