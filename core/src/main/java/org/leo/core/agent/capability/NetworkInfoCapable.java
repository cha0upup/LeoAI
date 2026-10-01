package org.leo.core.agent.capability;

import java.util.Map;

/**
 * Capability marker for nodes that can collect network topology information.
 */
public interface NetworkInfoCapable {

    Map<String, Object> collectNetworkInfo() throws Exception;
}
