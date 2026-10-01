package org.leo.core.runtime;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Runtime availability and constraints for one stable Agent capability. */
public final class CapabilityStatus {

    private final String name;
    private final boolean available;
    private final String reason;
    private final Map<String, Object> constraints;

    public CapabilityStatus(String name, boolean available, String reason,
                            Map<String, Object> constraints) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("capability name不能为空");
        }
        this.name = name.trim();
        this.available = available;
        this.reason = reason;
        this.constraints = constraints == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(constraints));
    }

    public static CapabilityStatus available(String name) {
        return new CapabilityStatus(name, true, null, Collections.emptyMap());
    }

    public static CapabilityStatus unavailable(String name, String reason) {
        return new CapabilityStatus(name, false, reason, Collections.emptyMap());
    }

    public String getName() {
        return name;
    }

    public boolean isAvailable() {
        return available;
    }

    public String getReason() {
        return reason;
    }

    public Map<String, Object> getConstraints() {
        return constraints;
    }
}
