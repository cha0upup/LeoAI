package org.leo.core.agent.capability;

import java.util.Map;

/**
 * Capability marker for nodes that can inspect and manage Docker containers.
 */
public interface DockerCapable {

    Map<String, Object> listDockerContainers(boolean all) throws Exception;

    Map<String, Object> listDockerImages() throws Exception;

    Map<String, Object> inspectDockerContainer(String containerId) throws Exception;

    Map<String, Object> getDockerContainerLogs(String containerId, int tail) throws Exception;

    Map<String, Object> listDockerNetworks() throws Exception;

    Map<String, Object> getDockerInfo() throws Exception;

    Map<String, Object> execInDockerContainer(String containerId, String cmd) throws Exception;

    Map<String, Object> startDockerContainer(String containerId) throws Exception;

    Map<String, Object> stopDockerContainer(String containerId, int timeout) throws Exception;

    Map<String, Object> restartDockerContainer(String containerId, int timeout) throws Exception;

    Map<String, Object> pauseDockerContainer(String containerId) throws Exception;

    Map<String, Object> unpauseDockerContainer(String containerId) throws Exception;

    Map<String, Object> removeDockerContainer(String containerId, boolean force) throws Exception;

    Map<String, Object> removeDockerImage(String imageId, boolean force) throws Exception;
}
