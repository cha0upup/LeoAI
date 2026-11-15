package org.leo.web.config;

import dev.langchain4j.model.chat.StreamingChatModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.leo.ai.agent.AiAgentFactory;
import org.leo.ai.agent.PlatformAiAgent;
import org.leo.ai.channel.AiModelFailoverService;
import org.leo.ai.channel.DynamicModelProvider;
import org.leo.ai.platform.PlatformAiState;
import org.leo.core.entity.AiModelConfig;
import org.leo.web.adapter.ai.PlatformNodeAiBridgeTools;
import org.leo.web.adapter.ai.ShellGeneratorTools;
import org.leo.web.interceptor.BootstrapReadinessInterceptor;
import org.leo.web.interceptor.LoginInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.handler.MappedInterceptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebConfigTest {

    private static class Registry extends InterceptorRegistry {
        List<Object> interceptors() { return getInterceptors(); }
    }

    @Test
    void onlyTheExactLoginStatusAndSetupPathsArePublic() {
        var registry = new Registry();
        new WebConfig(mock(LoginInterceptor.class), mock(BootstrapReadinessInterceptor.class),
                "http://localhost:3000").addInterceptors(registry);
        var readiness = (MappedInterceptor) registry.interceptors().get(0);
        assertArrayEquals(new String[]{"/platform/**", "/agent-node/**"}, readiness.getIncludePathPatterns());
        org.junit.jupiter.api.Assertions.assertNull(readiness.getExcludePathPatterns());
        var interceptor = (MappedInterceptor) registry.interceptors().get(1);
        assertArrayEquals(new String[]{"/platform/**", "/agent-node/**"}, interceptor.getIncludePathPatterns());
        assertArrayEquals(new String[]{"/platform/user/login", "/platform/user/status", "/platform/setup"},
                interceptor.getExcludePathPatterns());
    }

    @Test
    void parsesAndDeduplicatesExplicitOrigins() {
        assertArrayEquals(
                new String[]{"http://localhost:3000", "https://console.example.com"},
                WebConfig.parseAllowedOrigins(
                        " http://localhost:3000,https://console.example.com,http://localhost:3000 "));
    }

    @Test
    void rejectsCredentialedWildcardOrigin() {
        assertThrows(IllegalArgumentException.class,
                () -> WebConfig.parseAllowedOrigins("*"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void bindsPlatformToolsAndModelCapabilitiesAtTheCompositionRoot(boolean supportsTools) {
        AiAgentFactory factory = mock(AiAgentFactory.class);
        DynamicModelProvider models = mock(DynamicModelProvider.class);
        AiModelFailoverService failover = mock(AiModelFailoverService.class);
        PlatformNodeAiBridgeTools tools = mock(PlatformNodeAiBridgeTools.class);
        ShellGeneratorTools shellTools = mock(ShellGeneratorTools.class);
        StreamingChatModel model = mock(StreamingChatModel.class);
        PlatformAiAgent agent = mock(PlatformAiAgent.class);
        AiModelConfig config = new AiModelConfig();
        config.setId(7);
        when(failover.selectForExecution(config)).thenReturn(new AiModelFailoverService.ModelSelection(
                config, null));
        when(models.buildRuntime(config, null)).thenReturn(new DynamicModelProvider.ModelRuntime(
                model, null, "responses", "openai", "https://example.test", "test-model", 4096,
                false, null, supportsTools, false, 32_768));
        var additionalTools = List.of(shellTools, tools);
        when(factory.createPlatformAiAgent(model, supportsTools, 32_768, additionalTools)).thenReturn(agent);

        var registry = WebConfig.platformAiAgentRegistry(factory, models, failover, tools, shellTools);

        assertSame(agent, registry.resolve(new PlatformAiState("state-1"), config, null).aiAgent());
        verify(factory).createPlatformAiAgent(model, supportsTools, 32_768, additionalTools);
    }
}
