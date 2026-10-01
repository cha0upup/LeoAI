package org.leo.core.generator;

import org.leo.core.runtime.AgentRuntime;

import java.util.Map;

/** Equal-status generator SPI implemented by jmg and phpcore. */
public interface ScriptGeneratorProvider {

    AgentRuntime getRuntime();

    Map<String, Object> getMetadata();

    GeneratedArtifact generate(GenerationRequest request) throws Exception;
}
