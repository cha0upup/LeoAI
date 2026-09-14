package org.leo.core.puppet.capability;

import java.util.Map;

/**
 * Capability for running the unified, node-side network probe plan.
 *
 * <p>The plan is intentionally represented as a map so the service layer can
 * evolve stage-specific options without changing the capability contract.</p>
 */
public interface NetworkProbeCapable {

    Map<String, Object> startNetworkProbe(Map<String, Object> plan) throws Exception;

    /**
     * Reads a bounded, incremental result page from a node-side task.
     *
     * <p>The cursor is a monotonically increasing observation cursor. Nodes
     * must not return the complete task history from this method.</p>
     */
    Map<String, Object> queryNetworkProbe(String taskId, long cursor,
                                          int maxItems, int maxBytes,
                                          boolean includeEvidence) throws Exception;

    /** Acknowledge that the service has durably accepted observations through cursor. */
    default Map<String, Object> ackNetworkProbe(String taskId, long cursor) throws Exception {
        return Map.of("code", Integer.valueOf(200), "cursor", Long.valueOf(cursor));
    }

    Map<String, Object> pauseNetworkProbe(String taskId) throws Exception;

    Map<String, Object> resumeNetworkProbe(String taskId) throws Exception;

    Map<String, Object> stopNetworkProbe(String taskId) throws Exception;

    /** Release a terminal node-side task after its final snapshot has been collected. */
    Map<String, Object> releaseNetworkProbe(String taskId) throws Exception;
}
