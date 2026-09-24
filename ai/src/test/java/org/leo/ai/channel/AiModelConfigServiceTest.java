package org.leo.ai.channel;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.leo.core.entity.AiModelCapability;
import org.leo.core.entity.AiModelConfig;
import org.leo.core.entity.AiProvider;
import org.leo.dao.mapper.AiModelCapabilityMapper;
import org.leo.dao.mapper.AiModelConfigMapper;
import org.leo.dao.mapper.AiProviderMapper;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;

import java.util.Map;

@ExtendWith(MockitoExtension.class)
class AiModelConfigServiceTest {

    @Mock private AiModelConfigMapper mapper;
    @Mock private AiProviderMapper providerMapper;
    @Mock private AiModelCapabilityMapper capabilityMapper;
    @Mock private AiSecretCryptoService secretCryptoService;

    @Mock private org.springframework.context.ApplicationEventPublisher events;
    private AiModelConfigService service;

    @BeforeEach
    void setUp() {
        service = new AiModelConfigService(mapper, providerMapper, capabilityMapper, secretCryptoService, events);
    }

    @Test
    void resolvesExplicitEnabledModel() {
        AiModelConfig model = new AiModelConfig();
        model.setId(7);
        model.setEnabled(1);
        when(mapper.findById(7)).thenReturn(model);

        AiModelConfig resolved = service.resolve(7);
        assertNotSame(model, resolved);
        assertEquals(7, resolved.getId());
    }

    @Test
    void doesNotSilentlyFallbackWhenExplicitModelIsMissing() {
        when(mapper.findById(7)).thenReturn(null);

        assertNull(service.resolve(7));
    }

    @Test
    void usesActiveModelOnlyWhenNoModelWasRequested() {
        AiModelConfig active = new AiModelConfig();
        when(mapper.findActive()).thenReturn(active);

        assertNotSame(active, service.resolve(null));
        verifyNoInteractions(providerMapper);
    }

    @Test
    void repeatedProviderReadsDoNotDecryptTheMyBatisCachedEntityInPlace() {
        AiSecretCryptoService crypto = new AiSecretCryptoService("master-key-a", "unused");
        service = new AiModelConfigService(mapper, providerMapper, capabilityMapper, crypto, events);
        AiProvider stored = new AiProvider();
        stored.setId(1);
        stored.setApiKey(crypto.encrypt("sk-provider"));
        stored.setHeadersJson(crypto.encrypt("{\"X-Test\":\"secret\"}"));
        when(providerMapper.findById(1)).thenReturn(stored);

        AiProvider first = service.findProviderById(1);
        AiProvider second = service.findProviderById(1);

        assertEquals("sk-provider", first.getApiKey());
        assertEquals("sk-provider", second.getApiKey());
        assertEquals("{\"X-Test\":\"secret\"}", second.getHeadersJson());
        assertNotSame(stored, first);
        assertNotSame(stored, second);
        assertTrue(crypto.isEncrypted(stored.getApiKey()));
        assertTrue(crypto.isEncrypted(stored.getHeadersJson()));
    }

    @Test
    void clampsCustomContextWindowToCapabilityLimit() {
        AiModelConfig model = new AiModelConfig();
        model.setModel("model-a");
        model.setContextWindowTokens(65_536);

        AiModelCapability capability = new AiModelCapability();
        capability.setModelName("model-a");
        capability.setContextWindowTokens(32_768);
        capability.setMaxOutputTokens(4_096);
        capability.setSupportsTextGeneration(1);
        capability.setSupportsReasoning(0);
        capability.setSupportsStreaming(1);
        capability.setSupportsFunctionCalling(1);
        capability.setSupportsStructuredOutput(0);
        capability.setSupportsWebSearch(0);
        capability.setSupportsParallelToolCalls(0);
        when(capabilityMapper.findByModelName("model-a")).thenReturn(capability);

        assertEquals(32_768, service.getContextWindowTokens(model));
    }

    @Test
    void appliesOnlyConclusiveProbeFlagsToNewCapability() {
        AiModelConfig model = new AiModelConfig();
        model.setProviderId(1);
        model.setModel("custom-model");
        model.setProviderKey("custom");
        when(capabilityMapper.findByModelName("custom-model")).thenReturn(null);

        AiModelCapability report = service.applyProbeResult(model, Map.of(
                "textGeneration", true,
                "streaming", false,
                "functionCalling", true));

        ArgumentCaptor<AiModelCapability> captured = ArgumentCaptor.forClass(AiModelCapability.class);
        verify(capabilityMapper).saveObservation(org.mockito.ArgumentMatchers.eq(1), org.mockito.ArgumentMatchers.eq("chat_completions"),
                org.mockito.ArgumentMatchers.eq("custom-model"), org.mockito.ArgumentMatchers.anyString(), captured.capture());
        AiModelCapability row = captured.getValue();
        assertEquals("provider_probe", report.getSource());
        assertEquals(1, row.getSupportsTextGeneration());
        assertEquals(0, row.getSupportsStreaming());
        assertEquals(1, row.getSupportsFunctionCalling());
        // 没有证据的能力保持保守默认，不因探测不确定而被误判。
        assertNull(row.getSupportsReasoning());
        assertNull(row.getSupportsStructuredOutput());
        assertEquals(0, report.getSupportsReasoning());
        assertEquals(0, report.getSupportsStructuredOutput());
        verify(capabilityMapper, org.mockito.Mockito.never()).insert(org.mockito.ArgumentMatchers.any());
        verify(capabilityMapper, org.mockito.Mockito.never()).update(org.mockito.ArgumentMatchers.any());
    }
}
