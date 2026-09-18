package org.leo.core.component;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.Reader;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.SQLXML;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * 数据库操作组件。
 *
 * <p>组件作为单 class payload 运行在未知的 Java 6+ 容器中，因此只依赖 JDK API，
 * 并在组件边界完成 JDBC 驱动发现、资源限制、类型归一化和错误结构化。</p>
 */
public class DatabaseComponent implements Runnable {

    private static final String[] KNOWN_DRIVER_CLASSES = new String[]{
            "com.mysql.cj.jdbc.Driver",
            "com.mysql.jdbc.Driver",
            "org.mariadb.jdbc.Driver",
            "org.postgresql.Driver",
            "com.microsoft.sqlserver.jdbc.SQLServerDriver",
            "net.sourceforge.jtds.jdbc.Driver",
            "oracle.jdbc.OracleDriver",
            "oracle.jdbc.driver.OracleDriver",
            "org.sqlite.JDBC",
            "org.h2.Driver",
            "com.ibm.db2.jcc.DB2Driver",
            "com.clickhouse.jdbc.ClickHouseDriver",
            "org.duckdb.DuckDBDriver",
            "net.snowflake.client.jdbc.SnowflakeDriver"
    };

    private static final int DEFAULT_TIMEOUT_SECONDS = 30;
    private static final int MAX_TIMEOUT_SECONDS = 300;
    private static final int DEFAULT_MAX_ROWS = 1000;
    private static final int MAX_ROWS = 100000;
    private static final int DEFAULT_MAX_RESULT_BYTES = 4 * 1024 * 1024;
    private static final int MAX_RESULT_BYTES = 16 * 1024 * 1024;
    private static final int DEFAULT_MAX_CELL_BYTES = 1024 * 1024;
    private static final int MAX_CELL_BYTES = 4 * 1024 * 1024;
    private static final int DEFAULT_FETCH_SIZE = 200;
    private static final int MAX_FETCH_SIZE = 10000;

    private HashMap<String, Object> params;
    private HashMap<String, Object> results;
    private String selectedLoader;
    private boolean cellValueTruncated;

    public void run() {
        java.lang.reflect.InvocationHandler h =
                (java.lang.reflect.InvocationHandler) Thread.currentThread().getContextClassLoader();
        try {
            params = copyStringObjectMap(h.invoke(null, null, null));
            results = new HashMap<String, Object>();
            invoke();
        } catch (Throwable t) {
            if (results == null) results = new HashMap<String, Object>();
            putEmptyResults();
            putError(500, "COMPONENT_ERROR", safeMessage(t), null, false);
        }
        if (results != null) {
            try { h.invoke(null, null, new Object[]{results}); } catch (Throwable ignored) {}
        }
    }

