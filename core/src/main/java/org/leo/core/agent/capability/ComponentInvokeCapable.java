package org.leo.core.agent.capability;

import java.util.Map;

/**
 * Capability marker for nodes that can invoke dynamically loaded components.
 */
public interface ComponentInvokeCapable {

    Map<String, Object> invokeComponent(String componentId, Map<String, Object> params) throws Exception;
}
