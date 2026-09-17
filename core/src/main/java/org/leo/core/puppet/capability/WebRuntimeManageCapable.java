package org.leo.core.puppet.capability;

import java.util.Map;

/**
 * Inspects and manages versioned Java web runtimes.
 */
public interface WebRuntimeManageCapable {

    Map<String, Object> inspectWebRuntime(String runtimeFamily,
                                          String runtimeVersion,
                                          String webFramework) throws Exception;

    Map<String, Object> removeWebRuntimeComponent(String runtimeFamily,
                                                   String runtimeVersion,
                                                   String webFramework,
                                                   String componentType,
                                                   String contextId,
                                                   String identifier) throws Exception;
}
