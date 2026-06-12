package org.leo.core.entity;

import java.util.Date;
import java.util.Map;

public class PuppetJdbc {
    private String connId;

    // 连接名称
    private String connName;

    // 所属的puppet ID
    private String puppetId;

    // 数据库类型 (mysql, postgresql, sqlserver, oracle, sqlite)
    private String dbType;

    // 主机地址
    private String host;

    // 端口号
    private Integer port;

    // 数据库名/服务名/文件路径
    private String databaseName;

    // 用户名
    private String username;

    // 密码 (加密存储)
    private String password;

    // JDBC URL模板
    private String urlTemplate;

    // 完整的JDBC连接字符串
    private String jdbcUrl;

    // JDBC驱动类名
    private String driverClass;

    // 额外连接参数 (JSON格式)
    private String connectionParams;

    // 状态 (1:启用 0:禁用)
    private Integer status;

    // 测试状态 (0:未测试 1:连接成功 2:连接失败)
    private Integer testStatus;

    // 最后一次测试时间
    private Date lastTestTime;

    // 最后一次测试结果信息
    private String lastTestMessage;

    // 最大连接数
    private Integer maxConnections;

    // 连接超时时间(秒)
    private Integer timeoutSeconds;

    // 创建用户ID
    private String createUserId;

    // 所属团队ID (NULL表示个人连接)
    private String teamId;

    // 是否公开 (0:私有 1:公开)
    private Integer isPublic;

    // 创建时间
    private Date createTime;

    // 更新时间
    private Date updateTime;

    // 描述
    private String description;

    // 备注
    private String remark;

    // 扩展字段，用于存储解析后的连接参数
    private Map<String, String> parsedParams;

    // 构造函数
    public PuppetJdbc() {
        this.status = 1;
        this.testStatus = 0;
        this.maxConnections = 10;
        this.timeoutSeconds = 30;
        this.isPublic = 0;
        this.createTime = new Date();
        this.updateTime = new Date();
    }

    // 带参数的构造函数
    public PuppetJdbc(String connName, String puppetId, String dbType, String host, Integer port, String databaseName) {
        this();
        this.connName = connName;
        this.puppetId = puppetId;
        this.dbType = dbType;
        this.host = host;
        this.port = port;
        this.databaseName = databaseName;
    }

    // Getter和Setter方法
    public String getConnId() {
        return connId;
    }

    public void setConnId(String connId) {
        this.connId = connId;
    }

    public String getConnName() {
        return connName;
    }

    public void setConnName(String connName) {
        this.connName = connName;
    }

    public String getPuppetId() {
        return puppetId;
    }

    public void setPuppetId(String puppetId) {
        this.puppetId = puppetId;
    }

    public String getDbType() {
        return dbType;
    }

    public void setDbType(String dbType) {
        this.dbType = dbType;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public Integer getPort() {
        return port;
    }

    public void setPort(Integer port) {
        this.port = port;
    }

    public String getDatabaseName() {
        return databaseName;
    }

    public void setDatabaseName(String databaseName) {
        this.databaseName = databaseName;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getUrlTemplate() {
        return urlTemplate;
    }

    public void setUrlTemplate(String urlTemplate) {
        this.urlTemplate = urlTemplate;
    }

    public String getJdbcUrl() {
        return jdbcUrl;
    }

    public void setJdbcUrl(String jdbcUrl) {
        this.jdbcUrl = jdbcUrl;
    }

    public String getDriverClass() {
        return driverClass;
    }

    public void setDriverClass(String driverClass) {
        this.driverClass = driverClass;
    }

    public String getConnectionParams() {
        return connectionParams;
    }

    public void setConnectionParams(String connectionParams) {
        this.connectionParams = connectionParams;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public Integer getTestStatus() {
        return testStatus;
    }

    public void setTestStatus(Integer testStatus) {
        this.testStatus = testStatus;
    }

    public Date getLastTestTime() {
        return lastTestTime;
    }

    public void setLastTestTime(Date lastTestTime) {
        this.lastTestTime = lastTestTime;
    }

    public String getLastTestMessage() {
        return lastTestMessage;
    }

    public void setLastTestMessage(String lastTestMessage) {
        this.lastTestMessage = lastTestMessage;
    }

    public Integer getMaxConnections() {
        return maxConnections;
    }

    public void setMaxConnections(Integer maxConnections) {
        this.maxConnections = maxConnections;
    }

    public Integer getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(Integer timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    public String getCreateUserId() {
        return createUserId;
    }

    public void setCreateUserId(String createUserId) {
        this.createUserId = createUserId;
    }

    public String getTeamId() {
        return teamId;
    }

    public void setTeamId(String teamId) {
        this.teamId = teamId;
    }

    public Integer getIsPublic() {
        return isPublic;
    }

    public void setIsPublic(Integer isPublic) {
        this.isPublic = isPublic;
    }

    public Date getCreateTime() {
        return createTime;
    }

    public void setCreateTime(Date createTime) {
        this.createTime = createTime;
    }

    public Date getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(Date updateTime) {
        this.updateTime = updateTime;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }

    public Map<String, String> getParsedParams() {
        return parsedParams;
    }

    public void setParsedParams(Map<String, String> parsedParams) {
        this.parsedParams = parsedParams;
    }

    /**
     * 检查连接是否启用
     */
    public boolean isEnabled() {
        return status != null && status == 1;
    }

    /**
     * 检查连接是否公开
     */
    public boolean isPublic() {
        return isPublic != null && isPublic == 1;
    }

    /**
     * 检查连接是否测试成功
     */
    public boolean isTestedSuccessfully() {
        return testStatus != null && testStatus == 1;
    }

    /**
     * 检查连接是否测试失败
     */
    public boolean isTestedFailed() {
        return testStatus != null && testStatus == 2;
    }

    /**
     * 更新测试状态
     */
    public void updateTestStatus(boolean success, String message) {
        this.testStatus = success ? 1 : 2;
        this.lastTestMessage = message;
        this.lastTestTime = new Date();
    }

    @Override
    public String toString() {
        return "DatabaseConnection{" +
               "connId='" + connId + '\'' +
               ", connName='" + connName + '\'' +
               ", puppetId='" + puppetId + '\'' +
               ", dbType='" + dbType + '\'' +
               ", host='" + host + '\'' +
               ", port=" + port +
               ", databaseName='" + databaseName + '\'' +
               ", username='" + username + '\'' +
               ", status=" + status +
               ", testStatus=" + testStatus +
               '}';
    }
}
