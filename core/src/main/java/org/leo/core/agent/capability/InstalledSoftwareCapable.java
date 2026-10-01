package org.leo.core.agent.capability;

import java.util.Map;

/**
 * Capability marker for nodes that can enumerate installed software.
 */
public interface InstalledSoftwareCapable {

    Map<String, Object> listAllSoftware() throws Exception;

    Map<String, Object> listSystemSoftware() throws Exception;

    Map<String, Object> listUserSoftware() throws Exception;

    Map<String, Object> searchSoftware(String keyword) throws Exception;
}
