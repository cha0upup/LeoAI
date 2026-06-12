package org.leo.core.entity;

/**
 * 用户 AI 工具放行策略实体。
 *
 * <p>{@link #allowedToolTypes} 以逗号分隔存储工具类型字符串（如 "command,scan"），
 * 由 UserAiPolicyService 负责与 {@code Set<String>} 互转。
 */
public class UserAiPolicy {

    private String userId;

    /**
     * 永久放行的工具类型列表（逗号分隔）。
     * 合法值：command, file_write, scan, sql_write, script, plugin, container, platform_write。
     */
    private String allowedToolTypes;

    private String updateTime;

    public UserAiPolicy() {
    }

    public UserAiPolicy(String userId, String allowedToolTypes, String updateTime) {
        this.userId = userId;
        this.allowedToolTypes = allowedToolTypes;
        this.updateTime = updateTime;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getAllowedToolTypes() {
        return allowedToolTypes;
    }

    public void setAllowedToolTypes(String allowedToolTypes) {
        this.allowedToolTypes = allowedToolTypes;
    }

    public String getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(String updateTime) {
        this.updateTime = updateTime;
    }
}
