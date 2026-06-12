package org.leo.service;

import org.leo.core.entity.PuppetJdbc;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import org.leo.dao.mapper.PuppetJdbcMapper;
import java.util.Date;
import java.util.List;

/**
 * PuppetJdbc服务类
 * 
 * @author LeoSpring
 * @version 2.1
 */
@Service
public class PuppetJdbcService {
    
    // 数据库类型常量
    private static final String DB_TYPE_MYSQL = "mysql";
    private static final String DB_TYPE_POSTGRESQL = "postgresql";
    private static final String DB_TYPE_SQLSERVER = "sqlserver";
    private static final String DB_TYPE_ORACLE = "oracle";
    private static final String DB_TYPE_SQLITE = "sqlite";
    
    // JDBC URL前缀常量
    private static final String JDBC_MYSQL_PREFIX = "jdbc:mysql:";
    private static final String JDBC_POSTGRESQL_PREFIX = "jdbc:postgresql:";
    private static final String JDBC_SQLSERVER_PREFIX = "jdbc:sqlserver:";
    private static final String JDBC_MICROSOFT_SQLSERVER_PREFIX = "jdbc:microsoft:sqlserver:";
    private static final String JDBC_ORACLE_PREFIX = "jdbc:oracle:";
    private static final String JDBC_SQLITE_PREFIX = "jdbc:sqlite:";
    
    // 驱动类常量
    private static final String DRIVER_MYSQL = "com.mysql.cj.jdbc.Driver";
    private static final String DRIVER_POSTGRESQL = "org.postgresql.Driver";
    private static final String DRIVER_SQLSERVER = "com.microsoft.sqlserver.jdbc.SQLServerDriver";
    private static final String DRIVER_ORACLE = "oracle.jdbc.driver.OracleDriver";
    private static final String DRIVER_SQLITE = "org.sqlite.JDBC";
    
    // URL模板常量
    private static final String URL_TEMPLATE_MYSQL = "jdbc:mysql://{host}:{port}/{database}";
    private static final String URL_TEMPLATE_POSTGRESQL = "jdbc:postgresql://{host}:{port}/{database}";
    private static final String URL_TEMPLATE_SQLSERVER = "jdbc:sqlserver://{host}:{port};databaseName={database}";
    private static final String URL_TEMPLATE_ORACLE = "jdbc:oracle:thin:@//{host}:{port}/{database}";
    private static final String URL_TEMPLATE_SQLITE = "jdbc:sqlite:{database}";
    
    // 其他常量
    private static final int TEST_STATUS_UNTESTED = 0;
    private static final String LOCALHOST = "localhost";
    private static final String DATABASE_NAME_PARAM = "databaseName=";
    private static final String ORACLE_CONNECTION_PREFIX = "@//";
    
    private final PuppetJdbcMapper puppetJdbcMapper;

    @Autowired
    public PuppetJdbcService(PuppetJdbcMapper puppetJdbcMapper) {
        this.puppetJdbcMapper = puppetJdbcMapper;
    }

    /**
     * 根据ID查询数据库连接
     */
    public PuppetJdbc findById(String connId) {
        return puppetJdbcMapper.selectById(connId);
    }

    /**
     * 根据名称查询数据库连接
     */
    public PuppetJdbc findByName(String connName) {
        return puppetJdbcMapper.selectByName(connName);
    }

    /**
     * 根据puppet ID查询数据库连接列表
     */
    public List<PuppetJdbc> findByPuppetId(String puppetId) {
        return puppetJdbcMapper.selectByPuppetId(puppetId);
    }

    /**
     * 根据用户ID查询数据库连接列表
     */
    public List<PuppetJdbc> findByUserId(String userId) {
        return puppetJdbcMapper.selectByUserId(userId);
    }

    /**
     * 根据团队ID查询数据库连接列表
     */
    public List<PuppetJdbc> findByTeamId(String teamId) {
        return puppetJdbcMapper.selectByTeamId(teamId);
    }

