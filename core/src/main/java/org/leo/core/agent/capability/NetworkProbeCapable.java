package org.leo.core.agent.capability;

import java.util.Map;

/**
 * Capability for running the unified, node-side network probe plan.
 *
 * <p>The plan is intentionally represented as a map so the service layer can
 * evolve stage-specific options without changing the capability contract.</p>
 */
public interface NetworkProbeCapable {

    /** Concurrent probe workers available in this node runtime (threads or processes). */
    default int networkProbeWorkerLimit() { return 256; }

    Map<String, Object> startNetworkProbe(Map<String, Object> plan) throws Exception;

    /**
     * Reads a bounded, incremental result page from a node-side task.
     *
     * <p>The cursor is a monotonically increasing observation cursor. Nodes
     * must not return the complete task history from this method. The ackCursor
     * confirms the previously accepted page before selecting the next one;
     * it must not exceed cursor.</p>
     */
    Map<String, Object> queryNetworkProbe(String taskId, long cursor, long ackCursor,
                                          int maxItems, int maxBytes,
                                          boolean includeEvidence) throws Exception;

    /** Confirm the final accepted page when there will be no further query. */
    Map<String, Object> ackNetworkProbe(String taskId, long cursor) throws Exception;

    Map<String, Object> pauseNetworkProbe(String taskId) throws Exception;

    Map<String, Object> resumeNetworkProbe(String taskId) throws Exception;

    Map<String, Object> stopNetworkProbe(String taskId) throws Exception;

    /** Release a terminal node-side task after its final snapshot has been collected. */
    Map<String, Object> releaseNetworkProbe(String taskId) throws Exception;
}
