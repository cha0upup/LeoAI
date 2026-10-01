package org.leo.core.agent.service;

import org.leo.core.net.Communication;
import org.leo.core.net.layer.RequestLayer;
import org.leo.core.net.layer.ResponseLayer;
import org.leo.core.agent.database.DatabaseConnectionSpec;
import org.leo.core.agent.database.JavaDatabaseConnectionAdapter;
import org.leo.core.agent.database.SqlCommand;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SqlService extends ComponentService {

    private final JavaDatabaseConnectionAdapter connectionAdapter = new JavaDatabaseConnectionAdapter();

    public SqlService(Communication communication, List<RequestLayer> requestLayers, List<ResponseLayer> responseLayers) {
        super(communication, requestLayers, responseLayers);
    }

    public Map<String, Object> executeSql(DatabaseConnectionSpec connection, String sqlScript) throws Exception {
        return executeSql(connection, SqlCommand.raw(sqlScript));
    }

    public Map<String, Object> executeSql(DatabaseConnectionSpec connection, SqlCommand command) throws Exception {
        HashMap<String, Object> payload = new HashMap<String, Object>(connectionAdapter.adapt(connection));
        payload.put("sql", command.sql());
        if (command.hasParameters()) payload.put("parameters", command.parameters());
        return invokeComponent("DatabaseComponent", payload);
    }

    public Map<String, Object> inspectRuntime(Map<String, Object> connection) throws Exception {
        HashMap<String, Object> payload = new HashMap<String, Object>();
        payload.put("operation", "capabilities");
        payload.put("requestedDriver", requestedDriver(connection));
        return invokeComponent("DatabaseComponent", payload);
    }

    @SuppressWarnings("unchecked")
    private String requestedDriver(Map<String, Object> connection) {
        if (connection == null) return "";
        Object runtimeOptions = connection.get("runtimeOptions");
        if (runtimeOptions instanceof Map<?, ?> runtimes) {
            Object javaOptions = runtimes.get("java");
            if (javaOptions instanceof Map<?, ?> java) {
                Object configured = java.get("driverClass");
                if (configured != null && !String.valueOf(configured).isBlank()) {
                    return String.valueOf(configured).trim();
                }
            }
        }
        String dialect = String.valueOf(connection.getOrDefault("dialect", "")).trim().toLowerCase();
        try {
            return connectionAdapter.defaultDriver(dialect);
        } catch (IllegalArgumentException ignored) {
            return "";
        }
    }
}
