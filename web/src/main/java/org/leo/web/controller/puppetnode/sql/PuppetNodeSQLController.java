package org.leo.web.controller.puppetnode.sql;

import jakarta.servlet.http.HttpServletRequest;
import org.leo.core.entity.User;
import org.leo.core.puppet.AbstractPuppetNode;
import org.leo.core.puppet.capability.SqlCapable;
import org.leo.core.util.ApiResponse;
import org.leo.service.sql.PuppetNodeSqlService;
import org.leo.service.sql.SqlExecutionException;
import org.leo.service.sql.SqlExportService;
import org.leo.service.sql.SqlObjectRef;
import org.leo.service.PuppetDatabaseConnectionService;
import org.leo.web.dto.puppetnode.sql.SqlRequests.ConnectionPayload;
import org.leo.web.dto.puppetnode.sql.SqlRequests.CreateDatabaseRequest;
import org.leo.web.dto.puppetnode.sql.SqlRequests.CreateTableRequest;
import org.leo.web.dto.puppetnode.sql.SqlRequests.DeleteRowRequest;
import org.leo.web.dto.puppetnode.sql.SqlRequests.ConnectionRequest;
import org.leo.web.dto.puppetnode.sql.SqlRequests.ExecuteRequest;
import org.leo.web.dto.puppetnode.sql.SqlRequests.ExportDatabaseRequest;
import org.leo.web.dto.puppetnode.sql.SqlRequests.ExportResumeRequest;
import org.leo.web.dto.puppetnode.sql.SqlRequests.ExportSessionRequest;
import org.leo.web.dto.puppetnode.sql.SqlRequests.ExportTableRequest;
import org.leo.web.dto.puppetnode.sql.SqlRequests.ExportTaskRequest;
import org.leo.web.dto.puppetnode.sql.SqlRequests.InsertRowRequest;
import org.leo.web.dto.puppetnode.sql.SqlRequests.ObjectRequest;
import org.leo.web.dto.puppetnode.sql.SqlRequests.QueryTableRequest;
import org.leo.web.dto.puppetnode.sql.SqlRequests.UpdateRowRequest;
import org.leo.web.exception.ApiException;
import org.leo.web.security.DatabaseConnectionResolver;
import org.leo.web.util.AuditLogUtil;
import org.leo.web.util.ControllerUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/puppet-node/sql")
public class PuppetNodeSQLController {

    private static final Logger logger = LoggerFactory.getLogger(PuppetNodeSQLController.class);

    private final PuppetNodeSqlService puppetNodeSqlService;
    private final SqlExportService sqlExportService;
    private final PuppetDatabaseConnectionService databaseConnectionService;
    private final DatabaseConnectionResolver databaseConnectionResolver;

    public PuppetNodeSQLController(PuppetNodeSqlService puppetNodeSqlService,
                                   SqlExportService sqlExportService,
                                   PuppetDatabaseConnectionService databaseConnectionService,
                                   DatabaseConnectionResolver databaseConnectionResolver) {
        this.puppetNodeSqlService = puppetNodeSqlService;
        this.sqlExportService = sqlExportService;
        this.databaseConnectionService = databaseConnectionService;
        this.databaseConnectionResolver = databaseConnectionResolver;
    }

    @PostMapping("/query/execute")
    public Map<String, Object> executeQuery(@RequestBody ExecuteRequest request) {
        return auditedSqlCall("执行SQL失败", request, "SQL_EXEC", "执行SQL",
                sqlOperationPath(request, null, request == null ? null : request.sql()), node -> {
            String sqlScript = requireText(request.sql(), "sql");
            logger.debug("执行SQL，sessionId: {}", request.sessionId());
            Map<String, Object> results = puppetNodeSqlService.executeSql(
                    node, resolveConnection(request), sqlScript, request.queryTimeoutSeconds());
            return ApiResponse.success("ok", results);
        });
    }

