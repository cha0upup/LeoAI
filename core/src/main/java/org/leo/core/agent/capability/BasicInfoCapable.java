package org.leo.core.agent.capability;

import java.util.Map;

/**
 * Capability marker for nodes that can report host baseline information.
 */
public interface BasicInfoCapable {

    Map<String, Object> getBasicInfo() throws Exception;
}
