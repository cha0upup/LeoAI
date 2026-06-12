package org.leo.core.entity;

/**
 * 用户模型
 * 
 * @author LeoSpring
 * @version 2.1
 */
public class User {
    private String userId;
    private String userName;
    private String password;
    private String privilege;
    private String email;
    private String phone;
    private Integer status;
    private String lastLoginTime;
    private Integer loginCount;
    private String createTime;
    private String updateTime;
    private String teamId;
    private String remark;

    public User() {
    }

    public User(String userId, String userName, String password, String privilege, String createTime) {
        this.userId = userId;
        this.userName = userName;
        this.password = password;
        this.privilege = privilege;
        this.createTime = createTime;
        this.updateTime = createTime;
        this.status = 1;
        this.loginCount = 0;
    }

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

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getPrivilege() {
        return privilege;
    }

    public void setPrivilege(String privilege) {
        this.privilege = privilege;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public String getLastLoginTime() {
        return lastLoginTime;
    }

    public void setLastLoginTime(String lastLoginTime) {
        this.lastLoginTime = lastLoginTime;
    }

    public Integer getLoginCount() {
        return loginCount;
    }

    public void setLoginCount(Integer loginCount) {
        this.loginCount = loginCount;
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

    public String getTeamId() {
        return teamId;
    }

    public void setTeamId(String teamId) {
        this.teamId = teamId;
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
        sb.append("User{");
        sb.append("userId='").append(userId).append('\'');
        sb.append(", userName='").append(userName).append('\'');
        sb.append(", privilege='").append(privilege).append('\'');
        sb.append(", email='").append(email).append('\'');
        sb.append(", status=").append(status);
        sb.append(", teamId='").append(teamId).append('\'');
        sb.append('}');
        return sb.toString();
    }
}