    @PostMapping("/connections/test")
    public Map<String, Object> testConnection(@RequestBody ConnectionRequest request) {
        return sqlCall("连接失败", () -> {
            SqlCapable node = sqlNode(request);
            Map<String, Object> connection = resolveConnection(request);
            String connectionId = connectionReference(request);
            Map<String, Object> result = puppetNodeSqlService.testConnection(node, connection);
            boolean success = Boolean.TRUE.equals(result.get("success"));
            if (connectionId != null) {
                recordConnectionTestResult(connectionId, success,
                        success ? successfulTestMessage(result)
                                : String.valueOf(result.getOrDefault("message", "连接失败")));
            }
            return ApiResponse.success(success ? "连接成功" : "连接失败", result);
        });
    }

    @PostMapping("/runtime-capabilities")
    public Map<String, Object> runtimeCapabilities(@RequestBody ConnectionRequest request) {
        return sqlCall("数据库运行时能力探测失败", () -> ApiResponse.success(
                "ok",
                puppetNodeSqlService.getRuntimeCapabilities(
                        sqlNode(request), resolveConnection(request))));
    }

    @GetMapping("/dialects")
    public Map<String, Object> getDialects() {
        return ApiResponse.success("ok", puppetNodeSqlService.getDialects());
    }

    @PostMapping("/metadata/databases")
    public Map<String, Object> getDatabases(@RequestBody ConnectionRequest request) {
        return sqlCall("获取数据库列表失败", () -> ApiResponse.success(
                "ok",
                puppetNodeSqlService.getDatabases(sqlNode(request), resolveConnection(request))));
    }

    @PostMapping("/metadata/tables")
    public Map<String, Object> getTables(@RequestBody ObjectRequest request) {
        return sqlCall("获取表列表失败", () -> ApiResponse.success(
                "ok",
                puppetNodeSqlService.getTables(
                        sqlNode(request),
                        resolveConnection(request),
                        request.objectRef())));
    }

    @PostMapping("/metadata/table-columns")
    public Map<String, Object> getTableColumns(@RequestBody ObjectRequest request) {
        return sqlCall("获取表字段失败", () -> ApiResponse.success(
                "ok",
                puppetNodeSqlService.getTableColumns(
                        sqlNode(request),
                        resolveConnection(request),
                        request.objectRef())));
    }

    @PostMapping("/data/query-table")
    public Map<String, Object> queryTable(@RequestBody QueryTableRequest request) {
        return auditedSqlCall("查询表数据失败", request, "SQL_QUERY_TABLE", "查询表数据",
                sqlOperationPath(request, request == null ? null : request.objectRef(), null), node -> ApiResponse.success(
                "ok",
                puppetNodeSqlService.queryTable(
                        node,
                        resolveConnection(request),
                        request.objectRef(),
                        intValue(request.page(), 1),
                        intValue(request.pageSize(), 20),
                        stringList(request.columns()),
                        mapList(request.orderBy()),
                        mapList(request.filters()),
                        request.includeTotal(),
                        request.queryTimeoutSeconds())));
    }

    @PostMapping("/tables/create")
    public Map<String, Object> createTable(@RequestBody CreateTableRequest request) {
        return auditedSqlCall("创建表失败", request, "SQL_TABLE_CREATE", "创建数据表",
                sqlOperationPath(request, request == null ? null : request.objectRef(), null), node -> ApiResponse.success(
                "创建成功",
                puppetNodeSqlService.createTable(
                        node,
                        resolveConnection(request),
                        request.objectRef(),
                        mapList(request.columns()))));
    }

    @PostMapping("/databases/create")
    public Map<String, Object> createDatabase(@RequestBody CreateDatabaseRequest request) {
        return auditedSqlCall("创建数据库失败", request, "SQL_DATABASE_CREATE", "创建数据库",
                sqlOperationPath(request, null, request == null ? null : request.database()), node -> ApiResponse.success(
                "创建成功",
                puppetNodeSqlService.createDatabase(
                        node,
                        resolveConnection(request),
                        request.database())));
    }

