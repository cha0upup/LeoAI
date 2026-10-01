package org.leo.core.agent.capability;

import java.util.Map;

/**
 * Capability marker for nodes that can harvest runtime credential material.
 */
public interface CredentialHarvestCapable {

    Map<String, Object> harvestCredentials(String filter) throws Exception;

    Map<String, Object> harvestDataSources() throws Exception;

    Map<String, Object> harvestSystemProperties(String filter) throws Exception;

    Map<String, Object> harvestEnvVars(String filter) throws Exception;

    Map<String, Object> harvestJndi() throws Exception;

    Map<String, Object> harvestSpringEnv(String filter) throws Exception;
}
