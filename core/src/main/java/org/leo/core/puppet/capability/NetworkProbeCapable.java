package org.leo.core.puppet.capability;

import java.util.Map;

/**
 * Capability for running the unified, node-side network probe plan.
 *
 * <p>The plan is intentionally represented as a map so the service layer can
 * evolve stage-specific options without changing the capability contract.</p>
 */
public interface NetworkProbeCapable {

    Map<String, Object> networkProbeCapabilities() throws Exception;

    Map<String, Object> startNetworkProbe(Map<String, Object> plan) throws Exception;

    Map<String, Object> queryNetworkProbe(String taskId) throws Exception;

    Map<String, Object> pauseNetworkProbe(String taskId) throws Exception;

    Map<String, Object> resumeNetworkProbe(String taskId) throws Exception;

    Map<String, Object> stopNetworkProbe(String taskId) throws Exception;
}
