package org.leo.service.generator;

import org.leo.core.generator.GeneratedArtifact;
import org.leo.core.generator.GenerationRequest;
import org.leo.core.generator.ScriptGeneratorProvider;
import org.leo.core.runtime.AgentRuntime;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Runtime-neutral registry and dispatch service for script generators. */
@Service
public final class ScriptGeneratorService {

    private final Map<AgentRuntime, ScriptGeneratorProvider> providers;

    public ScriptGeneratorService(List<ScriptGeneratorProvider> providers) {
        EnumMap<AgentRuntime, ScriptGeneratorProvider> indexed = new EnumMap<>(AgentRuntime.class);
        if (providers != null) {
            for (ScriptGeneratorProvider provider : providers) {
                if (provider == null || provider.getRuntime() == null) continue;
                ScriptGeneratorProvider previous = indexed.putIfAbsent(provider.getRuntime(), provider);
                if (previous != null) {
                    throw new IllegalStateException("重复的脚本生成器: " + provider.getRuntime().getValue());
                }
            }
        }
        this.providers = Collections.unmodifiableMap(indexed);
    }

    public Map<String, Object> getMetadata() {
        Map<String, Object> result = new LinkedHashMap<>();
        providers.forEach((runtime, provider) -> result.put(runtime.getValue(), provider.getMetadata()));
        return result;
    }

    public GeneratedArtifact generate(GenerationRequest request) throws Exception {
        if (request == null) throw new IllegalArgumentException("generation request不能为空");
        ScriptGeneratorProvider provider = providers.get(request.getRuntime());
        if (provider == null) {
            throw new IllegalArgumentException("未注册脚本生成器: " + request.getRuntime().getValue());
        }
        return provider.generate(request);
    }
}
