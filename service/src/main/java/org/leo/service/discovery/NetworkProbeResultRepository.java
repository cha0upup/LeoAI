package org.leo.service.discovery;

import java.util.List;
import java.util.Map;

/** Durable-result port used by workflows; storage and session-scope resolution belong to adapters. */
public interface NetworkProbeResultRepository {
    boolean createTask(String taskId, String sessionId, String name, Map<String, Object> config);

    boolean append(String taskId, List<Map<String, Object>> observations, List<Map<String, Object>> errors);

    boolean append(String taskId, List<Map<String, Object>> observations, List<Map<String, Object>> errors,
                   List<Map<String, Object>> matches, List<Map<String, Object>> endpointSnapshots);

    void updateTask(String taskId, String status, String outcome, String currentStage,
                    int progress, int targetCount);

    void updateTask(String taskId, String status, String outcome, String currentStage,
                    int progress, int targetCount, String errorMessage, List<Map<String, Object>> stages);

    boolean ownsTask(String sessionId, String taskId);

    FingerprintSource fingerprintSource(String sessionId, String taskId, List<String> endpointIds);

    record FingerprintSource(List<Map<String, Object>> endpoints, List<Map<String, Object>> targets) { }
}
