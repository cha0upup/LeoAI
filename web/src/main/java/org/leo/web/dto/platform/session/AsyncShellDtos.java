package org.leo.web.dto.platform.session;

import java.util.List;

public final class AsyncShellDtos {

    private AsyncShellDtos() {
    }

    public record StartRequest(String sessionId, String command) {
    }

    public record SessionRequest(String sessionId) {
    }

    public record TaskRequest(String sessionId, String taskId) {
    }

    public record StartResponse(String sessionId, String taskId, String status) {
    }

    public record TaskSummary(String taskId,
                              String command,
                              String status,
                              long startTime,
                              long endTime,
                              int exitCode) {
    }

    public record TaskDetail(String taskId,
                             String command,
                             String status,
                             long startTime,
                             long endTime,
                             int exitCode,
                             String output) {
    }

    public record TaskListResponse(String sessionId, List<TaskSummary> tasks, int count) {
    }

    public record TaskResponse(String sessionId, TaskDetail task) {
    }

    public record CancelResponse(String sessionId, String taskId, boolean cancelled, String msg) {
    }

    public record ClearResponse(String sessionId, int cleared, String msg) {
    }
}
