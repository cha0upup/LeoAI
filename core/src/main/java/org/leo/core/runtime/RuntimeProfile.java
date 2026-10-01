package org.leo.core.runtime;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Immutable runtime facts learned during the Agent handshake. */
public final class RuntimeProfile {

    private final AgentRuntime runtime;
    private final String version;
    private final String sapi;
    private final String osFamily;
    private final String architecture;
    private final Set<String> extensions;
    private final Set<String> disabledFunctions;
    private final CapabilitySet capabilities;
    private final Map<String, Object> attributes;

    private RuntimeProfile(Builder builder) {
        this.runtime = builder.runtime == null ? AgentRuntime.UNKNOWN : builder.runtime;
        this.version = builder.version;
        this.sapi = builder.sapi;
        this.osFamily = builder.osFamily;
        this.architecture = builder.architecture;
        this.extensions = Collections.unmodifiableSet(new LinkedHashSet<>(builder.extensions));
        this.disabledFunctions = Collections.unmodifiableSet(new LinkedHashSet<>(builder.disabledFunctions));
        this.capabilities = builder.capabilities == null ? CapabilitySet.empty() : builder.capabilities;
        this.attributes = Collections.unmodifiableMap(new LinkedHashMap<>(builder.attributes));
    }

    public static RuntimeProfile minimal(AgentRuntime runtime) {
        return builder(runtime).build();
    }

    public static Builder builder(AgentRuntime runtime) {
        return new Builder(runtime);
    }

    public AgentRuntime getRuntime() {
        return runtime;
    }

    public String getVersion() {
        return version;
    }

    public String getSapi() {
        return sapi;
    }

    public String getOsFamily() {
        return osFamily;
    }

    public String getArchitecture() {
        return architecture;
    }

    public Set<String> getExtensions() {
        return extensions;
    }

    public Set<String> getDisabledFunctions() {
        return disabledFunctions;
    }

    public CapabilitySet getCapabilities() {
        return capabilities;
    }

    public Map<String, Object> getAttributes() {
        return attributes;
    }

    public static final class Builder {
        private final AgentRuntime runtime;
        private String version;
        private String sapi;
        private String osFamily;
        private String architecture;
        private Set<String> extensions = new LinkedHashSet<>();
        private Set<String> disabledFunctions = new LinkedHashSet<>();
        private CapabilitySet capabilities = CapabilitySet.empty();
        private Map<String, Object> attributes = new LinkedHashMap<>();

        private Builder(AgentRuntime runtime) {
            this.runtime = runtime;
        }

        public Builder version(String version) {
            this.version = version;
            return this;
        }

        public Builder sapi(String sapi) {
            this.sapi = sapi;
            return this;
        }

        public Builder osFamily(String osFamily) {
            this.osFamily = osFamily;
            return this;
        }

        public Builder architecture(String architecture) {
            this.architecture = architecture;
            return this;
        }

        public Builder extensions(Set<String> extensions) {
            this.extensions = extensions == null ? new LinkedHashSet<>() : new LinkedHashSet<>(extensions);
            return this;
        }

        public Builder disabledFunctions(Set<String> disabledFunctions) {
            this.disabledFunctions = disabledFunctions == null
                    ? new LinkedHashSet<>() : new LinkedHashSet<>(disabledFunctions);
            return this;
        }

        public Builder capabilities(CapabilitySet capabilities) {
            this.capabilities = capabilities;
            return this;
        }

        public Builder attributes(Map<String, Object> attributes) {
            this.attributes = attributes == null ? new LinkedHashMap<>() : new LinkedHashMap<>(attributes);
            return this;
        }

        public RuntimeProfile build() {
            return new RuntimeProfile(this);
        }
    }
}
