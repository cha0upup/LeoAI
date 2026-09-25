package org.leo.ai.channel;

import org.leo.core.entity.AiModelConfig;
import org.leo.core.entity.AiModelCapability;
import org.leo.core.entity.AiProvider;
import org.leo.core.entity.ProviderCapabilities;
import org.leo.dao.mapper.AiModelCapabilityMapper;
import org.leo.dao.mapper.AiModelConfigMapper;
import org.leo.dao.mapper.AiProviderMapper;
import org.springframework.stereotype.Service;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Transactional;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * AI 供应商及模型配置服务。
 *
 * <p>模型继承所属供应商的连接配置，同一时刻只允许一条 {@code is_active=1}。
 * 激活时会触发 {@link DynamicModelProvider#refresh()} 热切换底层模型。
 */
@Service
public class AiModelConfigService {

    private final AiModelConfigMapper mapper;
    private final AiProviderMapper providerMapper;
    private final AiModelCapabilityMapper capabilityMapper;
    private final AiSecretCryptoService secretCryptoService;
    private final ApplicationEventPublisher events;

    public AiModelConfigService(AiModelConfigMapper mapper,
                                AiProviderMapper providerMapper,
                                AiModelCapabilityMapper capabilityMapper,
                                AiSecretCryptoService secretCryptoService,
                                ApplicationEventPublisher events) {
        this.mapper = mapper;
        this.providerMapper = providerMapper;
        this.capabilityMapper = capabilityMapper;
        this.secretCryptoService = secretCryptoService;
        this.events = events;
    }

    public List<AiModelConfig> listAll() {
        return decryptModels(mapper.listAll());
    }

    public List<AiModelConfig> listEnabled() {
        return decryptModels(mapper.listEnabled());
    }

    public List<AiProvider> listProviders() {
        return decryptProviders(providerMapper.listAll());
    }

    public List<AiModelCapability> listModelCapabilities() {
        return capabilityMapper.listAll();
    }

    public ProviderCapabilities capabilitiesForModel(String providerKey, String modelName) {
        if (modelName == null || modelName.isBlank()) {
            return ProviderCapabilities.missing();
        }
        AiModelCapability row = capabilityMapper.findByModelName(normalizeCapabilityModelName(providerKey, modelName));
        if (row == null) {
            return ProviderCapabilities.conservativeDefault();
        }
        return capabilityFromRow(row);
    }

    public ProviderCapabilities capabilitiesForModel(AiModelConfig config) {
        if (config == null) return ProviderCapabilities.missing();
        ProviderCapabilities baseline = capabilitiesForModel(config.getProviderKey(), config.getModel());
        return mergeCapabilities(baseline, findObservation(config));
    }

    private static ProviderCapabilities mergeCapabilities(ProviderCapabilities baseline, AiModelCapability observed) {
        if (observed == null) return baseline;
        return new ProviderCapabilities(true, "recognized", "provider_probe",
                baseline.contextWindowTokens(), baseline.maxOutputTokens(),
                observedFlag(observed.getSupportsTextGeneration(), baseline.supportsTextGeneration()),
                observedFlag(observed.getSupportsReasoning(), baseline.supportsReasoning()),
                observedFlag(observed.getSupportsStreaming(), baseline.supportsStreaming()),
                observedFlag(observed.getSupportsFunctionCalling(), baseline.supportsFunctionCalling()),
                observedFlag(observed.getSupportsStructuredOutput(), baseline.supportsStructuredOutput()),
                baseline.supportsWebSearch(), baseline.supportsParallelToolCalls());
    }

    private AiModelCapability findObservation(AiModelConfig config) {
        if (config.getProviderId() == null || isBlank(config.getModel())) return null;
        return capabilityMapper.findObservation(config.getProviderId(), DynamicModelProvider.resolveProtocol(config),
                config.getModel(), ModelConfigurationKey.connection(config));
    }

    private static boolean observedFlag(Integer value, boolean baseline) {
        return value == null ? baseline : flag(value);
    }

    public String capabilityModelName(AiModelConfig config) {
        return config == null ? "" : normalizeCapabilityModelName(config.getProviderKey(), config.getModel());
    }

    private static ProviderCapabilities capabilityFromRow(AiModelCapability row) {
        return new ProviderCapabilities(true,
                "recognized",
                row.getSource() == null || row.getSource().isBlank() ? "system" : row.getSource(),
                positiveOrDefault(row.getContextWindowTokens(), 32_768),
                nonNegativeOrDefault(row.getMaxOutputTokens(), 4_096),
                flag(row.getSupportsTextGeneration()),
                flag(row.getSupportsReasoning()),
                flag(row.getSupportsStreaming()),
                flag(row.getSupportsFunctionCalling()),
                flag(row.getSupportsStructuredOutput()),
                flag(row.getSupportsWebSearch()),
                flag(row.getSupportsParallelToolCalls()));
    }

    public AiProvider findProviderById(Integer id) {
        return id == null ? null : decryptProvider(providerMapper.findById(id));
    }

    @Transactional
    public AiProvider createProvider(AiProvider row) {
        validateProvider(row, true);
        normalizeProvider(row, null);
        String now = nowSqlite();
        row.setCreateTime(now);
        row.setUpdateTime(now);
        withEncryptedProviderSecrets(row, () -> providerMapper.insert(row));
        return findProviderById(row.getId());
    }

    @Transactional
    public AiProvider createProviderWithModels(AiProvider row) {
        AiProvider saved = createProvider(row);
        List<AiModelConfig> models = row.getModels();
        if (models == null || models.isEmpty()) {
            return saved;
        }

        boolean providerEnabled = Integer.valueOf(1).equals(saved.getEnabled());
        boolean hasExistingModels = mapper.countAll() > 0;
        int requestedDefaultIndex = -1;
        for (int i = 0; i < models.size(); i++) {
            if (Integer.valueOf(1).equals(models.get(i).getIsActive())) {
                requestedDefaultIndex = i;
                break;
            }
        }
        int defaultIndex = providerEnabled
                ? (requestedDefaultIndex >= 0 ? requestedDefaultIndex : (hasExistingModels ? -1 : 0))
                : -1;
        if (defaultIndex >= 0) {
            mapper.clearActive();
        }

        for (int i = 0; i < models.size(); i++) {
            AiModelConfig model = models.get(i);
            model.setProviderId(saved.getId());
            model.setIsActive(i == defaultIndex ? 1 : 0);
            model.setEnabled(Integer.valueOf(1).equals(saved.getEnabled()) ? 1 : 0);
            createModel(model, false, false);
        }
        if (defaultIndex >= 0) {
            notifyModelRefresh();
        }
        return findProviderById(saved.getId());
    }

    @Transactional
    public AiProvider updateProvider(Integer id, AiProvider patch) {
        AiProvider existing = findProviderById(id);
        if (existing == null) return null;
        validateProvider(patch, false);
        normalizeProvider(patch, existing);
        existing.setUpdateTime(nowSqlite());
        withEncryptedProviderSecrets(existing, () -> providerMapper.update(existing));
        syncProviderSnapshot(existing);
        if (!Integer.valueOf(1).equals(existing.getEnabled())) {
            mapper.disableByProviderId(existing.getId(), nowSqlite());
        }
        notifyModelRefresh();
        return findProviderById(id);
    }

    @Transactional
    public boolean deleteProvider(Integer id) {
        AiProvider existing = findProviderById(id);
        if (existing == null) return false;
        mapper.clearFallbackByProviderId(id);
        mapper.deleteByProviderId(id);
        providerMapper.deleteById(id);
        notifyModelRefresh();
        return true;
    }

    public AiModelConfig findById(Integer id) {
        return id == null ? null : decryptModel(mapper.findById(id));
    }

    public AiModelConfig getActive() {
        return decryptModel(mapper.findActive());
    }

    @Transactional
    public AiModelConfig create(AiModelConfig row) {
        return createModel(row, true, true);
    }

    @Transactional
    public AiModelCapability createCapability(AiModelCapability row) {
        validateCapability(row);
        normalizeCapability(row);
        if (capabilityMapper.findByModelName(row.getModelName()) != null) {
            throw new IllegalArgumentException("模型能力已存在: " + row.getModelName());
        }
        String now = nowSqlite();
        row.setCreateTime(now);
        row.setUpdateTime(now);
        capabilityMapper.insert(row);
        notifyModelRefresh();
        return capabilityMapper.findByModelName(row.getModelName());
    }

    @Transactional
    public AiModelCapability updateCapability(String modelName, AiModelCapability patch) {
        String normalizedName = normalizeCapabilityKey(modelName);
        AiModelCapability existing = capabilityMapper.findByModelName(normalizedName);
        if (existing == null) return null;
        if (patch == null) {
            throw new IllegalArgumentException("模型能力配置不能为空");
        }
        existing.setSource("manual");
        existing.setContextWindowTokens(patch.getContextWindowTokens());
        existing.setMaxOutputTokens(patch.getMaxOutputTokens());
        existing.setSupportsTextGeneration(patch.getSupportsTextGeneration());
        existing.setSupportsReasoning(patch.getSupportsReasoning());
        existing.setSupportsStreaming(patch.getSupportsStreaming());
        existing.setSupportsFunctionCalling(patch.getSupportsFunctionCalling());
        existing.setSupportsStructuredOutput(patch.getSupportsStructuredOutput());
        existing.setSupportsWebSearch(patch.getSupportsWebSearch());
        existing.setSupportsParallelToolCalls(patch.getSupportsParallelToolCalls());
        if (patch.getRemark() != null) existing.setRemark(patch.getRemark());
        validateCapability(existing);
        normalizeCapability(existing);
        existing.setUpdateTime(nowSqlite());
        capabilityMapper.update(existing);
        notifyModelRefresh();
        return capabilityMapper.findByModelName(existing.getModelName());
    }

    /**
     * 将真实探测中有明确证据的结果写入当前连接的能力覆盖。null 表示探测不确定，保留原值；
     * 这样网络、鉴权或服务端偶发异常不会误伤模型能力配置。
     */
    @Transactional
    public AiModelCapability applyProbeResult(AiModelConfig config, Map<String, Boolean> verifiedFeatures) {
        if (config == null) throw new IllegalArgumentException("模型配置不能为空");
        String modelName = capabilityModelName(config);
        if (modelName.isBlank()) throw new IllegalArgumentException("模型名称不能为空");

        if (config.getProviderId() == null) throw new IllegalArgumentException("providerId 不能为空");
        AiModelCapability previous = findObservation(config);
        AiModelCapability row = new AiModelCapability();
        if (previous != null) {
            row.setSupportsTextGeneration(previous.getSupportsTextGeneration());
            row.setSupportsReasoning(previous.getSupportsReasoning());
            row.setSupportsStreaming(previous.getSupportsStreaming());
            row.setSupportsFunctionCalling(previous.getSupportsFunctionCalling());
            row.setSupportsStructuredOutput(previous.getSupportsStructuredOutput());
        }
        applyProbeFlag(verifiedFeatures, "textGeneration", row::setSupportsTextGeneration);
        applyProbeFlag(verifiedFeatures, "reasoning", row::setSupportsReasoning);
        applyProbeFlag(verifiedFeatures, "streaming", row::setSupportsStreaming);
        applyProbeFlag(verifiedFeatures, "functionCalling", row::setSupportsFunctionCalling);
        applyProbeFlag(verifiedFeatures, "structuredOutput", row::setSupportsStructuredOutput);
        row.setSource("probe");
        row.setUpdateTime(nowSqlite());
        capabilityMapper.saveObservation(config.getProviderId(), DynamicModelProvider.resolveProtocol(config),
                config.getModel(), ModelConfigurationKey.connection(config), row);
        notifyModelRefresh();
        return capabilityReport(modelName,
                mergeCapabilities(capabilitiesForModel(config.getProviderKey(), config.getModel()), row));
    }

    private static AiModelCapability capabilityReport(String modelName, ProviderCapabilities caps) {
        AiModelCapability report = new AiModelCapability();
        report.setModelName(modelName);
        report.setSource(caps.source());
        report.setContextWindowTokens(caps.contextWindowTokens());
        report.setMaxOutputTokens(caps.maxOutputTokens());
        report.setSupportsTextGeneration(caps.supportsTextGeneration() ? 1 : 0);
        report.setSupportsReasoning(caps.supportsReasoning() ? 1 : 0);
        report.setSupportsStreaming(caps.supportsStreaming() ? 1 : 0);
        report.setSupportsFunctionCalling(caps.supportsFunctionCalling() ? 1 : 0);
        report.setSupportsStructuredOutput(caps.supportsStructuredOutput() ? 1 : 0);
        report.setSupportsWebSearch(caps.supportsWebSearch() ? 1 : 0);
        report.setSupportsParallelToolCalls(caps.supportsParallelToolCalls() ? 1 : 0);
        return report;
    }

    @Transactional
    public boolean deleteCapability(String modelName) {
        String normalizedName = normalizeCapabilityKey(modelName);
        if (normalizedName == null || normalizedName.isBlank()) return false;
        boolean deleted = capabilityMapper.deleteByModelName(normalizedName) > 0;
        if (deleted) notifyModelRefresh();
        return deleted;
    }

    private AiModelConfig createModel(AiModelConfig row, boolean autoActivateFirst, boolean notify) {
        validateRequired(row);
        normalize(row);
        applyProvider(row);
        validateFallback(row, null);
        String now = nowSqlite();
        row.setCreateTime(now);
        row.setUpdateTime(now);
        if (row.getIsActive() == null) {
            row.setIsActive(0);
        }
        if (row.getEnabled() == null) {
            row.setEnabled(1);
        }
        if (autoActivateFirst && mapper.countAll() == 0 && Integer.valueOf(1).equals(row.getEnabled())) {
            row.setIsActive(1);
            row.setEnabled(1);
        }
        if (Integer.valueOf(1).equals(row.getIsActive())) {
            assertUsable(row);
            mapper.clearActive();
        }
        withEncryptedModelSecrets(row, () -> mapper.insert(row));
        if (notify && Integer.valueOf(1).equals(row.getIsActive())) {
            notifyModelRefresh();
        }
        return findById(row.getId());
    }

    @Transactional
    public AiModelConfig update(Integer id, AiModelConfig patch) {
        AiModelConfig existing = findById(id);
        if (existing == null) return null;
        boolean wasActive = Integer.valueOf(1).equals(existing.getIsActive());
        if (!isBlank(patch.getName())) existing.setName(patch.getName().trim());
        if (patch.getProviderId() != null) existing.setProviderId(patch.getProviderId());
        if (!isBlank(patch.getProviderKey())) existing.setProviderKey(patch.getProviderKey().trim());
        if (patch.getProviderName() != null) existing.setProviderName(blankToNull(patch.getProviderName()));
        if (!isBlank(patch.getBaseUrl())) existing.setBaseUrl(patch.getBaseUrl().trim());
        if (patch.getApiKey() != null && !patch.getApiKey().isEmpty()) {
            existing.setApiKey(patch.getApiKey());
        }
        if (!isBlank(patch.getModel())) existing.setModel(patch.getModel().trim());
        if (!isBlank(patch.getProtocol())) {
            existing.setProtocol(patch.getProtocol().trim());
        }
        if (!isBlank(patch.getCompletionsPath())) {
            existing.setCompletionsPath(patch.getCompletionsPath().trim());
        }
        existing.setMaxOutputTokens(patch.getMaxOutputTokens() != null && patch.getMaxOutputTokens() > 0
                ? patch.getMaxOutputTokens() : null);
        existing.setThinkingEnabled(normalizeTriStateFlag(patch.getThinkingEnabled()));
        if (patch.getReasoningEffort() != null) {
            existing.setReasoningEffort(normalizeReasoningEffort(patch.getReasoningEffort()));
        }
        existing.setContextWindowTokens(patch.getContextWindowTokens() != null && patch.getContextWindowTokens() > 0
                ? patch.getContextWindowTokens() : null);
        if (patch.getTemperature() != null) {
            existing.setTemperature(normalizeTemperature(patch.getTemperature()));
        }
        if (patch.getHeadersJson() != null) existing.setHeadersJson(blankToNull(patch.getHeadersJson()));
        if (patch.getEnabled() != null) {
            existing.setEnabled(Integer.valueOf(1).equals(patch.getEnabled()) ? 1 : 0);
        }
        existing.setFallbackModelId(patch.getFallbackModelId());
        if (patch.getRemark() != null) existing.setRemark(patch.getRemark());
        boolean activating = patch.getIsActive() != null && Integer.valueOf(1).equals(patch.getIsActive());
        if (patch.getIsActive() != null) {
            existing.setIsActive(activating ? 1 : 0);
            if (activating) {
                existing.setEnabled(1);
            }
        }
        if (Integer.valueOf(1).equals(existing.getIsActive())
                && !Integer.valueOf(1).equals(existing.getEnabled())) {
            throw new IllegalArgumentException("默认模型必须保持启用，请先设置新的默认模型");
        }
        existing.setUpdateTime(nowSqlite());
        normalize(existing);
        applyProvider(existing);
        validateFallback(existing, id);
        if (activating && !Integer.valueOf(1).equals(existing.getIsActive())) {
            throw new IllegalArgumentException("供应商已禁用，不能设为默认模型");
        }
        if (Integer.valueOf(1).equals(existing.getIsActive())) {
            assertUsable(existing);
        }
        if (activating) {
            mapper.clearActive();
        }
        withEncryptedModelSecrets(existing, () -> mapper.update(existing));
        boolean isActiveNow = Integer.valueOf(1).equals(existing.getIsActive());
        if (wasActive || isActiveNow) {
            notifyModelRefresh();
        }
        return findById(id);
    }

    @Transactional
    public boolean deleteById(Integer id) {
        AiModelConfig row = findById(id);
        if (row == null) return false;
        mapper.clearFallbackByModelId(id);
        mapper.deleteById(id);
        notifyModelRefresh();
        return true;
    }

    @Transactional
    public AiModelConfig activate(Integer id) {
        AiModelConfig row = findById(id);
        if (row == null) return null;
        assertUsable(row);
        mapper.clearActive();
        mapper.setActiveById(id, nowSqlite());
        AiModelConfig activated = findById(id);
        notifyModelRefresh();
        return activated;
    }

    /** 解析"要使用的模型"。requested 可为空，空则取激活记录。 */
    public AiModelConfig resolve(Integer requestedId) {
        if (requestedId != null) {
            AiModelConfig found = findById(requestedId);
            return found != null && Integer.valueOf(1).equals(found.getEnabled()) ? found : null;
        }
        return getActive();
    }

    public AiModelConfig requireActive() {
        AiModelConfig active = getActive();
        if (active == null) {
            throw new IllegalStateException("未配置激活的 AI 模型，请先在设置中添加并激活一条");
        }
        return active;
    }

    /** 返回指定模型的实际上下文硬上限，线程级选模必须使用该方法。 */
    public int getContextWindowTokens(AiModelConfig config) {
        if (config == null) return 32_768;
        ProviderCapabilities capabilities = capabilitiesForModel(config);
        Integer custom = config.getContextWindowTokens();
        if (custom != null && custom > 0) {
            return Math.min(custom, capabilities.contextWindowTokens());
        }
        return capabilities.contextWindowTokens();
    }

    private static boolean flag(Integer value) {
        return Integer.valueOf(1).equals(value);
    }

    private static int positiveOrDefault(Integer value, int fallback) {
        return value != null && value > 0 ? value : fallback;
    }

    private static int nonNegativeOrDefault(Integer value, int fallback) {
        return value != null && value >= 0 ? value : fallback;
    }

    private static void applyProbeFlag(Map<String, Boolean> values, String key,
                                       java.util.function.Consumer<Integer> setter) {
        if (values == null) return;
        Boolean value = values.get(key);
        if (value != null) setter.accept(value ? 1 : 0);
    }

    // ── 内部工具 ──────────────────────────────────────────────────────────

    private void validateRequired(AiModelConfig row) {
        if (row == null) throw new IllegalArgumentException("配置不能为空");
        if (isBlank(row.getName())) throw new IllegalArgumentException("name 不能为空");
        if (row.getProviderId() == null) throw new IllegalArgumentException("providerId 不能为空");
        if (isBlank(row.getModel())) throw new IllegalArgumentException("model 不能为空");
    }

    private void normalize(AiModelConfig row) {
        row.setName(row.getName().trim());
        if (isBlank(row.getProviderKey())) {
            row.setProviderKey("custom");
        } else {
            row.setProviderKey(row.getProviderKey().trim());
        }
        row.setProviderName(blankToNull(row.getProviderName()));
        if (!isBlank(row.getBaseUrl())) row.setBaseUrl(row.getBaseUrl().trim());
        row.setModel(row.getModel().trim());
        if (row.getEnabled() == null) row.setEnabled(1);
        row.setEnabled(Integer.valueOf(1).equals(row.getEnabled()) ? 1 : 0);
        row.setProtocol(DynamicModelProvider.normalizeProtocol(row.getProtocol()));
        if (isBlank(row.getCompletionsPath())) {
            row.setCompletionsPath(DynamicModelProvider.defaultPathForProtocol(row.getProtocol()));
        } else {
            row.setCompletionsPath(row.getCompletionsPath().trim());
        }
        if (row.getMaxOutputTokens() != null && row.getMaxOutputTokens() <= 0) {
            row.setMaxOutputTokens(null);
        }
        if (row.getContextWindowTokens() != null && row.getContextWindowTokens() <= 0) {
            row.setContextWindowTokens(null);
        }
        row.setReasoningEffort(normalizeReasoningEffort(row.getReasoningEffort()));
        row.setTemperature(normalizeTemperature(row.getTemperature()));
        row.setHeadersJson(blankToNull(row.getHeadersJson()));
        row.setThinkingEnabled(normalizeTriStateFlag(row.getThinkingEnabled()));
    }

    private void applyProvider(AiModelConfig row) {
        AiProvider provider = findProviderById(row.getProviderId());
        if (provider == null) {
            throw new IllegalArgumentException("供应商不存在，providerId: " + row.getProviderId());
        }
        row.setProviderKey(provider.getProviderKey());
        row.setProviderName(provider.getName());
        row.setBaseUrl(provider.getBaseUrl());
        row.setApiKey(provider.getApiKey());
        row.setProtocol(provider.getProtocol());
        row.setCompletionsPath(provider.getCompletionsPath());
        row.setHeadersJson(provider.getHeadersJson());
        if (!Integer.valueOf(1).equals(provider.getEnabled())) {
            row.setEnabled(0);
            row.setIsActive(0);
        }
    }

    private void assertUsable(AiModelConfig row) {
        if (row == null || !Integer.valueOf(1).equals(row.getEnabled())) {
            throw new IllegalArgumentException("模型未启用，不能设为默认模型");
        }
        ProviderCapabilities capabilities = capabilitiesForModel(row);
        if (!capabilities.supportsTextGeneration()) {
            throw new IllegalArgumentException("模型不支持文本生成，不能设为默认聊天模型");
        }
        if (!capabilities.supportsStreaming()) {
            throw new IllegalArgumentException("模型不支持流式输出，不能用于当前聊天通道");
        }
        if (capabilities.maxOutputTokens() <= 0) {
            throw new IllegalArgumentException("模型最大输出长度为 0，不能用于当前聊天通道");
        }
        AiProvider provider = findProviderById(row.getProviderId());
        if (provider == null) {
            throw new IllegalArgumentException("供应商不存在，providerId: " + row.getProviderId());
        }
        if (!Integer.valueOf(1).equals(provider.getEnabled())) {
            throw new IllegalArgumentException("供应商已禁用，不能设为默认模型");
        }
    }

    /**
     * 备用模型只允许指向已启用、可用于当前流式对话的模型；同时拒绝配置环路。
     * 这样运行时的熔断选择始终是确定且安全的。
     */
    private void validateFallback(AiModelConfig row, Integer currentId) {
        Integer fallbackId = row.getFallbackModelId();
        if (fallbackId == null) return;
        if (currentId != null && currentId.equals(fallbackId)) {
            throw new IllegalArgumentException("备用模型不能指向自身");
        }
        AiModelConfig fallback = findById(fallbackId);
        if (fallback == null || !Integer.valueOf(1).equals(fallback.getEnabled())) {
            throw new IllegalArgumentException("备用模型不存在或未启用，fallbackModelId: " + fallbackId);
        }
        ProviderCapabilities capabilities = capabilitiesForModel(fallback);
        if (!capabilities.supportsTextGeneration() || !capabilities.supportsStreaming()) {
            throw new IllegalArgumentException("备用模型必须支持文本生成和流式输出");
        }

        Set<Integer> visited = new HashSet<>();
        if (currentId != null) visited.add(currentId);
        AiModelConfig cursor = fallback;
        while (cursor != null) {
            Integer cursorId = cursor.getId();
            if (cursorId != null && !visited.add(cursorId)) {
                throw new IllegalArgumentException("备用模型链不能形成循环");
            }
            Integer nextId = cursor.getFallbackModelId();
            if (nextId == null) break;
            cursor = findById(nextId);
            if (cursor == null) break;
        }
    }

    private void syncProviderSnapshot(AiProvider provider) {
        AiModelConfig snapshot = new AiModelConfig();
        snapshot.setProviderId(provider.getId());
        snapshot.setProviderKey(provider.getProviderKey());
        snapshot.setProviderName(provider.getName());
        snapshot.setApiKey(provider.getApiKey());
        snapshot.setBaseUrl(provider.getBaseUrl());
        snapshot.setProtocol(provider.getProtocol());
        snapshot.setCompletionsPath(provider.getCompletionsPath());
        snapshot.setHeadersJson(provider.getHeadersJson());
        snapshot.setUpdateTime(nowSqlite());
        withEncryptedModelSecrets(snapshot, () -> mapper.updateProviderSnapshot(snapshot));
    }

    private List<AiProvider> decryptProviders(List<AiProvider> rows) {
        if (rows == null) return List.of();
        List<AiProvider> decrypted = new ArrayList<>(rows.size());
        rows.forEach(row -> decrypted.add(decryptProvider(row)));
        return decrypted;
    }

    private List<AiModelConfig> decryptModels(List<AiModelConfig> rows) {
        if (rows == null) return List.of();
        List<AiModelConfig> decrypted = new ArrayList<>(rows.size());
        rows.forEach(row -> decrypted.add(decryptModel(row)));
        return decrypted;
    }

    private AiProvider decryptProvider(AiProvider row) {
        if (row == null) return null;
        AiProvider decrypted = copyProvider(row);
        decrypted.setApiKey(secretCryptoService.decrypt(row.getApiKey()));
        decrypted.setHeadersJson(secretCryptoService.decrypt(row.getHeadersJson()));
        return decrypted;
    }

    private AiModelConfig decryptModel(AiModelConfig row) {
        if (row == null) return null;
        AiModelConfig decrypted = copyModel(row);
        decrypted.setApiKey(secretCryptoService.decrypt(row.getApiKey()));
        decrypted.setHeadersJson(secretCryptoService.decrypt(row.getHeadersJson()));
        return decrypted;
    }

    /**
     * MyBatis 的事务级一级缓存可能让相同查询重复返回同一个实体实例。读取密文时必须先复制，
     * 否则第一次读取会把缓存实体改成明文，事务内第二次读取就会因数据不再是密文而失败。
     */
    private static AiProvider copyProvider(AiProvider source) {
        AiProvider copy = new AiProvider();
        copy.setId(source.getId());
        copy.setName(source.getName());
        copy.setProviderKey(source.getProviderKey());
        copy.setBaseUrl(source.getBaseUrl());
        copy.setApiKey(source.getApiKey());
        copy.setProtocol(source.getProtocol());
        copy.setCompletionsPath(source.getCompletionsPath());
        copy.setHeadersJson(source.getHeadersJson());
        copy.setEnabled(source.getEnabled());
        copy.setCreateTime(source.getCreateTime());
        copy.setUpdateTime(source.getUpdateTime());
        copy.setRemark(source.getRemark());
        copy.setModels(source.getModels());
        return copy;
    }

    private static AiModelConfig copyModel(AiModelConfig source) {
        AiModelConfig copy = new AiModelConfig();
        copy.setId(source.getId());
        copy.setProviderId(source.getProviderId());
        copy.setName(source.getName());
        copy.setProviderKey(source.getProviderKey());
        copy.setProviderName(source.getProviderName());
        copy.setBaseUrl(source.getBaseUrl());
        copy.setApiKey(source.getApiKey());
        copy.setModel(source.getModel());
        copy.setProtocol(source.getProtocol());
        copy.setCompletionsPath(source.getCompletionsPath());
        copy.setIsActive(source.getIsActive());
        copy.setEnabled(source.getEnabled());
        copy.setFallbackModelId(source.getFallbackModelId());
        copy.setMaxOutputTokens(source.getMaxOutputTokens());
        copy.setThinkingEnabled(source.getThinkingEnabled());
        copy.setReasoningEffort(source.getReasoningEffort());
        copy.setContextWindowTokens(source.getContextWindowTokens());
        copy.setTemperature(source.getTemperature());
        copy.setHeadersJson(source.getHeadersJson());
        copy.setCreateTime(source.getCreateTime());
        copy.setUpdateTime(source.getUpdateTime());
        copy.setRemark(source.getRemark());
        return copy;
    }

    private void withEncryptedProviderSecrets(AiProvider row, Runnable writer) {
        String apiKey = row.getApiKey();
        String headersJson = row.getHeadersJson();
        row.setApiKey(secretCryptoService.encrypt(apiKey));
        row.setHeadersJson(secretCryptoService.encrypt(headersJson));
        try {
            writer.run();
        } finally {
            row.setApiKey(apiKey);
            row.setHeadersJson(headersJson);
        }
    }

    private void withEncryptedModelSecrets(AiModelConfig row, Runnable writer) {
        String apiKey = row.getApiKey();
        String headersJson = row.getHeadersJson();
        row.setApiKey(secretCryptoService.encrypt(apiKey));
        row.setHeadersJson(secretCryptoService.encrypt(headersJson));
        try {
            writer.run();
        } finally {
            row.setApiKey(apiKey);
            row.setHeadersJson(headersJson);
        }
    }

    private void validateProvider(AiProvider row, boolean creating) {
        if (row == null) throw new IllegalArgumentException("供应商不能为空");
        if (creating && isBlank(row.getName())) throw new IllegalArgumentException("供应商名称不能为空");
        if (creating && isBlank(row.getApiKey())) throw new IllegalArgumentException("apiKey 不能为空");
        if (creating && isBlank(row.getBaseUrl())) throw new IllegalArgumentException("baseUrl 不能为空");
    }

    private void normalizeProvider(AiProvider patch, AiProvider existing) {
        AiProvider target = existing != null ? existing : patch;
        if (existing != null) {
            if (!isBlank(patch.getName())) target.setName(patch.getName().trim());
            if (!isBlank(patch.getProviderKey())) target.setProviderKey(patch.getProviderKey().trim());
            if (patch.getApiKey() != null && !patch.getApiKey().isEmpty()) target.setApiKey(patch.getApiKey());
            if (!isBlank(patch.getBaseUrl())) target.setBaseUrl(patch.getBaseUrl().trim());
            if (!isBlank(patch.getProtocol())) target.setProtocol(patch.getProtocol().trim());
            if (!isBlank(patch.getCompletionsPath())) target.setCompletionsPath(patch.getCompletionsPath().trim());
            if (patch.getHeadersJson() != null) target.setHeadersJson(blankToNull(patch.getHeadersJson()));
            if (patch.getEnabled() != null) target.setEnabled(Integer.valueOf(1).equals(patch.getEnabled()) ? 1 : 0);
            if (patch.getRemark() != null) target.setRemark(patch.getRemark());
        } else {
            target.setName(target.getName().trim());
            target.setProviderKey(isBlank(target.getProviderKey()) ? "custom" : target.getProviderKey().trim());
            target.setBaseUrl(target.getBaseUrl().trim());
            target.setHeadersJson(blankToNull(target.getHeadersJson()));
            target.setEnabled(target.getEnabled() == null || Integer.valueOf(1).equals(target.getEnabled()) ? 1 : 0);
        }
        target.setProtocol(DynamicModelProvider.normalizeProtocol(target.getProtocol()));
        target.setCompletionsPath(isBlank(target.getCompletionsPath())
                ? DynamicModelProvider.defaultPathForProtocol(target.getProtocol())
                : target.getCompletionsPath().trim());
        AiModelConfig endpoint = new AiModelConfig();
        endpoint.setBaseUrl(target.getBaseUrl());
        endpoint.setProtocol(target.getProtocol());
        endpoint.setCompletionsPath(target.getCompletionsPath());
        ModelEndpoint.apiRoot(endpoint);
        ModelEndpoint.headers(target.getHeadersJson());
    }

    private static Integer normalizeTriStateFlag(Integer v) {
        if (v == null) return null;
        return v > 0 ? 1 : 0;
    }

    private static String normalizeReasoningEffort(String value) {
        if (value == null || value.isBlank()) return "auto";
        String normalized = value.trim().toLowerCase();
        return switch (normalized) {
            case "auto", "minimal", "low", "medium", "high", "xhigh", "max" -> normalized;
            default -> throw new IllegalArgumentException("reasoningEffort 只支持 auto/minimal/low/medium/high/xhigh/max");
        };
    }

    private static Double normalizeTemperature(Double value) {
        if (value == null) return null;
        if (value < 0 || value > 2) {
            throw new IllegalArgumentException("temperature 必须在 0 到 2 之间");
        }
        return value;
    }

    private static void validateCapability(AiModelCapability row) {
        if (row == null) throw new IllegalArgumentException("模型能力配置不能为空");
        if (isBlank(row.getModelName())) throw new IllegalArgumentException("modelName 不能为空");
        if (row.getContextWindowTokens() == null || row.getContextWindowTokens() <= 0) {
            throw new IllegalArgumentException("contextWindowTokens 必须大于 0");
        }
        if (row.getMaxOutputTokens() == null || row.getMaxOutputTokens() < 0) {
            throw new IllegalArgumentException("maxOutputTokens 不能小于 0");
        }
    }

    private static void normalizeCapability(AiModelCapability row) {
        row.setModelName(normalizeCapabilityKey(row.getModelName()));
        row.setSource("manual");
        row.setSupportsTextGeneration(normalizeFlag(row.getSupportsTextGeneration(), 1));
        row.setSupportsReasoning(normalizeFlag(row.getSupportsReasoning(), 0));
        row.setSupportsStreaming(normalizeFlag(row.getSupportsStreaming(), 1));
        row.setSupportsFunctionCalling(normalizeFlag(row.getSupportsFunctionCalling(), 0));
        row.setSupportsStructuredOutput(normalizeFlag(row.getSupportsStructuredOutput(), 0));
        row.setSupportsWebSearch(normalizeFlag(row.getSupportsWebSearch(), 0));
        row.setSupportsParallelToolCalls(normalizeFlag(row.getSupportsParallelToolCalls(), 1));
        row.setRemark(blankToNull(row.getRemark()));
    }

    private static int normalizeFlag(Integer value, int fallback) {
        if (value == null) return fallback;
        return value > 0 ? 1 : 0;
    }

    private static String normalizeCapabilityKey(String value) {
        return value == null ? null : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizeCapabilityModelName(String providerKey, String modelName) {
        return ProviderCapabilities.normalizeModelName(normalizeCapabilityKey(providerKey), modelName);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private void notifyModelRefresh() {
        events.publishEvent(new AiModelConfigurationChanged());
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String nowSqlite() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
    }
}
