package org.leo.web.controller.puppetnode.sql;

import jakarta.servlet.http.HttpServletRequest;
import org.leo.core.entity.User;
import org.leo.core.puppet.impl.JavaPuppetNode;
import org.leo.core.util.ApiResponse;
import org.leo.service.sql.PuppetNodeSqlService;
import org.leo.service.sql.SqlExportService;
import org.leo.web.dto.puppetnode.sql.SqlRequests.ConnectionPayload;
import org.leo.web.dto.puppetnode.sql.SqlRequests.CreateDatabaseRequest;
import org.leo.web.dto.puppetnode.sql.SqlRequests.CreateTableRequest;
import org.leo.web.dto.puppetnode.sql.SqlRequests.DeleteRowRequest;
import org.leo.web.dto.puppetnode.sql.SqlRequests.ExecRequest;
import org.leo.web.dto.puppetnode.sql.SqlRequests.ExportDatabaseRequest;
import org.leo.web.dto.puppetnode.sql.SqlRequests.ExportResumeRequest;
import org.leo.web.dto.puppetnode.sql.SqlRequests.ExportSessionRequest;
import org.leo.web.dto.puppetnode.sql.SqlRequests.ExportTableRequest;
import org.leo.web.dto.puppetnode.sql.SqlRequests.ExportTaskRequest;
import org.leo.web.dto.puppetnode.sql.SqlRequests.InsertRowRequest;
import org.leo.web.dto.puppetnode.sql.SqlRequests.MetadataRequest;
import org.leo.web.dto.puppetnode.sql.SqlRequests.QueryTableRequest;
import org.leo.web.dto.puppetnode.sql.SqlRequests.UpdateRowRequest;
import org.leo.web.exception.ApiException;
import org.leo.web.util.ControllerUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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

    @Autowired
    public PuppetNodeSQLController(PuppetNodeSqlService puppetNodeSqlService, SqlExportService sqlExportService) {
        this.puppetNodeSqlService = puppetNodeSqlService;
        this.sqlExportService = sqlExportService;
    }

    /**
     * 执行SQL
     */
    @PostMapping("/exec")
    public Map<String, Object> execSql(@RequestBody ExecRequest request) {
        return sqlCall("执行SQL失败", () -> {
            String sqlScript = requireText(request.sql(), "sql");
            JavaPuppetNode javaPuppetNode = puppetNode(request);
            logger.debug("执行SQL，sessionId: {}", request.sessionId());
            Map<String, Object> results = puppetNodeSqlService.executeSql(
                    javaPuppetNode, request.connectionOptions(), sqlScript);
            return ApiResponse.success("ok", results);
        });
    }

    @PostMapping("/connections/test")
    public Map<String, Object> testConnection(@RequestBody MetadataRequest request) {
        return sqlCall("连接失败", () -> ApiResponse.success(
                "连接成功",
                puppetNodeSqlService.testConnection(puppetNode(request), request.connectionOptions())));
    }

    @GetMapping("/dialects")
    public Map<String, Object> getDialects() {
        return ApiResponse.success("ok", puppetNodeSqlService.getDialects());
    }

    @GetMapping("/dialects/{type}/data-types")
    public Map<String, Object> getDataTypes(@PathVariable("type") String type) {
        return sqlCall("获取数据类型失败",
                () -> ApiResponse.success("ok", puppetNodeSqlService.getDataTypes(type)));
    }

    @PostMapping("/metadata/databases")
    public Map<String, Object> getDatabases(@RequestBody MetadataRequest request) {
        return sqlCall("获取数据库列表失败", () -> ApiResponse.success(
                "ok",
                puppetNodeSqlService.getDatabases(puppetNode(request), request.connectionOptions())));
    }

    @PostMapping("/metadata/tables")
    public Map<String, Object> getTables(@RequestBody MetadataRequest request) {
        return sqlCall("获取表列表失败", () -> ApiResponse.success(
                "ok",
                puppetNodeSqlService.getTables(
                        puppetNode(request),
                        request.connectionOptions(),
                        request.database())));
    }

    @PostMapping("/metadata/table-columns")
    public Map<String, Object> getTableColumns(@RequestBody MetadataRequest request) {
        return sqlCall("获取表字段失败", () -> ApiResponse.success(
                "ok",
                puppetNodeSqlService.getTableColumns(
                        puppetNode(request),
                        request.connectionOptions(),
                        request.database(),
                        request.table())));
    }

    @PostMapping("/data/query-table")
    public Map<String, Object> queryTable(@RequestBody QueryTableRequest request) {
        return sqlCall("查询表数据失败", () -> ApiResponse.success(
                "ok",
                puppetNodeSqlService.queryTable(
                        puppetNode(request),
                        request.connectionOptions(),
                        request.database(),
                        request.table(),
                        intValue(request.page(), 1),
                        intValue(request.pageSize(), 20),
                        stringList(request.columns()),
                        mapList(request.orderBy()),
                        mapList(request.filters()))));
    }

    @PostMapping("/query/execute")
    public Map<String, Object> executeQuery(@RequestBody ExecRequest request) {
        return execSql(request);
    }

    @PostMapping("/tables/create")
    public Map<String, Object> createTable(@RequestBody CreateTableRequest request) {
        return sqlCall("创建表失败", () -> ApiResponse.success(
                "创建成功",
                puppetNodeSqlService.createTable(
                        puppetNode(request),
                        request.connectionOptions(),
                        request.database(),
                        request.table(),
                        mapList(request.columns()))));
    }

    @PostMapping("/databases/create")
    public Map<String, Object> createDatabase(@RequestBody CreateDatabaseRequest request) {
        return sqlCall("创建数据库失败", () -> ApiResponse.success(
                "创建成功",
                puppetNodeSqlService.createDatabase(
                        puppetNode(request),
                        request.connectionOptions(),
                        request.database())));
    }

    @PostMapping("/rows/insert")
    public Map<String, Object> insertRow(@RequestBody InsertRowRequest request) {
        return sqlCall("插入数据失败", () -> ApiResponse.success(
                "创建成功",
                puppetNodeSqlService.insertRow(
                        puppetNode(request),
                        request.connectionOptions(),
                        request.database(),
                        request.table(),
                        mapValue(request.row()))));
    }

    @PostMapping("/rows/update")
    public Map<String, Object> updateRow(@RequestBody UpdateRowRequest request) {
        return sqlCall("更新数据失败", () -> ApiResponse.success(
                "更新成功",
                puppetNodeSqlService.updateRows(
                        puppetNode(request),
                        request.connectionOptions(),
                        request.database(),
                        request.table(),
                        mapValue(request.where()),
                        mapValue(request.update()))));
    }

    @PostMapping("/rows/delete")
    public Map<String, Object> deleteRow(@RequestBody DeleteRowRequest request) {
        return sqlCall("删除数据失败", () -> ApiResponse.success(
                "删除成功",
                puppetNodeSqlService.deleteRows(
                        puppetNode(request),
                        request.connectionOptions(),
                        request.database(),
                        request.table(),
                        mapValue(request.where()))));
    }

    @PostMapping("/export/table")
    public Map<String, Object> exportTable(HttpServletRequest httpRequest,
                                           @RequestBody ExportTableRequest request) {
        return sqlCall("创建导出任务失败", () -> {
            User user = requireUser(httpRequest);
            return ApiResponse.success("导出任务已创建", sqlExportService.startTableExport(
                    puppetNode(request),
                    user.getUserId(),
                    request.sessionId(),
                    request.connectionOptions(),
                    request.database(),
                    request.table(),
                    request.format()));
        });
    }

    @PostMapping("/export/database")
    public Map<String, Object> exportDatabase(HttpServletRequest httpRequest,
                                              @RequestBody ExportDatabaseRequest request) {
        return sqlCall("创建导出任务失败", () -> {
            User user = requireUser(httpRequest);
            return ApiResponse.success("导出任务已创建", sqlExportService.startDatabaseExport(
                    puppetNode(request),
                    user.getUserId(),
                    request.sessionId(),
                    request.connectionOptions(),
                    request.database(),
                    stringList(request.tables()),
                    boolValue(request.includeStructure()),
                    boolValue(request.includeData()),
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
        return sqlCall("暂停导出任务失败", () -> ApiResponse.success(
                "已请求暂停导出任务",
                sqlExportService.pause(requireUser(httpRequest).getUserId(), request.taskId())));
    }

    @PostMapping("/export/stop")
    public Map<String, Object> stopExport(HttpServletRequest httpRequest,
                                          @RequestBody ExportTaskRequest request) {
        return sqlCall("停止导出任务失败", () -> ApiResponse.success(
                "已停止导出任务",
                sqlExportService.stop(requireUser(httpRequest).getUserId(), request.taskId())));
    }

    @PostMapping("/export/resume")
    public Map<String, Object> resumeExport(HttpServletRequest httpRequest,
                                            @RequestBody ExportResumeRequest request) {
        return sqlCall("恢复导出任务失败", () -> {
            User user = requireUser(httpRequest);
            return ApiResponse.success("导出任务已恢复", sqlExportService.resume(
                    puppetNode(request),
                    user.getUserId(),
                    request.sessionId(),
                    request.taskId(),
                    request.connectionOptions()));
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
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(e.getMessage());
        } catch (Exception e) {
            throw ApiException.serverError(failureMessage + ": " + e.getMessage());
        }
    }

    private JavaPuppetNode puppetNode(ConnectionPayload request) {
        return ControllerUtil.getPuppetNode(request.sessionId());
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

    private Boolean boolValue(Object value) {
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return null;
        }
        return "true".equalsIgnoreCase(text)
                || "1".equals(text)
                || "yes".equalsIgnoreCase(text)
                || "y".equalsIgnoreCase(text);
    }

    @FunctionalInterface
    private interface SqlAction {
        Map<String, Object> execute() throws Exception;
    }
}