    /**
     * 保存或更新数据库连接
     */
    public boolean saveOrUpdate(PuppetJdbc connection) throws Exception {
        // 构建JDBC URL和驱动类
        buildJdbcUrl(connection);

        if (connection.getConnId() == null || connection.getConnId().isBlank()) {
            // 新增
            connection.setConnId(java.util.UUID.randomUUID().toString());
            connection.setCreateTime(new Date());
            connection.setUpdateTime(new Date());
            connection.setTestStatus(TEST_STATUS_UNTESTED); // 未测试
            
            int result = puppetJdbcMapper.insert(connection);
            return result > 0;
        } else {
            // 更新
            connection.setUpdateTime(new Date());
            int result = puppetJdbcMapper.update(connection);
            return result > 0;
        }
    }

    /**
     * 删除数据库连接
     */
    public boolean deleteById(String connId) {
        int result = puppetJdbcMapper.deleteById(connId);
        return result > 0;
    }

    /**
     * 检查连接名称是否存在
     */
    public boolean existsByName(String connName, String excludeConnId) {
        return puppetJdbcMapper.existsByName(connName, excludeConnId);
    }

    /**
     * 构建JDBC URL和驱动类
     * 如果已提供jdbcUrl，则从jdbcUrl解析信息；否则根据参数构建
     */
    private void buildJdbcUrl(PuppetJdbc connection) {
        // 如果已经提供了jdbcUrl，则从jdbcUrl解析信息
        if (connection.getJdbcUrl() != null && !connection.getJdbcUrl().isBlank()) {
            parseJdbcUrl(connection);
            return;
        }

        // 否则根据参数构建jdbcUrl
        String dbType = connection.getDbType();
        String host = connection.getHost();
        Integer port = connection.getPort();
        String databaseName = connection.getDatabaseName();
        String connectionParams = connection.getConnectionParams();

        if (dbType == null || host == null || port == null) {
            return;
        }

        StringBuilder jdbcUrl = new StringBuilder();
        String driverClass = null;
        String urlTemplate = null;

        if (DB_TYPE_MYSQL.equalsIgnoreCase(dbType)) {
            driverClass = DRIVER_MYSQL;
            urlTemplate = URL_TEMPLATE_MYSQL;
            jdbcUrl.append(JDBC_MYSQL_PREFIX).append("//").append(host).append(":").append(port);
            if (databaseName != null && !databaseName.isBlank()) {
                jdbcUrl.append("/").append(databaseName);
            }
        } else if (DB_TYPE_POSTGRESQL.equalsIgnoreCase(dbType)) {
            driverClass = DRIVER_POSTGRESQL;
            urlTemplate = URL_TEMPLATE_POSTGRESQL;
            jdbcUrl.append(JDBC_POSTGRESQL_PREFIX).append("//").append(host).append(":").append(port);
            if (databaseName != null && !databaseName.isBlank()) {
                jdbcUrl.append("/").append(databaseName);
            }
        } else if (DB_TYPE_SQLSERVER.equalsIgnoreCase(dbType)) {
            driverClass = DRIVER_SQLSERVER;
            urlTemplate = URL_TEMPLATE_SQLSERVER;
            jdbcUrl.append(JDBC_SQLSERVER_PREFIX).append("//").append(host).append(":").append(port);
            if (databaseName != null && !databaseName.isBlank()) {
                jdbcUrl.append(";").append(DATABASE_NAME_PARAM).append(databaseName);
            }
        } else if (DB_TYPE_ORACLE.equalsIgnoreCase(dbType)) {
            driverClass = DRIVER_ORACLE;
            urlTemplate = URL_TEMPLATE_ORACLE;
            jdbcUrl.append(JDBC_ORACLE_PREFIX).append("thin:").append(ORACLE_CONNECTION_PREFIX).append(host).append(":").append(port);
            if (databaseName != null && !databaseName.isBlank()) {
                jdbcUrl.append("/").append(databaseName);
            }
        } else if (DB_TYPE_SQLITE.equalsIgnoreCase(dbType)) {
            driverClass = DRIVER_SQLITE;
            urlTemplate = URL_TEMPLATE_SQLITE;
            jdbcUrl.append(JDBC_SQLITE_PREFIX);
            if (databaseName != null && !databaseName.isBlank()) {
                jdbcUrl.append(databaseName);
            }
        }

        // 添加额外连接参数
        if (connectionParams != null && !connectionParams.isBlank()) {
            if (jdbcUrl.indexOf("?") > 0) {
                jdbcUrl.append("&").append(connectionParams);
            } else {
                jdbcUrl.append("?").append(connectionParams);
            }
        }

        connection.setJdbcUrl(jdbcUrl.toString());
        connection.setDriverClass(driverClass);
        connection.setUrlTemplate(urlTemplate);
    }

