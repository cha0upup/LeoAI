package org.leo.core.agent.capability;

import java.util.Map;

/**
 * Capability marker for nodes that can inspect operating-system persistence entries.
 */
public interface PersistenceCapable {

    Map<String, Object> listPersistence() throws Exception;

    Map<String, Object> queryPersistence(String name, String type, String path) throws Exception;
}
