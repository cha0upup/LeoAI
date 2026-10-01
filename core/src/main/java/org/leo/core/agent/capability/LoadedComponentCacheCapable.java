package org.leo.core.agent.capability;

import java.util.Set;

/**
 * Internal capability for nodes that can seed loaded component metadata after connection probing.
 */
public interface LoadedComponentCacheCapable {

    void addLoadedComponent(String hostId, Set<String> loadedComponents);
}