    /**
     * 从JDBC URL解析数据库类型、驱动类等信息（公共方法，供Controller调用）
     */
    public void parseJdbcUrlInfo(PuppetJdbc connection) {
        parseJdbcUrl(connection);
    }

    /**
     * 从JDBC URL解析数据库类型、驱动类等信息
     */
    private void parseJdbcUrl(PuppetJdbc connection) {
        String jdbcUrl = connection.getJdbcUrl();
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            return;
        }

        String urlLower = jdbcUrl.toLowerCase();
        String driverClass = null;
        String dbType = null;
        String urlTemplate = null;

        // 解析数据库类型和驱动类
        if (urlLower.startsWith(JDBC_MYSQL_PREFIX)) {
            dbType = DB_TYPE_MYSQL;
            driverClass = DRIVER_MYSQL;
            urlTemplate = URL_TEMPLATE_MYSQL;
        } else if (urlLower.startsWith(JDBC_POSTGRESQL_PREFIX)) {
            dbType = DB_TYPE_POSTGRESQL;
            driverClass = DRIVER_POSTGRESQL;
            urlTemplate = URL_TEMPLATE_POSTGRESQL;
        } else if (urlLower.startsWith(JDBC_SQLSERVER_PREFIX) || urlLower.startsWith(JDBC_MICROSOFT_SQLSERVER_PREFIX)) {
            dbType = DB_TYPE_SQLSERVER;
            driverClass = DRIVER_SQLSERVER;
            urlTemplate = URL_TEMPLATE_SQLSERVER;
        } else if (urlLower.startsWith(JDBC_ORACLE_PREFIX)) {
            dbType = DB_TYPE_ORACLE;
            driverClass = DRIVER_ORACLE;
            urlTemplate = URL_TEMPLATE_ORACLE;
        } else if (urlLower.startsWith(JDBC_SQLITE_PREFIX)) {
            dbType = DB_TYPE_SQLITE;
            driverClass = DRIVER_SQLITE;
            urlTemplate = URL_TEMPLATE_SQLITE;
        }

        // 如果解析出数据库类型，则设置
        if (dbType != null && connection.getDbType() == null) {
            connection.setDbType(dbType);
        }

        // 设置驱动类
        if (driverClass != null && connection.getDriverClass() == null) {
            connection.setDriverClass(driverClass);
        }

        // 设置URL模板
        if (urlTemplate != null) {
            connection.setUrlTemplate(urlTemplate);
        }

