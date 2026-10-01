package org.leo.core.agent.capability;

import java.util.Map;

/**
 * Capability marker for nodes that can inspect and manage operating-system processes.
 */
public interface ProcessCapable {

    Map<String, Object> listProcesses() throws Exception;

    Map<String, Object> findProcesses(String name, int pid, int port) throws Exception;

    Map<String, Object> killProcess(int pid, boolean force) throws Exception;
}
