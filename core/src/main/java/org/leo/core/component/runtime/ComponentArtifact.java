package org.leo.core.component.runtime;

import org.leo.core.runtime.AgentRuntime;

/** Immutable runtime-specific component payload. */
public final class ComponentArtifact {

    private final String componentId;
    private final String version;
    private final String digest;
    private final AgentRuntime runtime;
    private final ComponentDeliveryMode deliveryMode;
    private final byte[] content;

    public ComponentArtifact(String componentId, String version, String digest,
                             AgentRuntime runtime, ComponentDeliveryMode deliveryMode,
                             byte[] content) {
        if (componentId == null || componentId.isBlank()) {
            throw new IllegalArgumentException("componentId不能为空");
        }
        if (runtime == null || runtime == AgentRuntime.UNKNOWN) {
            throw new IllegalArgumentException("component runtime不能为空或unknown");
        }
        if (deliveryMode == null) {
            throw new IllegalArgumentException("deliveryMode不能为空");
        }
        this.componentId = componentId.trim();
        this.version = version;
        this.digest = digest;
        this.runtime = runtime;
        this.deliveryMode = deliveryMode;
        this.content = content == null ? new byte[0] : content.clone();
    }

    public String getComponentId() {
        return componentId;
    }

    public String getVersion() {
        return version;
    }

    public String getDigest() {
        return digest;
    }

    public AgentRuntime getRuntime() {
        return runtime;
    }

    public ComponentDeliveryMode getDeliveryMode() {
        return deliveryMode;
    }

    public byte[] getContent() {
        return content.clone();
    }
}