    @PostMapping("/rows/insert")
    public Map<String, Object> insertRow(@RequestBody InsertRowRequest request) {
        return auditedSqlCall("插入数据失败", request, "SQL_ROW_INSERT", "插入数据",
                sqlOperationPath(request, request == null ? null : request.objectRef(), null), node -> ApiResponse.success(
                "创建成功",
                puppetNodeSqlService.insertRow(
                        node,
                        resolveConnection(request),
                        request.objectRef(),
                        mapValue(request.row()))));
    }

    @PostMapping("/rows/update")
    public Map<String, Object> updateRow(@RequestBody UpdateRowRequest request) {
        return auditedSqlCall("更新数据失败", request, "SQL_ROW_UPDATE", "更新数据",
                sqlOperationPath(request, request == null ? null : request.objectRef(), null), node -> ApiResponse.success(
                "更新成功",
                puppetNodeSqlService.updateRows(
                        node,
                        resolveConnection(request),
                        request.objectRef(),
                        mapValue(request.where()),
                        mapValue(request.update()))));
    }

    @PostMapping("/rows/delete")
    public Map<String, Object> deleteRow(@RequestBody DeleteRowRequest request) {
        return auditedSqlCall("删除数据失败", request, "SQL_ROW_DELETE", "删除数据",
                sqlOperationPath(request, request == null ? null : request.objectRef(), null), node -> ApiResponse.success(
                "删除成功",
                puppetNodeSqlService.deleteRows(
                        node,
                        resolveConnection(request),
                        request.objectRef(),
                        mapValue(request.where()))));
    }

    @PostMapping("/export/table")
    public Map<String, Object> exportTable(HttpServletRequest httpRequest,
                                           @RequestBody ExportTableRequest request) {
        return auditedSqlCall("创建导出任务失败", request, "SQL_EXPORT_TABLE", "导出数据表",
                sqlOperationPath(request, request == null ? null : request.objectRef(),
                        request == null ? null : request.format()), node -> {
            User user = requireUser(httpRequest);
            return ApiResponse.success("导出任务已创建", sqlExportService.startTableExport(
                    node,
                    user.getUserId(),
                    request.sessionId(),
                    resolveConnection(request),
                    request.objectRef(),
                    request.format()));
        });
    }

    @PostMapping("/export/database")
    public Map<String, Object> exportDatabase(HttpServletRequest httpRequest,
                                              @RequestBody ExportDatabaseRequest request) {
        return auditedSqlCall("创建导出任务失败", request, "SQL_EXPORT_DATABASE", "导出数据库",
                sqlOperationPath(request, request == null ? null : request.objectRef(),
                        request == null ? null : request.format()), node -> {
            User user = requireUser(httpRequest);
            return ApiResponse.success("导出任务已创建", sqlExportService.startDatabaseExport(
                    node,
                    user.getUserId(),
                    request.sessionId(),
                    resolveConnection(request),
                    request.objectRef(),
                    request.tableRefs(),
                    request.includeStructure(),
                    request.includeData(),
                    request.format()));
        });
    }

    @PostMapping("/export/progress")
    public Map<String, Object> exportProgress(HttpServletRequest httpRequest,
                                              @RequestBody ExportTaskRequest request) {
        return sqlCall("获取导出任务进度失败", () -> ApiResponse.success(
                "ok",
                sqlExportService.progress(requireUser(httpRequest).getUserId(), request.taskId())));
    }

    @PostMapping("/export/pause")
    public Map<String, Object> pauseExport(HttpServletRequest httpRequest,
                                           @RequestBody ExportTaskRequest request) {
        return sqlCall("暂停导出任务失败", () -> {
            User user = requireUser(httpRequest);
            Map<String, Object> result = ApiResponse.success(
                    "已请求暂停导出任务",
                    sqlExportService.pause(user.getUserId(), request.taskId()));
            AuditLogUtil.logSystemOperation(user, "SQL_EXPORT_PAUSE", "暂停SQL导出任务",
                    request.taskId(), exportTaskAuditParams(request), ApiResponse.CODE_SUCCESS,
                    "暂停SQL导出任务", "SUCCESS", null, AuditLogUtil.getClientIp(httpRequest), false);
            return result;
        });
    }

