package org.leo.core.agent.capability;

import java.util.Map;

/**
 * Capability marker for nodes that can inspect active network connections.
 */
public interface NetworkConnectionCapable {

    Map<String, Object> listNetworkConnections(String state, String protocol, String port,
                                                String pid, String process, String remoteIp,
                                                boolean listeningOnly, int maxEntries) throws Exception;

    Map<String, Object> listNetworkConnections() throws Exception;

    Map<String, Object> networkConnectionSummary() throws Exception;
}
