package org.leo.core.agent.capability;

import java.util.Map;

/** Runtime-neutral capability for executing platform-managed plugins. */
public interface PluginCapable {

    Map<String, Object> invokePlugin(String pluginId, String source,
                                     Map<String, Object> params) throws Exception;
}
