package org.leo.core.entity;

/**
 * 团队模型
 * 
 * @author LeoSpring
 * @version 2.1
 */
public class Team {
    private String teamId;
    private String teamName;
    private String leaderId;
    private String description;
    private Integer status;
    private String createTime;
    private String updateTime;
    private String remark;

    public Team() {
    }

    public Team(String teamId, String teamName, String leaderId, String createTime) {
        this.teamId = teamId;
        this.teamName = teamName;
        this.leaderId = leaderId;
        this.createTime = createTime;
        this.updateTime = createTime;
        this.status = 1;
    }

    public String getTeamId() {
        return teamId;
    }

    public void setTeamId(String teamId) {
        this.teamId = teamId;
    }

    public String getTeamName() {
        return teamName;
    }

    public void setTeamName(String teamName) {
        this.teamName = teamName;
    }

    public String getLeaderId() {
        return leaderId;
    }

    public void setLeaderId(String leaderId) {
        this.leaderId = leaderId;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public String getCreateTime() {
        return createTime;
    }

    public void setCreateTime(String createTime) {
        this.createTime = createTime;
    }

    public String getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(String updateTime) {
        this.updateTime = updateTime;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }

    @Override
    public String toString() {
        StringBuffer sb = new StringBuffer();
        sb.append("Team{");
        sb.append("teamId='").append(teamId).append('\'');
        sb.append(", teamName='").append(teamName).append('\'');
        sb.append(", leaderId='").append(leaderId).append('\'');
        sb.append(", status=").append(status);
        sb.append('}');
        return sb.toString();
    }
}
