package org.leo.core.agent.capability;

import java.util.Map;

/**
 * Capability marker for nodes that can inspect and manage operating-system services.
 */
public interface ServiceCapable {

    Map<String, Object> listServices() throws Exception;

    Map<String, Object> queryService(String serviceName) throws Exception;

    Map<String, Object> startService(String serviceName) throws Exception;

    Map<String, Object> stopService(String serviceName) throws Exception;

    Map<String, Object> restartService(String serviceName) throws Exception;

    Map<String, Object> enableService(String serviceName) throws Exception;

    Map<String, Object> disableService(String serviceName) throws Exception;

    Map<String, Object> createService(String serviceName, String binPath, String displayName, String startType) throws Exception;

    Map<String, Object> deleteService(String serviceName) throws Exception;
}
