package org.leo.core.entity;

import java.util.Objects;

/**
 * Puppet模型
 * 
 * @author LeoSpring
 * @version 2.1
 */
public class Puppet {
    /** 一次操作允许发送的最少请求数；包含首次请求。 */
    public static final int MIN_REQUEST_COUNT = 1;

    /** 一次操作允许发送的最多请求数；包含首次请求。 */
    public static final int MAX_REQUEST_COUNT = 10;

    /** 默认只发送首次请求，不自动重试。 */
    public static final int DEFAULT_MAX_REQUEST_COUNT = MIN_REQUEST_COUNT;

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
    /** Java PayloadCodec AES key supplied for this node. */
    private String payloadKey;
    private Integer proxyEnabled;
    private String proxyType;
    private String proxyHost;
    private Integer proxyPort;
    /**
     * 一次操作最多发送的请求总数，包含首次请求。
     * 例如 1 表示不重试，3 表示首次请求失败后最多再重试 2 次。
     */
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

    /** Java Component 运行时类名画像（JSON 格式存储） */
    private String componentClassNameStrategy;

    /** 节点类型 */
    private String type;

    public Puppet() {
        this.maxReqCount = DEFAULT_MAX_REQUEST_COUNT;
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

    public String getPayloadKey() {
        return payloadKey;
    }

    public void setPayloadKey(String payloadKey) {
        this.payloadKey = payloadKey;
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

    public Integer getMaxReqCount() {
        return maxReqCount;
    }

    public void setMaxReqCount(Integer maxReqCount) {
        this.maxReqCount = maxReqCount;
    }

    /** 校验并返回最大请求总数。 */
    public static int requireValidMaxRequestCount(Integer maxReqCount) {
        if (maxReqCount == null
                || maxReqCount < MIN_REQUEST_COUNT
                || maxReqCount > MAX_REQUEST_COUNT) {
            throw new IllegalArgumentException("最大请求数必须在 " + MIN_REQUEST_COUNT
                    + " 到 " + MAX_REQUEST_COUNT + " 之间（包含首次请求）");
        }
        return maxReqCount;
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

    public String getComponentClassNameStrategy() {
        return componentClassNameStrategy;
    }

    public void setComponentClassNameStrategy(String componentClassNameStrategy) {
        this.componentClassNameStrategy = componentClassNameStrategy;
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
        sb.append(", permission='").append(permission).append('\'');
        sb.append('}');
        return sb.toString();
    }
}
