package org.leo.ai.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class AiAgentPropertiesBindingTest {

    @Test
    void semanticTypeNamesPreserveExternalConfigurationKeys() {
        var source = new MapConfigurationPropertySource(Map.of(
                "leo.ai.agent.agent-node.main.max-parallel-tools", "11",
                "leo.ai.agent.agent-node.main.max-context-tokens", "24000",
                "leo.ai.agent.platform.main.max-parallel-tools", "3"));
        AiAgentProperties properties = new Binder(source)
                .bind("leo.ai.agent", Bindable.of(AiAgentProperties.class)).get();

        assertInstanceOf(AiAgentProperties.NodeAiSettings.class, properties.getAgentNode());
        assertInstanceOf(AiAgentProperties.PlatformAiSettings.class, properties.getPlatform());
        assertInstanceOf(AiAgentProperties.AiExecutionSettings.class, properties.getAgentNode().getMain());
        assertEquals(11, properties.getAgentNode().getMain().getMaxParallelTools());
        assertEquals(24000, properties.getAgentNode().getMain().getMaxContextTokens());
        assertEquals(3, properties.getPlatform().getMain().getMaxParallelTools());
    }
}
