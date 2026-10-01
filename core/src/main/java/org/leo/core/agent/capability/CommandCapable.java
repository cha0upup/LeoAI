package org.leo.core.agent.capability;

import java.util.Map;

/**
 * Capability marker for nodes that can run remote command operations.
 */
public interface CommandCapable {

    Map<String, Object> execCommand(String type, String cmd, String processId) throws Exception;

    Map<String, Object> execSimpleCommand(String cmd) throws Exception;

    Map<String, Object> execSimpleCommand(String cmd, int timeoutSeconds) throws Exception;
}
