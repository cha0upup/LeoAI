package org.leo.core.agent.capability;

import java.util.Map;

/**
 * Capability marker for nodes that can inspect operating-system users and groups.
 */
public interface UserAccountCapable {

    Map<String, Object> listUsers() throws Exception;

    Map<String, Object> listGroups() throws Exception;

    Map<String, Object> queryUser(String username) throws Exception;

    Map<String, Object> queryGroup(String groupName) throws Exception;

    Map<String, Object> whoami() throws Exception;
}
