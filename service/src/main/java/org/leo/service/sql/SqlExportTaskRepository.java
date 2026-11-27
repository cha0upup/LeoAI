package org.leo.service.sql;

import java.util.List;
import java.util.Map;

/** Durable task metadata only; scheduling and public response projection stay with the application. */
public interface SqlExportTaskRepository {
    /** Returns a detached metadata snapshot, independent of subsequent writes. */
    Map<String, Object> load(SqlExportTaskKey key) throws Exception;
    void save(SqlExportTaskKey key, Map<String, Object> task) throws Exception;
    List<String> listTaskIds(String userId) throws Exception;
}