    public void invoke() {
        if (results == null) results = new HashMap<String, Object>();
        if ("capabilities".equalsIgnoreCase(getStringParam("operation"))) {
            inspectRuntimeCapabilities();
            return;
        }
        putEmptyResults();

        String provider = getStringParam("provider");
        String url = getStringParam("jdbcUrl");
        String user = getStringParam("username");
        String password = getStringParam("password");
        String sql = getStringParam("sql");
        String driverClass = getStringParam("driverClass");

        Connection connection = null;
        PreparedStatement statement = null;
        ResultSet resultSet = null;
        try {
            if (provider != null && provider.trim().length() > 0
                    && !"jdbc".equalsIgnoreCase(provider.trim())) {
                throw new IllegalArgumentException("DatabaseComponent 仅支持 jdbc provider");
            }
            if (isBlank(driverClass) || isBlank(url) || isBlank(sql)) {
                throw new IllegalArgumentException("缺少必填参数: driverClass、jdbcUrl 或 sql");
            }

            int timeoutSeconds = getBoundedIntParam("queryTimeoutSeconds",
                    getBoundedIntParam("timeoutSeconds", DEFAULT_TIMEOUT_SECONDS, 0, MAX_TIMEOUT_SECONDS),
                    0, MAX_TIMEOUT_SECONDS);
            int maxRows = getBoundedIntParam("maxRows", DEFAULT_MAX_ROWS, 1, MAX_ROWS);
            int maxResultBytes = getBoundedIntParam("maxResultBytes",
                    DEFAULT_MAX_RESULT_BYTES, 1024, MAX_RESULT_BYTES);
            int maxCellBytes = getBoundedIntParam("maxCellBytes",
                    DEFAULT_MAX_CELL_BYTES, 256, MAX_CELL_BYTES);
            int fetchSize = getBoundedIntParam("fetchSize", DEFAULT_FETCH_SIZE, 0, MAX_FETCH_SIZE);

            Properties connectionProperties = connectionProperties();
            connection = openConnection(driverClass.trim(), url.trim(), user, password, connectionProperties);
            statement = prepareStatement(connection, sql);
            bindParameters(statement, params.get("parameters"));
            if (timeoutSeconds > 0) statement.setQueryTimeout(timeoutSeconds);
            if (fetchSize > 0) {
                try { statement.setFetchSize(fetchSize); } catch (SQLException ignored) {}
            }

            boolean hasResult = statement.execute();
            if (hasResult) {
                resultSet = statement.getResultSet();
                readResultSet(resultSet, maxRows, maxResultBytes, maxCellBytes);
            } else {
                results.put("affectedRows", Integer.valueOf(Math.max(0, statement.getUpdateCount())));
                results.put("generatedKey", readGeneratedKey(statement, maxCellBytes));
            }

            results.put("runtimeMetadata", runtimeMetadata(connection, driverClass, timeoutSeconds,
                    maxRows, maxResultBytes, maxCellBytes));
            results.put("code", Integer.valueOf(200));
            results.put("msg", "执行成功");
        } catch (ClassNotFoundException error) {
            putError(503, "DRIVER_NOT_FOUND", redact(error.getMessage(), password, url), null, false);
        } catch (IllegalArgumentException error) {
            String category = safeMessage(error).indexOf("JDBC URL") >= 0
                    ? "URL_MISMATCH" : "INVALID_ARGUMENT";
            putError(400, category, redact(safeMessage(error), password, url), null, false);
        } catch (SQLException error) {
            putSqlError(error, password, url);
        } catch (Exception error) {
            putError(500, "EXECUTION_ERROR", redact(safeMessage(error), password, url), null, false);
        } finally {
            closeResource(resultSet);
            closeResource(statement);
            closeResource(connection);
        }
    }

    private ArrayList<HashMap<String, Object>> readColumns(ResultSetMetaData metadata) throws SQLException {
        ArrayList<HashMap<String, Object>> columns = new ArrayList<HashMap<String, Object>>();
        HashSet<String> usedKeys = new HashSet<String>();
        int columnCount = metadata.getColumnCount();
        for (int index = 1; index <= columnCount; index++) {
            String label = safeColumnLabel(metadata, index);
            String nativeType = safeColumnTypeName(metadata, index);
            HashMap<String, Object> column = new HashMap<String, Object>();
            column.put("name", uniqueColumnKey(label, usedKeys));
            column.put("label", label);
            column.put("nativeName", safeColumnName(metadata, index));
            column.put("type", nativeType);
            column.put("nativeType", nativeType);
            column.put("jdbcType", safeColumnType(metadata, index));
            column.put("precision", safeColumnPrecision(metadata, index));
            column.put("scale", safeColumnScale(metadata, index));
            column.put("nullable", safeColumnNullable(metadata, index));
            columns.add(column);
        }
        return columns;
    }

    private void readResultSet(ResultSet resultSet, int maxRows, int maxResultBytes,
                               int maxCellBytes) throws Exception {
        ArrayList<HashMap<String, Object>> columns = readColumns(resultSet.getMetaData());
        ArrayList<HashMap<String, Object>> rows = new ArrayList<HashMap<String, Object>>();
        String truncationReason = null;
        int resultBytes = 0;
        while (resultSet.next()) {
            if (rows.size() >= maxRows) {
                truncationReason = "MAX_ROWS";
                break;
            }
            HashMap<String, Object> row = new HashMap<String, Object>();
            long rowBytes = 0L;
            boolean rowCellTruncated = false;
            for (int index = 0; index < columns.size(); index++) {
                cellValueTruncated = false;
                Object value = normalizeJdbcValue(resultSet.getObject(index + 1), maxCellBytes);
                String key = (String) columns.get(index).get("name");
                row.put(key, value);
                rowBytes += utf8Length(key) + estimateValueBytes(value);
                if (cellValueTruncated) rowCellTruncated = true;
            }
            if (((long) resultBytes) + rowBytes > maxResultBytes) {
                truncationReason = "MAX_RESULT_BYTES";
                break;
            }
            rows.add(row);
            resultBytes += rowBytes;
            if (rowCellTruncated) truncationReason = "MAX_CELL_BYTES";
        }
        // Publish only a fully read result, so JDBC failures retain the empty error response.
        results.put("columns", columns);
        results.put("rows", rows);
        results.put("rowCount", Integer.valueOf(rows.size()));
        results.put("truncated", Boolean.valueOf(truncationReason != null));
        results.put("truncationReason", truncationReason);
        results.put("resultBytes", Integer.valueOf(resultBytes));
    }

