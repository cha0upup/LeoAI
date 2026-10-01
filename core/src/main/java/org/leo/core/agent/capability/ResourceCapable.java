package org.leo.core.agent.capability;

import java.util.Map;

/**
 * Capability marker for nodes that can read classpath resources and bytecode.
 */
public interface ResourceCapable {

    Map<String, Object> getClassBytecode(String className) throws Exception;

    Map<String, Object> getResource(String resourcePath) throws Exception;
}
