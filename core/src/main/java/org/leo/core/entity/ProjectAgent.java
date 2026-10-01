package org.leo.core.entity;

/** 项目与 Agent 的多对多关联。 */
public class ProjectAgent {
    private String projectId;
    private String agentId;
    private String alias;
    private String environment;
    private String tags;
    private Integer sortOrder;
    private String addedByUserId;
    private String createTime;

    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }
    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }
    public String getAlias() { return alias; }
    public void setAlias(String alias) { this.alias = alias; }
    public String getEnvironment() { return environment; }
    public void setEnvironment(String environment) { this.environment = environment; }
    public String getTags() { return tags; }
    public void setTags(String tags) { this.tags = tags; }
    public Integer getSortOrder() { return sortOrder; }
    public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }
    public String getAddedByUserId() { return addedByUserId; }
    public void setAddedByUserId(String addedByUserId) { this.addedByUserId = addedByUserId; }
    public String getCreateTime() { return createTime; }
    public void setCreateTime(String createTime) { this.createTime = createTime; }
}
