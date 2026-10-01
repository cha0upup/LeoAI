package org.leo.core.agent.capability;

import java.util.Map;
import java.util.Collections;
import java.util.Set;

/**
 * Capability marker for nodes that can load and invoke dynamic components.
 */
public interface ComponentManageCapable extends ComponentInvokeCapable {

    Set<String> getLoadedComponents();

    /** Components that the platform can deploy to this runtime. */
    default Set<String> getAvailableComponents() {
        return Collections.emptySet();
    }

    Map<String, Object> loadComponent(String componentId) throws Exception;

    /**
     * Deploy the current artifact again even when the logical component is already loaded.
     * Runtimes without a distinct reload mechanism may overwrite through their normal loader.
     */
    default Map<String, Object> reloadComponent(String componentId) throws Exception {
        return loadComponent(componentId);
    }
}