    @PostMapping("/export/stop")
    public Map<String, Object> stopExport(HttpServletRequest httpRequest,
                                          @RequestBody ExportTaskRequest request) {
        return sqlCall("停止导出任务失败", () -> {
            User user = requireUser(httpRequest);
            Map<String, Object> result = ApiResponse.success(
                    "已停止导出任务",
                    sqlExportService.stop(user.getUserId(), request.taskId()));
            AuditLogUtil.logSystemOperation(user, "SQL_EXPORT_STOP", "停止SQL导出任务",
                    request.taskId(), exportTaskAuditParams(request), ApiResponse.CODE_SUCCESS,
                    "停止SQL导出任务", "SUCCESS", null, AuditLogUtil.getClientIp(httpRequest), false);
            return result;
        });
    }

    @PostMapping("/export/resume")
    public Map<String, Object> resumeExport(HttpServletRequest httpRequest,
                                            @RequestBody ExportResumeRequest request) {
        return auditedSqlCall("恢复导出任务失败", request, "SQL_EXPORT_RESUME", "恢复SQL导出任务",
                request == null ? null : request.taskId(), node -> {
            User user = requireUser(httpRequest);
            return ApiResponse.success("导出任务已恢复", sqlExportService.resume(
                    node,
                    user.getUserId(),
                    request.sessionId(),
                    request.taskId(),
                    resolveConnection(request)));
        });
    }

    @PostMapping("/export/tasks")
    public Map<String, Object> exportTasks(HttpServletRequest httpRequest,
                                           @RequestBody ExportSessionRequest request) {
        return sqlCall("获取导出任务列表失败", () -> ApiResponse.success(
                "ok",
                sqlExportService.listBySessionId(requireUser(httpRequest).getUserId(), request.sessionId())));
    }

    @GetMapping("/export/tasks/{taskId}")
    public Map<String, Object> exportTaskStatus(HttpServletRequest request,
                                                @PathVariable("taskId") String taskId) {
        return sqlCall("获取导出任务状态失败", () -> ApiResponse.success(
                "ok",
                sqlExportService.getTaskStatus(requireUser(request).getUserId(), taskId)));
    }

    private Map<String, Object> sqlCall(String failureMessage, SqlAction action) {
        try {
            return action.execute();
        } catch (ApiException e) {
            throw e;
        } catch (SqlExecutionException e) {
            throw ApiException.databaseError(e.getStatusCode(), e.getMessage(), e.details());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(e.getMessage());
        } catch (Exception e) {
            throw ApiException.serverError(failureMessage + ": " + e.getMessage());
        }
    }

    private Map<String, Object> auditedSqlCall(String failureMessage,
                                               ConnectionPayload request,
                                               String operationType,
                                               String operationName,
                                               String operationPath,
                                               SqlNodeAction action) {
        SqlCapable node = null;
        AbstractPuppetNode auditNode = null;
        Map<String, Object> auditParams = auditParams(request);
        try {
            node = sqlNode(request);
            auditNode = ControllerUtil.getAbstractPuppetNode(request.sessionId());
            Map<String, Object> result = action.execute(node);
            AuditLogUtil.logSuccess(auditNode, operationType, operationName, operationPath, auditParams,
                    ApiResponse.CODE_SUCCESS, "操作成功", AuditLogUtil.getClientIp());
            return result;
        } catch (ApiException e) {
            if (auditNode != null) {
                AuditLogUtil.logFailure(auditNode, operationType, operationName, operationPath, auditParams,
                        e.getMessage(), AuditLogUtil.getClientIp());
            }
            throw e;
        } catch (SqlExecutionException e) {
            if (auditNode != null) {
                AuditLogUtil.logFailure(auditNode, operationType, operationName, operationPath, auditParams,
                        e.getMessage(), AuditLogUtil.getClientIp());
            }
            throw ApiException.databaseError(e.getStatusCode(), e.getMessage(), e.details());
        } catch (IllegalArgumentException e) {
            if (auditNode != null) {
                AuditLogUtil.logFailure(auditNode, operationType, operationName, operationPath, auditParams,
                        e.getMessage(), AuditLogUtil.getClientIp());
            }
            throw ApiException.badRequest(e.getMessage());
        } catch (Exception e) {
            if (auditNode != null) {
                AuditLogUtil.logFailure(auditNode, operationType, operationName, operationPath, auditParams,
                        e.getMessage(), AuditLogUtil.getClientIp());
            }
            throw ApiException.serverError(failureMessage + ": " + e.getMessage());
        }
    }

