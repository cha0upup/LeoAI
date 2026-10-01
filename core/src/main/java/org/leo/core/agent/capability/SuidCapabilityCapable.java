package org.leo.core.agent.capability;

import java.util.Map;

/**
 * Capability marker for nodes that can enumerate SUID, SGID, and file capabilities.
 */
public interface SuidCapabilityCapable {

    Map<String, Object> listSuidFiles() throws Exception;

    Map<String, Object> listSgidFiles() throws Exception;

    Map<String, Object> listFileCapabilities() throws Exception;

    Map<String, Object> listAllSuidCaps() throws Exception;
}
