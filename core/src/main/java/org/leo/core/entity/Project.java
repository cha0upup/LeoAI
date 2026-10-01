package org.leo.core.entity;

import java.util.Objects;

/** 业务项目，用于组织可复用的 Agent 资产和运行会话。 */
public class Project {

    public static final String STATUS_ACTIVE = "active";
    public static final String STATUS_ARCHIVED = "archived";

    private String projectId;
    private String projectName;
    private String projectCode;
    private String description;
    private String status;
    private String ownerUserId;
    private String teamId;
    private String permission;
    private String createTime;
    private String updateTime;

    public Project() {
        this.status = STATUS_ACTIVE;
        this.permission = "private";
    }

    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }
    public String getProjectName() { return projectName; }
    public void setProjectName(String projectName) { this.projectName = projectName; }
    public String getProjectCode() { return projectCode; }
    public void setProjectCode(String projectCode) { this.projectCode = projectCode; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getOwnerUserId() { return ownerUserId; }
    public void setOwnerUserId(String ownerUserId) { this.ownerUserId = ownerUserId; }
    public String getTeamId() { return teamId; }
    public void setTeamId(String teamId) { this.teamId = teamId; }
    public String getPermission() { return permission; }
    public void setPermission(String permission) { this.permission = permission; }
    public String getCreateTime() { return createTime; }
    public void setCreateTime(String createTime) { this.createTime = createTime; }
    public String getUpdateTime() { return updateTime; }
    public void setUpdateTime(String updateTime) { this.updateTime = updateTime; }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof Project project)) return false;
        return Objects.equals(projectId, project.projectId);
    }

    @Override
    public int hashCode() { return Objects.hash(projectId); }
}
