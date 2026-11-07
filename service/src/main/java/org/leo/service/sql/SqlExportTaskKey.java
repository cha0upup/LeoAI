package org.leo.service.sql;

import java.util.Map;

/** The same validated identity is used for cache access and durable task lookup. */
public record SqlExportTaskKey(String userId, String taskId) {
    public SqlExportTaskKey {
        userId = requireUserId(userId);
        taskId = requireSegment(taskId, "taskId");
    }

    static String requireUserId(String userId) {
        return requireSegment(userId, "userId");
    }

    Map<String, Object> requireOwner(Map<String, Object> task) {
        if (task == null || !userId.equals(task.get("userId")) || !taskId.equals(task.get("taskId")))
            throw new IllegalArgumentException("任务不存在: " + taskId);
        return task;
    }

    private static String requireSegment(String value, String name) {
        if (value == null || value.isBlank() || value.length() > 128 || value.equals(".") || value.equals("..")
                || value.chars().anyMatch(c -> c == '/' || c == '\\' || c == ':' || Character.isISOControl(c)))
            throw new IllegalArgumentException(name + " 标识无效");
        return value;
    }
}
