package org.leo.core.agent.capability;

import java.util.Map;

/**
 * Capability marker for nodes that can inspect and manage operating-system network shares.
 */
public interface NetworkShareCapable {

    Map<String, Object> listNetworkShares() throws Exception;

    Map<String, Object> listNetworkMounts() throws Exception;

    Map<String, Object> queryNetworkShare(String shareName) throws Exception;

    Map<String, Object> connectNetworkShare(String remotePath, String localDrive,
                                            String mountPoint, String username,
                                            String password) throws Exception;

    Map<String, Object> disconnectNetworkShare(String target) throws Exception;
}
