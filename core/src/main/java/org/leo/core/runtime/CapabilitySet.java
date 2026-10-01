package org.leo.core.runtime;

import com.fasterxml.jackson.annotation.JsonValue;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Explicit runtime capability overrides reported by a Puppet node.
 *
 * <p>An absent entry means "use the structurally implemented capability
 * interface result". Runtime modules can override that result when target
 * configuration or the host environment blocks an otherwise implemented ability.
 */
public final class CapabilitySet {

    private static final CapabilitySet EMPTY = new CapabilitySet(Collections.emptyList());

    private final Map<String, CapabilityStatus> statuses;

    public CapabilitySet(Collection<CapabilityStatus> statuses) {
        LinkedHashMap<String, CapabilityStatus> copy = new LinkedHashMap<>();
        if (statuses != null) {
            for (CapabilityStatus status : statuses) {
                if (status != null) {
                    copy.put(status.getName(), status);
                }
            }
        }
        this.statuses = Collections.unmodifiableMap(copy);
    }

    public static CapabilitySet empty() {
        return EMPTY;
    }

    public CapabilityStatus get(String name) {
        return name == null ? null : statuses.get(name);
    }

    public boolean isAvailable(String name, boolean structuralDefault) {
        CapabilityStatus status = get(name);
        return status != null ? status.isAvailable() : structuralDefault;
    }

    @JsonValue
    public List<CapabilityStatus> asList() {
        return Collections.unmodifiableList(new ArrayList<>(statuses.values()));
    }

    public boolean isEmpty() {
        return statuses.isEmpty();
    }
}