    private SqlCapable sqlNode(ConnectionPayload request) {
        return ControllerUtil.requireCapability(request.sessionId(), SqlCapable.class);
    }

    private Map<String, Object> resolveConnection(ConnectionPayload request) {
        Map<String, Object> options = request.connectionOptions();
        AbstractPuppetNode node = ControllerUtil.getAbstractPuppetNode(request.sessionId());
        if (node.getPuppet() == null || node.getPuppet().getPuppetId() == null
                || node.getPuppet().getPuppetId().isBlank()) {
            throw ApiException.badRequest("当前会话缺少 Puppet 标识");
        }
        return databaseConnectionResolver.resolve(
                options, node.getPuppet().getPuppetId(), ControllerUtil.getCurrentUser());
    }

    private String connectionReference(ConnectionPayload request) {
        return databaseConnectionResolver.reference(request.connectionOptions());
    }

    private String successfulTestMessage(Map<String, Object> result) {
        StringBuilder message = new StringBuilder("连接成功");
        Object latency = result.get("latencyMs");
        if (latency != null) message.append("，延迟 ").append(latency).append(" ms");
        Object version = result.get("databaseVersion");
        if (version != null && !String.valueOf(version).isBlank()) {
            message.append("，版本 ").append(version);
        }
        return message.toString();
    }

    private void recordConnectionTestResult(String connectionId, boolean success, String message) {
        try {
            databaseConnectionService.recordTestResult(connectionId, success, message);
        } catch (RuntimeException e) {
            logger.warn("记录数据库连接测试状态失败，connectionId: {}", connectionId, e);
        }
    }

