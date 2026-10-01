package org.leo.core.agent.capability;

import java.util.Map;

/**
 * Capability marker for nodes that can inspect and modify the Windows registry.
 */
public interface RegistryCapable {

    Map<String, Object> queryRegistry(String keyPath, boolean recursive) throws Exception;

    Map<String, Object> searchRegistry(String keyPath, String pattern, String searchTarget, int maxResults) throws Exception;

    Map<String, Object> addRegistry(String keyPath, String valueName, String valueType, String valueData, boolean force) throws Exception;

    Map<String, Object> deleteRegistry(String keyPath, String valueName, boolean force) throws Exception;

    Map<String, Object> exportRegistry(String keyPath) throws Exception;
}