        // 尝试从URL中解析host、port、databaseName（如果未设置）
        if (connection.getHost() == null || connection.getPort() == null) {
            parseHostPortFromUrl(jdbcUrl, connection);
        }
    }

    /**
     * 从JDBC URL中解析host、port、databaseName
     */
    private void parseHostPortFromUrl(String jdbcUrl, PuppetJdbc connection) {
        try {
            // MySQL: jdbc:mysql://host:port/database
            // PostgreSQL: jdbc:postgresql://host:port/database
            // SQL Server: jdbc:sqlserver://host:port;databaseName=database
            // Oracle: jdbc:oracle:thin:@//host:port/database
            // SQLite: jdbc:sqlite:path/to/database.db

            String urlLower = jdbcUrl.toLowerCase();

            if (urlLower.startsWith(JDBC_SQLITE_PREFIX)) {
                // SQLite: jdbc:sqlite:path/to/database.db
                String path = jdbcUrl.substring(JDBC_SQLITE_PREFIX.length());
                if (connection.getDatabaseName() == null) {
                    connection.setDatabaseName(path);
                }
                if (connection.getHost() == null) {
                    connection.setHost(LOCALHOST);
                }
                if (connection.getPort() == null) {
                    connection.setPort(0);
                }
            } else if (urlLower.startsWith(JDBC_ORACLE_PREFIX)) {
                // Oracle: jdbc:oracle:thin:@//host:port/database
                String urlPart = jdbcUrl.substring(jdbcUrl.indexOf(ORACLE_CONNECTION_PREFIX) + ORACLE_CONNECTION_PREFIX.length());
                int portIndex = urlPart.indexOf(":");
                int dbIndex = urlPart.indexOf("/");
                
                if (portIndex > 0 && dbIndex > portIndex) {
                    String host = urlPart.substring(0, portIndex);
                    String portStr = urlPart.substring(portIndex + 1, dbIndex);
                    String database = urlPart.substring(dbIndex + 1);
                    
                    if (connection.getHost() == null) {
                        connection.setHost(host);
                    }
                    if (connection.getPort() == null) {
                        try {
                            connection.setPort(Integer.parseInt(portStr));
                        } catch (NumberFormatException e) {
                            // 忽略
                        }
                    }
                    if (connection.getDatabaseName() == null) {
                        connection.setDatabaseName(database);
                    }
                }
            } else if (urlLower.startsWith(JDBC_SQLSERVER_PREFIX) || urlLower.startsWith(JDBC_MICROSOFT_SQLSERVER_PREFIX)) {
                // SQL Server: jdbc:sqlserver://host:port;databaseName=database
                String urlPart = jdbcUrl.substring(jdbcUrl.indexOf("://") + 3);
                int portIndex = urlPart.indexOf(":");
                
                if (portIndex > 0) {
                    String host = urlPart.substring(0, portIndex);
                    String rest = urlPart.substring(portIndex + 1);
                    int portEnd = rest.indexOf(";");
                    if (portEnd < 0) portEnd = rest.length();
                    String portStr = rest.substring(0, portEnd);
                    
                    if (connection.getHost() == null) {
                        connection.setHost(host);
                    }
                    if (connection.getPort() == null) {
                        try {
                            connection.setPort(Integer.parseInt(portStr));
                        } catch (NumberFormatException e) {
                            // 忽略
                        }
                    }
                    
                    // 解析databaseName
                    int dbNameIndex = urlPart.indexOf(DATABASE_NAME_PARAM);
                    if (dbNameIndex > 0) {
                        String dbNamePart = urlPart.substring(dbNameIndex + DATABASE_NAME_PARAM.length());
                        int dbNameEnd = dbNamePart.indexOf(";");
                        if (dbNameEnd < 0) dbNameEnd = dbNamePart.length();
                        String database = dbNamePart.substring(0, dbNameEnd);
                        if (connection.getDatabaseName() == null) {
                            connection.setDatabaseName(database);
                        }
                    }
                }
            } else {
                // MySQL/PostgreSQL: jdbc:mysql://host:port/database
                String urlPart = jdbcUrl.substring(jdbcUrl.indexOf("://") + 3);
                int portIndex = urlPart.indexOf(":");
                int dbIndex = urlPart.indexOf("/");
                
                if (portIndex > 0) {
                    String host = urlPart.substring(0, portIndex);
                    if (connection.getHost() == null) {
                        connection.setHost(host);
                    }
                    
                    if (dbIndex > portIndex) {
                        String portStr = urlPart.substring(portIndex + 1, dbIndex);
                        if (connection.getPort() == null) {
                            try {
                                connection.setPort(Integer.parseInt(portStr));
                            } catch (NumberFormatException e) {
                                // 忽略
                            }
                        }
                        
                        String database = urlPart.substring(dbIndex + 1);
                        // 移除查询参数
                        int paramIndex = database.indexOf("?");
                        if (paramIndex > 0) {
                            database = database.substring(0, paramIndex);
                        }
                        if (connection.getDatabaseName() == null && !database.isEmpty()) {
                            connection.setDatabaseName(database);
                        }
                    } else {
                        String portStr = urlPart.substring(portIndex + 1);
                        int paramIndex = portStr.indexOf("?");
                        if (paramIndex > 0) {
                            portStr = portStr.substring(0, paramIndex);
                        }
                        if (connection.getPort() == null) {
                            try {
                                connection.setPort(Integer.parseInt(portStr));
                            } catch (NumberFormatException e) {
                                // 忽略
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            // 解析失败，忽略
        }
    }

    /**
     * 更新测试状态
     */
    public boolean updateTestStatus(String connId, Integer testStatus, String testMessage) {
        int result = puppetJdbcMapper.updateTestStatus(connId, testStatus, testMessage);
        return result > 0;
    }

    /**
     * 批量更新状态
     */
    public boolean batchUpdateStatus(List<String> connIds, Integer status) {
        int result = puppetJdbcMapper.batchUpdateStatus(connIds, status);
        return result > 0;
    }
}