    private void putEmptyResults() {
        results.put("columns", new ArrayList<HashMap<String, Object>>());
        results.put("rows", new ArrayList<HashMap<String, Object>>());
        results.put("rowCount", Integer.valueOf(0));
        results.put("affectedRows", Integer.valueOf(0));
        results.put("generatedKey", null);
        results.put("truncated", Boolean.FALSE);
        results.put("truncationReason", null);
        results.put("resultBytes", Integer.valueOf(0));
    }

    private void inspectRuntimeCapabilities() {
        results.clear();
        ArrayList<HashMap<String, Object>> drivers = new ArrayList<HashMap<String, Object>>();
        HashSet<String> registeredNames = new HashSet<String>();
        try {
            Enumeration registered = DriverManager.getDrivers();
            while (registered.hasMoreElements()) {
                Object value = registered.nextElement();
                if (value instanceof Driver) {
                    registeredNames.add(value.getClass().getName());
                }
            }
        } catch (Throwable ignored) {
        }

        HashSet<String> candidates = new HashSet<String>();
        candidates.addAll(registeredNames);
        int index;
        for (index = 0; index < KNOWN_DRIVER_CLASSES.length; index++) {
            candidates.add(KNOWN_DRIVER_CLASSES[index]);
        }
        String requested = getStringParam("requestedDriver");
        requested = isBlank(requested) ? "" : requested.trim();
        if (requested.length() > 0) candidates.add(requested);

        ArrayList<ClassLoader> loaders = collectCandidateClassLoaders();
        boolean requestedAvailable = requested.length() == 0;
        ArrayList<String> candidateNames = new ArrayList<String>(candidates);
        Collections.sort(candidateNames);
        for (index = 0; index < candidateNames.size(); index++) {
            String className = candidateNames.get(index);
            boolean available = registeredNames.contains(className) || canLoadDriverClass(className, loaders);
            if (className.equals(requested)) requestedAvailable = available;
            if (available || className.equals(requested)) {
                HashMap<String, Object> item = new HashMap<String, Object>();
                item.put("id", className);
                item.put("className", className);
                item.put("available", Boolean.valueOf(available));
                item.put("registered", Boolean.valueOf(registeredNames.contains(className)));
                drivers.add(item);
            }
        }

        HashMap<String, Object> requestedStatus = new HashMap<String, Object>();
        requestedStatus.put("id", requested);
        requestedStatus.put("available", Boolean.valueOf(requestedAvailable));
        requestedStatus.put("message", isBlank(requested)
                ? "未指定 JDBC 驱动"
                : requestedAvailable ? "JDBC 驱动可加载" : "JDBC 驱动类不可用");

        HashMap<String, Object> constraints = new HashMap<String, Object>();
        constraints.put("requiresInstalledDriver", Boolean.TRUE);
        constraints.put("remoteInstallSupported", Boolean.FALSE);
        constraints.put("customConnectorSupported", Boolean.TRUE);

        results.put("code", Integer.valueOf(200));
        results.put("msg", "数据库运行时能力探测成功");
        results.put("runtime", "java");
        results.put("provider", "jdbc");
        results.put("available", Boolean.TRUE);
        results.put("drivers", drivers);
        results.put("requestedDriver", requestedStatus);
        results.put("constraints", constraints);
    }

