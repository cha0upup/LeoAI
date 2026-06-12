package org.leo.core.entity;

import java.util.Objects;

/**
 * Puppet模型
 * 
 * @author LeoSpring
 * @version 2.1
 */
public class Puppet {
    private String puppetId;
    private String puppetName;
    private String parentPuppetId;
    private String createByUserId;
    private String teamId;
    private String connLink;
    private String protocol;
    private String headers;
    private String reqDisguiseId;
    private String respDisguiseId;
    private Integer proxyEnabled;
    private String proxyType;
    private String proxyHost;
    private Integer proxyPort;
    private Integer balanceEnabled;
    private Integer maxReqCount;
    private String permission;
    private String lastHeartbeat;
    private Integer heartbeatInterval;
    private String createTime;
    private String updateTime;
    private String remark;

    /** URL 随机化策略（JSON 格式存储） */
    private String urlStrategy;

    /** 请求体 Padding 策略（JSON 格式存储） */
    private String paddingStrategy;

    /** Header 噪声注入策略（JSON 格式存储） */
    private String headerNoiseStrategy;

    /** TLS 指纹伪装策略（JSON 格式存储） */
    private String tlsFingerprintStrategy;

    /** 节点类型 */
    private String type;

    public Puppet() {
        this.maxReqCount = 0;
        this.balanceEnabled = 0;
        this.proxyEnabled = 0;
        this.heartbeatInterval = 30000;
        this.permission = "private";
        this.protocol = "http";
        this.type = "java";
    }

    public Puppet(String puppetId, String puppetName, String createByUserId, String connLink, String proxyType, String proxyHost, Integer proxyPort, String createTime, String updateTime, String parentPuppetId) {
        this();
        this.puppetId = puppetId;
        this.puppetName = puppetName;
        this.createByUserId = createByUserId;
        this.connLink = connLink;
        this.proxyType = proxyType;
        this.proxyHost = proxyHost;
        this.proxyPort = proxyPort;
        this.createTime = createTime;
        this.updateTime = updateTime;
        this.parentPuppetId = parentPuppetId;
    }

    public String getPuppetId() {
        return puppetId;
    }

    public void setPuppetId(String puppetId) {
        this.puppetId = puppetId;
    }

    public String getPuppetName() {
        return puppetName;
    }

    public void setPuppetName(String puppetName) {
        this.puppetName = puppetName;
    }

    public String getParentPuppetId() {
        return parentPuppetId;
    }

    public void setParentPuppetId(String parentPuppetId) {
        this.parentPuppetId = parentPuppetId;
    }

    public String getCreateByUserId() {
        return createByUserId;
    }

    public void setCreateByUserId(String createByUserId) {
        this.createByUserId = createByUserId;
    }

    public String getTeamId() {
        return teamId;
    }

    public void setTeamId(String teamId) {
        this.teamId = teamId;
    }

    public String getConnLink() {
        return connLink;
    }

    public void setConnLink(String connLink) {
        this.connLink = connLink;
    }

    public String getProtocol() {
        return protocol;
    }

    public void setProtocol(String protocol) {
        this.protocol = protocol;
    }

    public String getHeaders() {
        return headers;
    }

    public void setHeaders(String headers) {
        this.headers = headers;
    }

    public String getReqDisguiseId() {
        return reqDisguiseId;
    }

    public void setReqDisguiseId(String reqDisguiseId) {
        this.reqDisguiseId = reqDisguiseId;
    }

    public String getRespDisguiseId() {
        return respDisguiseId;
    }

    public void setRespDisguiseId(String respDisguiseId) {
        this.respDisguiseId = respDisguiseId;
    }

    public Integer getProxyEnabled() {
        return proxyEnabled;
    }

    public void setProxyEnabled(Integer proxyEnabled) {
        this.proxyEnabled = proxyEnabled;
    }

    public String getProxyType() {
        return proxyType;
    }

    public void setProxyType(String proxyType) {
        this.proxyType = proxyType;
    }

    public String getProxyHost() {
        return proxyHost;
    }

    public void setProxyHost(String proxyHost) {
        this.proxyHost = proxyHost;
    }

    public Integer getProxyPort() {
        return proxyPort;
    }

    public void setProxyPort(Integer proxyPort) {
        this.proxyPort = proxyPort;
    }

    public Integer getBalanceEnabled() {
        return balanceEnabled;
    }

    public void setBalanceEnabled(Integer balanceEnabled) {
        this.balanceEnabled = balanceEnabled;
    }

    public Integer getMaxReqCount() {
        return maxReqCount;
    }

    public void setMaxReqCount(Integer maxReqCount) {
        this.maxReqCount = maxReqCount;
    }

    public String getPermission() {
        return permission;
    }

    public void setPermission(String permission) {
        this.permission = permission;
    }

    public String getLastHeartbeat() {
        return lastHeartbeat;
    }

    public void setLastHeartbeat(String lastHeartbeat) {
        this.lastHeartbeat = lastHeartbeat;
    }

    public Integer getHeartbeatInterval() {
        return heartbeatInterval;
    }

    public void setHeartbeatInterval(Integer heartbeatInterval) {
        this.heartbeatInterval = heartbeatInterval;
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

    public String getUrlStrategy() {
        return urlStrategy;
    }

    public void setUrlStrategy(String urlStrategy) {
        this.urlStrategy = urlStrategy;
    }

    public String getPaddingStrategy() {
        return paddingStrategy;
    }

    public void setPaddingStrategy(String paddingStrategy) {
        this.paddingStrategy = paddingStrategy;
    }

    public String getHeaderNoiseStrategy() {
        return headerNoiseStrategy;
    }

    public void setHeaderNoiseStrategy(String headerNoiseStrategy) {
        this.headerNoiseStrategy = headerNoiseStrategy;
    }

    public String getTlsFingerprintStrategy() {
        return tlsFingerprintStrategy;
    }

    public void setTlsFingerprintStrategy(String tlsFingerprintStrategy) {
        this.tlsFingerprintStrategy = tlsFingerprintStrategy;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Puppet puppet = (Puppet) o;
        return Objects.equals(puppetId, puppet.puppetId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(puppetId);
    }

    @Override
    public String toString() {
        StringBuffer sb = new StringBuffer();
        sb.append("Puppet{");
        sb.append("puppetId='").append(puppetId).append('\'');
        sb.append(", puppetName='").append(puppetName).append('\'');
        sb.append(", createByUserId='").append(createByUserId).append('\'');
        sb.append(", balanceEnabled=").append(balanceEnabled);
        sb.append(", permission='").append(permission).append('\'');
        sb.append('}');
        return sb.toString();
    }
}
