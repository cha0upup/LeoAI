package org.leo.core.generator;

import org.leo.core.entity.Disguise;
import org.leo.core.runtime.AgentRuntime;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Runtime-neutral script generation request. */
public final class GenerationRequest {

    private final AgentRuntime runtime;
    private final String artifactType;
    private final Disguise requestDisguise;
    private final Disguise responseDisguise;
    private final Map<String, Object> options;

    public GenerationRequest(AgentRuntime runtime, String artifactType,
                             Disguise requestDisguise, Disguise responseDisguise,
                             Map<String, Object> options) {
        if (runtime == null || runtime == AgentRuntime.UNKNOWN) {
            throw new IllegalArgumentException("runtime不能为空或unknown");
        }
        if (artifactType == null || artifactType.isBlank()) {
            throw new IllegalArgumentException("artifactType不能为空");
        }
        this.runtime = runtime;
        this.artifactType = artifactType.trim();
        this.requestDisguise = requestDisguise;
        this.responseDisguise = responseDisguise;
        this.options = options == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(options));
    }

    public AgentRuntime getRuntime() { return runtime; }
    public String getArtifactType() { return artifactType; }
    public Disguise getRequestDisguise() { return requestDisguise; }
    public Disguise getResponseDisguise() { return responseDisguise; }
    public Map<String, Object> getOptions() { return options; }
}
