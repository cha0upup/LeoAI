package org.leo.web.dto.agentnode.sql;

import org.leo.service.sql.SqlObjectRef;

import java.util.List;
import java.util.Map;

public final class SqlRequests {

    private SqlRequests() {
    }

    public interface ConnectionPayload {
        String sessionId();

        Map<String, Object> connection();

        default Map<String, Object> connectionOptions() {
            if (connection() == null) {
                throw new IllegalArgumentException("connection 不能为空");
            }
            return connection();
        }
    }

    public record ExecuteRequest(String sessionId,
                                 Map<String, Object> connection,
                                 String sql,
                                 Integer queryTimeoutSeconds) implements ConnectionPayload {
    }

    public record ConnectionRequest(String sessionId,
                                    Map<String, Object> connection) implements ConnectionPayload {
    }

    public record ObjectRequest(String sessionId,
                                Map<String, Object> connection,
                                SqlObjectRef objectRef) implements ConnectionPayload {
    }

    public record QueryTableRequest(String sessionId,
                                    Map<String, Object> connection,
                                    SqlObjectRef objectRef,
                                    Integer page,
                                    Integer pageSize,
                                    List<String> columns,
                                    List<Map<String, Object>> orderBy,
                                    List<Map<String, Object>> filters,
                                    Boolean includeTotal,
                                    Integer queryTimeoutSeconds) implements ConnectionPayload {
    }

    public record CreateTableRequest(String sessionId,
                                     Map<String, Object> connection,
                                     SqlObjectRef objectRef,
                                     List<Map<String, Object>> columns) implements ConnectionPayload {
    }

    public record CreateDatabaseRequest(String sessionId,
                                        Map<String, Object> connection,
                                        String database) implements ConnectionPayload {
    }

    public record InsertRowRequest(String sessionId,
                                   Map<String, Object> connection,
                                   SqlObjectRef objectRef,
                                   Map<String, Object> row) implements ConnectionPayload {
    }

    public record UpdateRowRequest(String sessionId,
                                   Map<String, Object> connection,
                                   SqlObjectRef objectRef,
                                   Map<String, Object> where,
                                   Map<String, Object> update) implements ConnectionPayload {
    }

    public record DeleteRowRequest(String sessionId,
                                   Map<String, Object> connection,
                                   SqlObjectRef objectRef,
                                   Map<String, Object> where) implements ConnectionPayload {
    }

    public record ExportTableRequest(String sessionId,
                                     Map<String, Object> connection,
                                     SqlObjectRef objectRef,
                                     String format) implements ConnectionPayload {
    }

    public record ExportDatabaseRequest(String sessionId,
                                        Map<String, Object> connection,
                                        SqlObjectRef objectRef,
                                        List<SqlObjectRef> tableRefs,
                                        Boolean includeStructure,
                                        Boolean includeData,
                                        String format) implements ConnectionPayload {
    }

    public record ExportResumeRequest(String sessionId,
                                      Map<String, Object> connection,
                                      String taskId) implements ConnectionPayload {
    }

    public record ExportTaskRequest(String taskId) {
    }

    public record ExportSessionRequest(String sessionId) {
    }
}
