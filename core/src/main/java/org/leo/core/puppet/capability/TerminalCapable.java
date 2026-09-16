package org.leo.core.puppet.capability;

import java.util.List;
import java.util.Map;

/**
 * Nodes that keep interactive terminal sessions and return ordered output.
 */
public interface TerminalCapable extends CommandCapable {
    /** Mode applies only to init; includeOutput applies to init and input. */
    Map<String, Object> execTerminal(String type, String cmd, String processId,
                                     String terminalMode, boolean includeOutput) throws Exception;

    /** Read several processes in one node request, without long polling. */
    Map<String, Object> readTerminals(List<String> processIds) throws Exception;

    /** Command tools explicitly read their output in a later call. */
    @Override
    default Map<String, Object> execCommand(String type, String cmd, String processId) throws Exception {
        return execTerminal(type, cmd, processId, null, false);
    }
}
