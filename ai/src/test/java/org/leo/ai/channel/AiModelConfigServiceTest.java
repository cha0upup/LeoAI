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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;

import java.util.List;
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
    void catalogDecryptsSharedProviderCredentialsOnceWithoutChangingTheStoredRows() {
        AiModelConfig first = catalogRow(7, 1);
        AiModelConfig sibling = catalogRow(8, 1);
        AiModelConfig other = catalogRow(9, 2);
        when(mapper.listAll()).thenReturn(List.of(first, sibling, other));
        when(secretCryptoService.decrypt("stored-key-1")).thenReturn("fixture-key-1");
        when(secretCryptoService.decrypt("stored-headers-1")).thenReturn("fixture-headers-1");
        when(secretCryptoService.decrypt("stored-key-2")).thenReturn("fixture-key-2");
        when(secretCryptoService.decrypt("stored-headers-2")).thenReturn("fixture-headers-2");

        var models = service.listCatalog().stream().map(AiModelConfigService.CatalogEntry::config).toList();

        assertEquals(List.of(7, 8, 9), models.stream().map(AiModelConfig::getId).toList());
        assertEquals(List.of("fixture-key-1", "fixture-key-1", "fixture-key-2"),
                models.stream().map(AiModelConfig::getApiKey).toList());
        assertEquals("stored-key-1", sibling.getApiKey());
        assertEquals("stored-headers-1", first.getHeadersJson());
        verify(secretCryptoService).decrypt("stored-key-1");
        verify(secretCryptoService).decrypt("stored-headers-1");
    }

    @Test
    void availableModelsResolveCapabilitiesInBatchesAndApplyTheServiceEligibilityRules() {
        var usable = catalogRow(7, 1);
        var noText = catalogRow(8, 1);
        var noStream = catalogRow(9, 1);
        var noOutput = catalogRow(10, 2);
        when(mapper.listEnabled()).thenReturn(List.of(usable, noText, noStream, noOutput));
        AiModelCapability textOff = baseline(noText.getModel());
        textOff.setSupportsTextGeneration(0);
        AiModelCapability outputOff = baseline(noOutput.getModel());
        outputOff.setMaxOutputTokens(0);
        when(capabilityMapper.findByModelNames(anyString())).thenReturn(List.of(textOff, outputOff));
        AiModelCapability streamOff = new AiModelCapability();
        streamOff.setSupportsStreaming(0);
        when(capabilityMapper.findObservations(anyString())).thenReturn(List.of(
                new AiModelCapabilityMapper.MatchedObservation(noStream.getId(), streamOff)));

        assertEquals(List.of(usable.getId()), service.listAvailableModels().stream()
                .map(entry -> entry.config().getId()).toList());

        verify(mapper).listEnabled();
        verify(mapper, never()).listAll();
        verify(capabilityMapper).findByModelNames(anyString());
        verify(capabilityMapper).findObservations(anyString());
        verify(capabilityMapper, never()).findByModelName(anyString());
        verify(capabilityMapper, never()).findObservation(any(), any(), any(), any());
        verify(secretCryptoService).decrypt("stored-key-1");
        verify(secretCryptoService).decrypt("stored-headers-1");
    }

    @Test
    void providerImportReusesItsConnectionAndDoesNotReloadInsertedModels() {
        AiProvider provider = new AiProvider();
        provider.setName("Imported provider");
        provider.setBaseUrl("https://example.test/v1");
        provider.setApiKey("fixture-key");
        AiModelConfig selected = catalogRow(7, 1);
        selected.setName("selected");
        selected.setIsActive(1);
        AiModelConfig other = catalogRow(8, 1);
        other.setName("other");
        provider.setModels(List.of(selected, other));
        when(providerMapper.insert(any(AiProvider.class))).thenAnswer(invocation -> {
            invocation.<AiProvider>getArgument(0).setId(11);
            return 1;
        });

        AiProvider saved = service.createProvider(provider);

        assertEquals(11, saved.getId());
        assertEquals("fixture-key", saved.getApiKey());
        assertNull(saved.getModels());
        assertEquals(11, selected.getProviderId());
        assertEquals(11, other.getProviderId());
        assertEquals("fixture-key", other.getApiKey());
        verify(providerMapper, never()).findById(any());
        verify(mapper, never()).findById(any());
        verify(mapper, never()).hasModels();
        verify(mapper).clearActive();
        verify(mapper, times(2)).insert(any(AiModelConfig.class));
        verify(capabilityMapper).findByModelName(selected.getModel());
        verify(capabilityMapper).findByModelName(other.getModel());
    }

    private static AiModelCapability baseline(String model) {
        AiModelCapability row = new AiModelCapability();
        row.setModelName(model);
        row.setSupportsTextGeneration(1);
        row.setSupportsStreaming(1);
        row.setMaxOutputTokens(4096);
        return row;
    }

    private static AiModelConfig catalogRow(int id, int providerId) {
        AiModelConfig row = new AiModelConfig();
        row.setId(id);
        row.setProviderId(providerId);
        row.setModel("fixture-model-" + id);
        row.setApiKey("stored-key-" + providerId);
        row.setHeadersJson("stored-headers-" + providerId);
        return row;
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
    void providerValidationLeavesTheMyBatisCachedModelSettingsUntouched() {
        AiSecretCryptoService crypto = new AiSecretCryptoService("master-key-a", "unused");
        service = new AiModelConfigService(mapper, providerMapper, capabilityMapper, crypto, events);
        AiProvider provider = new AiProvider();
        provider.setId(1);
        provider.setName("saved-provider");
        provider.setProviderKey("custom");
        provider.setEnabled(1);
        provider.setProtocol("chat_completions");
        provider.setBaseUrl("https://example.test/v1");
        provider.setApiKey(crypto.encrypt("saved-key"));
        when(providerMapper.findById(1)).thenReturn(provider);
        AiModelConfig stored = new AiModelConfig();
        stored.setProviderId(1);
        stored.setModel("test-model");
        when(mapper.listSettingsByProviderId(1)).thenReturn(List.of(stored));
        AiProvider patch = new AiProvider();
        patch.setName("updated-provider");
        patch.setBaseUrl("https://example.test/v2");
        patch.setApiKey("replacement-key");

        service.updateProvider(1, patch);

        assertNull(stored.getProviderName());
        assertNull(stored.getBaseUrl());
        assertNull(stored.getApiKey());
        assertNull(stored.getHeadersJson());
        verify(mapper).listSettingsByProviderId(1);
        verify(mapper, never()).listAll();
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