    private boolean canLoadDriverClass(String className, List<ClassLoader> loaders) {
        for (ClassLoader loader : loaders) {
            try {
                Class candidate = Class.forName(className, false, loader);
                if (Driver.class.isAssignableFrom(candidate)) return true;
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    private void putSqlError(SQLException error, String password, String url) {
        String state = error.getSQLState();
        String category = "SQL_ERROR";
        int code = 422;
        boolean retryable = false;
        if (error instanceof SQLTimeoutException || "HYT00".equals(state) || "HYT01".equals(state)) {
            category = "QUERY_TIMEOUT";
            code = 504;
            retryable = true;
        } else if (state != null && state.startsWith("08")) {
            category = "CONNECTION_ERROR";
            code = 503;
            retryable = true;
        } else if (state != null && state.startsWith("28")) {
            category = "AUTHENTICATION_ERROR";
        } else if (state != null && state.startsWith("40")) {
            category = "TRANSACTION_ROLLBACK";
            retryable = true;
        }
        putError(code, category, redact(safeMessage(error), password, url), state, retryable);
        results.put("vendorCode", Integer.valueOf(error.getErrorCode()));
    }

    private void putError(int code, String category, String message, String sqlState, boolean retryable) {
        results.put("code", Integer.valueOf(code));
        results.put("msg", isBlank(message) ? category : message);
        results.put("errorCategory", category);
        results.put("sqlState", sqlState);
        results.put("retryable", Boolean.valueOf(retryable));
    }

    private PreparedStatement prepareStatement(Connection connection, String sql) throws SQLException {
        try {
            return connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
        } catch (SQLException ignored) {
            return connection.prepareStatement(sql);
        }
    }

    private void bindParameters(PreparedStatement statement, Object value) throws SQLException {
        if (value == null) return;
        if (!(value instanceof List)) {
            throw new IllegalArgumentException("parameters 必须是数组");
        }
        List parameters = (List) value;
        int index;
        for (index = 0; index < parameters.size(); index++) {
            Object parameter = parameters.get(index);
            if (parameter instanceof byte[]) {
                statement.setBytes(index + 1, (byte[]) parameter);
            } else {
                statement.setObject(index + 1, parameter);
            }
        }
    }

    private Object readGeneratedKey(PreparedStatement statement, int maxCellBytes) {
        ResultSet keys = null;
        try {
            keys = statement.getGeneratedKeys();
            if (keys != null && keys.next()) return normalizeJdbcValue(keys.getObject(1), maxCellBytes);
        } catch (Throwable ignored) {
        } finally {
            closeResource(keys);
        }
        return null;
    }

    private HashMap<String, Object> runtimeMetadata(Connection connection, String driverClass,
                                                     int timeoutSeconds, int maxRows,
                                                     int maxResultBytes, int maxCellBytes) {
        HashMap<String, Object> metadata = new HashMap<String, Object>();
        metadata.put("provider", "jdbc");
        metadata.put("driverClass", driverClass);
        metadata.put("classLoader", selectedLoader);
        metadata.put("queryTimeoutSeconds", Integer.valueOf(timeoutSeconds));
        metadata.put("maxRows", Integer.valueOf(maxRows));
        metadata.put("maxResultBytes", Integer.valueOf(maxResultBytes));
        metadata.put("maxCellBytes", Integer.valueOf(maxCellBytes));
        try {
            java.sql.DatabaseMetaData databaseMetadata = connection.getMetaData();
            metadata.put("driverName", databaseMetadata.getDriverName());
            metadata.put("driverVersion", databaseMetadata.getDriverVersion());
            metadata.put("databaseProduct", databaseMetadata.getDatabaseProductName());
            metadata.put("databaseVersion", databaseMetadata.getDatabaseProductVersion());
        } catch (Throwable ignored) {
        }
        return metadata;
    }

    private Properties connectionProperties() {
        Properties properties = new Properties();
        Object value = params.get("connectionProperties");
        if (value == null) return properties;
        if (!(value instanceof Map)) {
            throw new IllegalArgumentException("connectionProperties 必须是对象");
        }
        Map source = (Map) value;
        Iterator iterator = source.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry entry = (Map.Entry) iterator.next();
            if (entry.getKey() != null && entry.getValue() != null) {
                properties.setProperty(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
            }
        }
        return properties;
    }

    private Connection openConnection(String driverClassName, String url, String user,
                                      String password, Properties properties) throws Exception {
        Driver driver = loadDriver(driverClassName, url);
        if (!isBlank(user)) properties.setProperty("user", user);
        if (!isBlank(password) || !isBlank(user)) properties.setProperty("password", password == null ? "" : password);
        Connection connection = driver.connect(url, properties);
        if (connection == null) {
            throw new IllegalArgumentException("JDBC URL 与驱动不匹配: " + redactUrl(url));
        }
        return connection;
    }

    private Driver loadDriver(String driverClassName, String url) throws ClassNotFoundException {
        boolean driverClassFound = false;
        boolean driverRejectedUrl = false;
        String driverLoadError = null;
        selectedLoader = null;
        for (ClassLoader loader : collectCandidateClassLoaders()) {
            Class driverClass;
            try {
                driverClass = Class.forName(driverClassName, true, loader);
            } catch (Throwable ignored) {
                continue;
            }
            driverClassFound = true;
            try {
                Object instance = driverClass.newInstance();
                if (!(instance instanceof Driver)) {
                    driverLoadError = "类未实现 java.sql.Driver";
                    continue;
                }
                Driver driver = (Driver) instance;
                if (!driver.acceptsURL(url)) {
                    driverRejectedUrl = true;
                    continue;
                }
                selectedLoader = loader.getClass().getName();
                return driver;
            } catch (Throwable error) {
                driverLoadError = safeMessage(error);
            }
        }
        if (driverRejectedUrl) {
            throw new IllegalArgumentException("JDBC URL 与驱动不匹配: " + redactUrl(url));
        }
        if (driverClassFound) {
            throw new IllegalStateException("JDBC driver 初始化失败: " + driverClassName
                    + (isBlank(driverLoadError) ? "" : " (" + driverLoadError + ")"));
        }
        throw new ClassNotFoundException("JDBC driver not found: " + driverClassName);
    }

    private ArrayList<ClassLoader> collectCandidateClassLoaders() {
        ArrayList<ClassLoader> result = new ArrayList<ClassLoader>();
        HashSet<ClassLoader> seen = new HashSet<ClassLoader>();
        addLoader(result, seen, Thread.currentThread().getContextClassLoader());
        addLoader(result, seen, getClass().getClassLoader());
        addLoader(result, seen, ClassLoader.getSystemClassLoader());
        try {
            Iterator threads = Thread.getAllStackTraces().keySet().iterator();
            while (threads.hasNext()) {
                Thread thread = (Thread) threads.next();
                addLoader(result, seen, thread.getContextClassLoader());
            }
        } catch (Throwable ignored) {
        }
        try {
            Iterator loaders = collectWebappClassLoaders().iterator();
            while (loaders.hasNext()) addLoader(result, seen, (ClassLoader) loaders.next());
        } catch (Throwable ignored) {
        }
        return result;
    }

    private void addLoader(ArrayList<ClassLoader> result, HashSet<ClassLoader> seen, ClassLoader loader) {
        ClassLoader current = loader;
        while (current != null && !seen.contains(current)) {
            seen.add(current);
            result.add(current);
            current = current.getParent();
        }
    }

    private HashSet<ClassLoader> collectWebappClassLoaders() throws Throwable {
        HashSet<ClassLoader> result = new HashSet<ClassLoader>();
        Class mfClass = Class.forName("java.lang.management.ManagementFactory");
        Object mbs = mfClass.getMethod("getPlatformMBeanServer", new Class[0]).invoke(null, new Object[0]);
        Class onClass = Class.forName("javax.management.ObjectName");
        Object pattern = onClass.getConstructor(new Class[]{String.class})
                .newInstance(new Object[]{"Catalina:j2eeType=WebModule,*"});
        Method queryNames = mbs.getClass().getMethod("queryNames", new Class[]{onClass,
                Class.forName("javax.management.QueryExp")});
        Object queriedNames = queryNames.invoke(mbs, new Object[]{pattern, null});
        if (!(queriedNames instanceof Set)) return result;
        Set names = (Set) queriedNames;
        Method getAttribute = mbs.getClass().getMethod("getAttribute", new Class[]{onClass, String.class});
        Iterator iterator = names.iterator();
        while (iterator.hasNext()) {
            try {
                Object objectName = iterator.next();
                Object context = getAttribute.invoke(mbs, new Object[]{objectName, "managedResource"});
                if (context == null) continue;
                Object loader = context.getClass().getMethod("getLoader", new Class[0])
                        .invoke(context, new Object[0]);
                if (loader == null) continue;
                Object classLoader = loader.getClass().getMethod("getClassLoader", new Class[0])
                        .invoke(loader, new Object[0]);
                if (classLoader instanceof ClassLoader) result.add((ClassLoader) classLoader);
            } catch (Throwable ignored) {
            }
        }
        return result;
    }

    private Object normalizeJdbcValue(Object value, int maxCellBytes) throws Exception {
        if (value == null) return null;
        if (value instanceof byte[]) return capBytes((byte[]) value, maxCellBytes);
        if (value instanceof Blob) return readBytes(((Blob) value).getBinaryStream(), maxCellBytes);
        if (value instanceof Clob) return readText(((Clob) value).getCharacterStream(), maxCellBytes);
        if (value instanceof SQLXML) return readText(((SQLXML) value).getCharacterStream(), maxCellBytes);
        if (value instanceof BigDecimal || value instanceof BigInteger) return String.valueOf(value);
        if (value instanceof java.sql.Date || value instanceof java.sql.Time
                || value instanceof java.sql.Timestamp || value instanceof java.util.Date) {
            return String.valueOf(value);
        }
        if (value instanceof Boolean || value instanceof Byte
                || value instanceof Short || value instanceof Integer || value instanceof Long
                || value instanceof Float || value instanceof Double) {
            return value;
        }
        return capString(String.valueOf(value), maxCellBytes);
    }

    private byte[] readBytes(InputStream input, int maxBytes) throws Exception {
        if (input == null) return null;
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maxBytes, 8192));
        byte[] buffer = new byte[4096];
        int total = 0;
        try {
            int read;
            while ((read = input.read(buffer)) != -1) {
                int allowed = Math.min(read, maxBytes - total);
                if (allowed > 0) output.write(buffer, 0, allowed);
                total += allowed;
                if (allowed < read || total >= maxBytes) {
                    if (allowed < read || input.read() != -1) cellValueTruncated = true;
                    break;
                }
            }
            return output.toByteArray();
        } finally {
            try { input.close(); } catch (Throwable ignored) {}
        }
    }

    private String readText(Reader reader, int maxBytes) throws Exception {
        if (reader == null) return null;
        StringBuilder text = new StringBuilder(Math.min(maxBytes, 8192));
        char[] buffer = new char[2048];
        try {
            // UTF-8 needs at least one byte per UTF-16 code unit. One extra character
            // is enough to detect truncation without repeatedly encoding the entire prefix.
            while (text.length() <= maxBytes) {
                int read = reader.read(buffer, 0, Math.min(buffer.length, maxBytes + 1 - text.length()));
                if (read == -1) break;
                text.append(buffer, 0, read);
            }
            return capString(text.toString(), maxBytes);
        } finally {
            try { reader.close(); } catch (Throwable ignored) {}
        }
    }

    private byte[] capBytes(byte[] value, int maxBytes) {
        if (value.length <= maxBytes) return value;
        byte[] truncated = new byte[maxBytes];
        System.arraycopy(value, 0, truncated, 0, maxBytes);
        cellValueTruncated = true;
        return truncated;
    }

    private String capString(String value, int maxBytes) {
        if (utf8Length(value) <= maxBytes) return value;
        int low = 0;
        int high = value.length();
        while (low < high) {
            int middle = (low + high + 1) / 2;
            if (utf8Length(value.substring(0, middle)) <= maxBytes) low = middle;
            else high = middle - 1;
        }
        if (low > 0 && Character.isHighSurrogate(value.charAt(low - 1))
                && Character.isLowSurrogate(value.charAt(low))) low--;
        cellValueTruncated = true;
        return value.substring(0, low);
    }

    private long estimateValueBytes(Object value) {
        if (value == null) return 4L;
        if (value instanceof byte[]) return ((byte[]) value).length;
        return utf8Length(String.valueOf(value));
    }

    private String uniqueColumnKey(String base, HashSet<String> used) {
        String candidate = base;
        int suffix = 2;
        while (used.contains(candidate)) {
            candidate = base + "_" + suffix;
            suffix++;
        }
        used.add(candidate);
        return candidate;
    }

    private String safeColumnLabel(ResultSetMetaData metadata, int index) {
        try {
            String label = metadata.getColumnLabel(index);
            return isBlank(label) ? safeColumnName(metadata, index) : label;
        } catch (Throwable ignored) {
            return safeColumnName(metadata, index);
        }
    }

    private String safeColumnName(ResultSetMetaData metadata, int index) {
        try {
            String name = metadata.getColumnName(index);
            return isBlank(name) ? "column" + index : name;
        } catch (Throwable ignored) {
            return "column" + index;
        }
    }

    private String safeColumnTypeName(ResultSetMetaData metadata, int index) {
        try {
            String type = metadata.getColumnTypeName(index);
            return isBlank(type) ? "UNKNOWN" : type;
        } catch (Throwable ignored) {
            return "UNKNOWN";
        }
    }

    private Object safeColumnType(ResultSetMetaData metadata, int index) {
        try { return Integer.valueOf(metadata.getColumnType(index)); }
        catch (Throwable ignored) { return null; }
    }

    private Object safeColumnPrecision(ResultSetMetaData metadata, int index) {
        try { return Integer.valueOf(metadata.getPrecision(index)); }
        catch (Throwable ignored) { return null; }
    }

    private Object safeColumnScale(ResultSetMetaData metadata, int index) {
        try { return Integer.valueOf(metadata.getScale(index)); }
        catch (Throwable ignored) { return null; }
    }

    private Object safeColumnNullable(ResultSetMetaData metadata, int index) {
        try {
            int nullable = metadata.isNullable(index);
            if (nullable == ResultSetMetaData.columnNullableUnknown) return null;
            return Boolean.valueOf(nullable != ResultSetMetaData.columnNoNulls);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private int getBoundedIntParam(String key, int defaultValue, int minimum, int maximum) {
        Object value = params.get(key);
        if (value == null) return defaultValue;
        String text;
        if (value instanceof Number) return bounded(key, ((Number) value).intValue(), minimum, maximum);
        if (value instanceof byte[]) text = decode((byte[]) value);
        else text = String.valueOf(value);
        try {
            return bounded(key, Integer.parseInt(text.trim()), minimum, maximum);
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException(key + " 必须是整数");
        }
    }

    private int bounded(String key, int value, int minimum, int maximum) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(key + " 必须在 " + minimum + " 到 " + maximum + " 之间");
        }
        return value;
    }

    private void closeResource(Object resource) {
        if (resource == null) return;
        try {
            if (resource instanceof ResultSet) ((ResultSet) resource).close();
            else if (resource instanceof Statement) ((Statement) resource).close();
            else if (resource instanceof Connection) ((Connection) resource).close();
        } catch (Throwable ignored) {
        }
    }

    private String redact(String message, String password, String url) {
        String value = message == null ? "" : message;
        if (!isBlank(password)) value = value.replace(password, "***");
        if (!isBlank(url)) value = value.replace(url, redactUrl(url));
        return value;
    }

    private String redactUrl(String url) {
        if (url == null) return null;
        String redacted = url;
        int scheme = url.indexOf("://");
        int at = scheme < 0 ? -1 : url.indexOf('@', scheme + 3);
        if (at > scheme) {
            int colon = url.indexOf(':', scheme + 3);
            if (colon > scheme && colon < at) {
                redacted = url.substring(0, colon + 1) + "***" + url.substring(at);
            }
        }
        return redacted.replaceAll("(?i)(password|passwd|pwd|token|secret)=([^&;]+)", "$1=***");
    }

    private String safeMessage(Throwable error) {
        if (error == null) return "未知错误";
        return error.getMessage() == null ? error.getClass().getName() : error.getMessage();
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().length() == 0;
    }

    private int utf8Length(String value) {
        if (value == null) return 0;
        try { return value.getBytes("UTF-8").length; }
        catch (UnsupportedEncodingException ignored) { return value.getBytes().length; }
    }

    private static HashMap<String, Object> copyStringObjectMap(Object value) {
        HashMap<String, Object> copy = new HashMap<String, Object>();
        if (!(value instanceof Map)) return copy;
        Map source = (Map) value;
        Iterator iterator = source.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry entry = (Map.Entry) iterator.next();
            if (entry.getKey() instanceof String) copy.put((String) entry.getKey(), entry.getValue());
        }
        return copy;
    }

    private String getStringParam(String key) {
        Object value = params.get(key);
        if (value == null) return null;
        if (value instanceof byte[]) return decode((byte[]) value);
        return String.valueOf(value);
    }

    private String decode(byte[] value) {
        try { return new String(value, "UTF-8"); }
        catch (UnsupportedEncodingException ignored) { return new String(value); }
    }
}