    private String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest("缺少 " + name);
        }
        return value;
    }

    private User requireUser(HttpServletRequest request) {
        User user = ControllerUtil.getCurrentUser(request);
        if (user == null) {
            throw ApiException.unauthorized("用户未登录");
        }
        return user;
    }

    private Integer intValue(Integer value, Integer defaultValue) {
        return value == null ? defaultValue : value;
    }

    private List<Map<String, Object>> mapList(List<Map<String, Object>> value) {
        return value == null ? Collections.emptyList() : value;
    }

    private Map<String, Object> mapValue(Map<String, Object> value) {
        return value == null ? new HashMap<>() : value;
    }

    private List<String> stringList(List<String> value) {
        return value == null ? Collections.emptyList() : value;
    }

    private Map<String, Object> auditParams(ConnectionPayload request) {
        Map<String, Object> params = new HashMap<>();
        if (request == null) {
            return params;
        }
        params.put("sessionId", request.sessionId());
        putConnectionAuditParams(params, request);

        if (request instanceof ExecuteRequest executeRequest) {
            params.put("sql", executeRequest.sql());
        } else if (request instanceof ObjectRequest objectRequest) {
            putObjectRef(params, objectRequest.objectRef());
        } else if (request instanceof QueryTableRequest queryRequest) {
            putObjectRef(params, queryRequest.objectRef());
            params.put("page", queryRequest.page());
            params.put("pageSize", queryRequest.pageSize());
            params.put("filters", queryRequest.filters());
        } else if (request instanceof CreateTableRequest createTableRequest) {
            putObjectRef(params, createTableRequest.objectRef());
            params.put("columns", createTableRequest.columns());
        } else if (request instanceof CreateDatabaseRequest createDatabaseRequest) {
            params.put("database", createDatabaseRequest.database());
        } else if (request instanceof InsertRowRequest insertRowRequest) {
            putObjectRef(params, insertRowRequest.objectRef());
            params.put("row", insertRowRequest.row());
        } else if (request instanceof UpdateRowRequest updateRowRequest) {
            putObjectRef(params, updateRowRequest.objectRef());
            params.put("where", updateRowRequest.where());
            params.put("update", updateRowRequest.update());
        } else if (request instanceof DeleteRowRequest deleteRowRequest) {
            putObjectRef(params, deleteRowRequest.objectRef());
            params.put("where", deleteRowRequest.where());
        } else if (request instanceof ExportTableRequest exportTableRequest) {
            putObjectRef(params, exportTableRequest.objectRef());
            params.put("format", exportTableRequest.format());
        } else if (request instanceof ExportDatabaseRequest exportDatabaseRequest) {
            putObjectRef(params, exportDatabaseRequest.objectRef());
            params.put("tableRefs", exportDatabaseRequest.tableRefs());
            params.put("includeStructure", exportDatabaseRequest.includeStructure());
            params.put("includeData", exportDatabaseRequest.includeData());
            params.put("format", exportDatabaseRequest.format());
        } else if (request instanceof ExportResumeRequest exportResumeRequest) {
            params.put("taskId", exportResumeRequest.taskId());
        }
        return params;
    }

    private void putObjectRef(Map<String, Object> params, SqlObjectRef objectRef) {
        if (objectRef != null) {
            params.put("objectRef", objectRef.toMap());
        }
    }

    private void putConnectionAuditParams(Map<String, Object> params, ConnectionPayload request) {
        Map<String, Object> options = request.connectionOptions();
        params.put("dbDialect", firstText(options, "dialect", null));
        params.put("dbHost", firstText(options, "host", null));
        params.put("dbUser", firstText(options, "username", null));
        params.put("dbTarget", connectionTarget(options));
    }

    private Map<String, Object> exportTaskAuditParams(ExportTaskRequest request) {
        Map<String, Object> params = new HashMap<>();
        if (request != null) {
            params.put("taskId", request.taskId());
        }
        return params;
    }

    private String sqlOperationPath(ConnectionPayload request, SqlObjectRef objectRef, String detail) {
        if (request == null) {
            return truncate(detail, 220);
        }
        Map<String, Object> options = request.connectionOptions();
        StringBuilder path = new StringBuilder();
        appendPathPart(path, firstText(options, "dialect", null));
        appendPathPart(path, connectionTarget(options));
        if (objectRef != null) {
            appendPathPart(path, objectRef.catalog());
            appendPathPart(path, objectRef.schema());
            appendPathPart(path, objectRef.name());
        }
        if (detail != null && !detail.isBlank()) {
            appendPathPart(path, truncate(detail, 180));
        }
        return path.isEmpty() ? null : path.toString();
    }

    private String connectionTarget(Map<String, Object> options) {
        for (String key : List.of("file", "database", "service", "sid", "host")) {
            String value = firstText(options, key, null);
            if (value != null) return value;
        }
        return null;
    }

    private String firstText(Map<String, Object> options, String key, String fallback) {
        Object value = options == null ? null : options.get(key);
        if (value != null && !value.toString().isBlank()) {
            return value.toString();
        }
        return fallback == null || fallback.isBlank() ? null : fallback;
    }

    private void appendPathPart(StringBuilder builder, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        if (!builder.isEmpty()) {
            builder.append(" | ");
        }
        builder.append(value.trim());
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim().replaceAll("\\s+", " ");
        if (trimmed.length() <= maxLength) {
            return trimmed;
        }
        return trimmed.substring(0, maxLength) + "...";
    }

    @FunctionalInterface
    private interface SqlAction {
        Map<String, Object> execute() throws Exception;
    }

    @FunctionalInterface
    private interface SqlNodeAction {
        Map<String, Object> execute(SqlCapable node) throws Exception;
    }
}
