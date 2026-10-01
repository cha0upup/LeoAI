package org.leo.core.agent.capability;

import java.util.function.Consumer;

/**
 * Internal capability for nodes whose service calls are scoped by a runtime host id.
 */
public interface HostScopedCapable {

    String getHostId();

    void setHostId(String hostId);

    void setHostIdChangeListener(Consumer<String> listener);
}
