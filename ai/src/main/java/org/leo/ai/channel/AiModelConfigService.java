package org.leo.ai.channel;

import org.leo.core.entity.AiModelCapability;
import org.leo.core.entity.AiModelConfig;
import org.leo.core.entity.AiProvider;
import org.leo.core.entity.AiProviderSummary;
import org.leo.core.entity.ProviderCapabilities;
import org.leo.core.util.json.JsonUtil;
import org.leo.dao.mapper.AiModelCapabilityMapper;
import org.leo.dao.mapper.AiModelConfigMapper;
import org.leo.dao.mapper.AiProviderMapper;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
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

    /** 列表使用同一数据库快照批量读取基础能力与当前连接的探测结果。 */
    @Transactional(readOnly = true)
    public List<CatalogEntry> listCatalog() {
        return loadCatalog(mapper.listAll());
    }

    /** 只载入启用候选；目录和入口可用性共用同一聊天能力判断。 */
    @Transactional(readOnly = true)
    public List<CatalogEntry> listAvailableModels() {
        return loadCatalog(mapper.listEnabled()).stream()
                .filter(entry -> supportsChat(entry.capabilities()))
                .toList();
    }

    private List<CatalogEntry> loadCatalog(List<AiModelConfig> storedModels) {
        if (storedModels == null || storedModels.isEmpty()) return List.of();
        List<AiModelConfig> models = new ArrayList<>(storedModels.size());
        Map<Integer, CatalogConnection> connections = new HashMap<>();
        Map<Integer, String> displayNames = new HashMap<>();
        Set<String> catalogNames = new HashSet<>();
        List<Map<String, Object>> lookups = new ArrayList<>();
        for (AiModelConfig row : storedModels) {
            AiModelConfig config = decryptCatalogModel(row, connections);
            models.add(config);
            displayNames.put(config.getId(), config.getName());
            if (!isBlank(config.getModel())) {
                catalogNames.add(capabilityModelName(config));
                Integer providerId = config.getProviderId();
                if (providerId != null) {
                    lookups.add(Map.of(
                            "configId", config.getId(),
                            "providerId", providerId,
                            "protocol", DynamicModelProvider.resolveProtocol(config),
                            "modelName", config.getModel(),
                            "connectionKey", connections.get(providerId).fingerprint()));
                }
            }
        }

        Map<String, AiModelCapability> baselines = new HashMap<>();
        if (!catalogNames.isEmpty()) {
            for (AiModelCapability row : capabilityMapper.findByModelNames(JsonUtil.toJsonString(catalogNames))) {
                baselines.put(row.getModelName(), row);
            }
        }
        Map<Integer, AiModelCapability> observations = new HashMap<>();
        if (!lookups.isEmpty()) {
            for (AiModelCapabilityMapper.MatchedObservation row
                    : capabilityMapper.findObservations(JsonUtil.toJsonString(lookups))) {
                observations.put(row.configId(), row.capability());
            }
        }

        List<CatalogEntry> entries = new ArrayList<>(models.size());
        for (AiModelConfig config : models) {
            String capabilityName = capabilityModelName(config);
            ProviderCapabilities baseline = isBlank(config.getModel())
                    ? ProviderCapabilities.missing() : catalogBaseline(baselines.get(capabilityName));
            entries.add(new CatalogEntry(config, displayNames.get(config.getFallbackModelId()), capabilityName,
                    mergeCapabilities(baseline, observations.get(config.getId()))));
        }
        return entries;
    }

    private AiModelConfig decryptCatalogModel(AiModelConfig row, Map<Integer, CatalogConnection> connections) {
        Integer providerId = row.getProviderId();
        CatalogConnection connection = connections.get(providerId);
        if (connection != null) {
            AiModelConfig model = copyModel(row);
            model.setApiKey(connection.apiKey());
            model.setHeadersJson(connection.headersJson());
            return model;
        }

        // listAll joins provider fields, so all rows for this ID use the same connection in this read.
        AiModelConfig model = decryptModel(row);
        if (providerId != null) {
            connections.put(providerId, new CatalogConnection(model.getApiKey(), model.getHeadersJson(),
                    ModelConfigurationKey.connection(model)));
        }
        return model;
    }

    private record CatalogConnection(String apiKey, String headersJson, String fingerprint) {
    }

    public record CatalogEntry(AiModelConfig config, String fallbackModelName, String capabilityModelName,
                               ProviderCapabilities capabilities) {
    }

    public List<Integer> listModelIds() {
        return mapper.listIds();
    }

    public boolean modelExists(Integer id) {
        return id != null && mapper.existsById(id);
    }

    public List<AiProviderSummary> listProviderSummaries() {
        return providerMapper.listSummaries();
    }

    public List<AiModelCapability> listModelCapabilities() {
        return capabilityMapper.listAll();
    }

    public List<String> listCapabilityModelNames() {
        return capabilityMapper.listModelNames();
    }

    public ProviderCapabilities capabilitiesForModel(String providerKey, String modelName) {
        if (modelName == null || modelName.isBlank()) {
            return ProviderCapabilities.missing();
        }
        return catalogBaseline(capabilityMapper.findByModelName(normalizeCapabilityModelName(providerKey, modelName)));
    }

    private static ProviderCapabilities catalogBaseline(AiModelCapability row) {
        return row == null ? ProviderCapabilities.conservativeDefault() : capabilityFromRow(row);
    }

    public ProviderCapabilities capabilitiesForModel(AiModelConfig config) {
        if (config == null) return ProviderCapabilities.missing();
        ProviderCapabilities baseline = capabilitiesForModel(config.getProviderKey(), config.getModel());
        return mergeCapabilities(baseline, findObservation(config));
    }

    private static boolean supportsChat(ProviderCapabilities capabilities) {
        return capabilities.supportsTextGeneration()
                && capabilities.supportsStreaming()
                && capabilities.maxOutputTokens() > 0;
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

    private static String capabilityModelName(AiModelConfig config) {
        return normalizeCapabilityModelName(config.getProviderKey(), config.getModel());
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
    public AiProvider createProvider(AiProvider provider) {
        validateProvider(provider, true);
        normalizeProvider(provider, null);
        String now = nowSqlite();
        provider.setCreateTime(now);
        provider.setUpdateTime(now);
        withEncryptedProviderSecrets(provider, () -> providerMapper.insert(provider));

        List<AiModelConfig> models = provider.getModels();
        if (models != null && !models.isEmpty()) {
            insertProviderModels(provider, models);
        }
        AiProvider saved = copyProvider(provider);
        saved.setModels(null);
        return saved;
    }

    private void insertProviderModels(AiProvider provider, List<AiModelConfig> models) {
        boolean enabled = flag(provider.getEnabled());
        int defaultIndex = enabled ? defaultModelIndex(models) : -1;
        for (int index = 0; index < models.size(); index++) {
            AiModelConfig model = models.get(index);
            model.setProviderId(provider.getId());
            model.setEnabled(enabled ? 1 : 0);
            model.setIsActive(index == defaultIndex ? 1 : 0);
            insertModel(model, provider);
        }
        if (defaultIndex >= 0) notifyModelRefresh();
    }

    private int defaultModelIndex(List<AiModelConfig> models) {
        for (int index = 0; index < models.size(); index++) {
            if (flag(models.get(index).getIsActive())) return index;
        }
        return mapper.hasModels() ? -1 : 0;
    }

    @Transactional
    public AiProvider updateProvider(Integer id, AiProvider patch) {
        AiProvider existing = findProviderById(id);
        if (existing == null) return null;
        validateProvider(patch, false);
        normalizeProvider(patch, existing);
        if (Integer.valueOf(1).equals(existing.getEnabled())) {
            for (AiModelConfig stored : mapper.listSettingsByProviderId(id)) {
                AiModelConfig model = copyModel(stored);
                applyProviderConnection(model, existing);
                validateModelParameters(model);
            }
        }
        existing.setUpdateTime(nowSqlite());
        withEncryptedProviderSecrets(existing, () -> providerMapper.update(existing));
        if (!Integer.valueOf(1).equals(existing.getEnabled())) {
            mapper.disableByProviderId(existing.getId(), nowSqlite());
        }
        notifyModelRefresh();
        return existing;
    }

    /** 模型、能力观测及备用引用由现有外键统一清理，无需读取供应商凭据。 */
    @Transactional
    public boolean deleteProvider(Integer id) {
        if (id == null || providerMapper.deleteById(id) <= 0) return false;
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
    public CatalogEntry create(AiModelConfig row) {
        ModelValidation validation = insertModel(row, null);
        if (flag(row.getIsActive())) notifyModelRefresh();
        return savedModel(row.getId(), validation.capabilities(), validation.fallbackModelName());
    }

    @Transactional
    public AiModelCapability createCapability(AiModelCapability row) {
        validateCapability(row);
        normalizeCapability(row);
        String now = nowSqlite();
        row.setCreateTime(now);
        row.setUpdateTime(now);
        if (capabilityMapper.insertIfAbsent(row) == 0) {
            throw new IllegalArgumentException("模型能力已存在: " + row.getModelName());
        }
        notifyModelRefresh();
        return row;
    }

    @Transactional
    public AiModelCapability updateCapability(String modelName, AiModelCapability patch) {
        String normalizedName = normalizeCapabilityKey(modelName);
        AiModelCapability existing = capabilityMapper.findByModelName(normalizedName);
        if (existing == null) return null;
        if (patch == null) {
            throw new IllegalArgumentException("模型能力配置不能为空");
        }
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
        return existing;
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

    private ModelValidation insertModel(AiModelConfig row, AiProvider importedProvider) {
        validateRequired(row);
        normalize(row);
        if (importedProvider == null) {
            applyProvider(row);
        } else {
            applyProviderConnection(row, importedProvider);
        }
        ModelValidation validation = validateModel(row, null);
        String now = nowSqlite();
        row.setCreateTime(now);
        row.setUpdateTime(now);
        if (row.getIsActive() == null) row.setIsActive(0);
        if (importedProvider == null && flag(row.getEnabled()) && !mapper.hasModels()) row.setIsActive(1);
        if (flag(row.getIsActive())) {
            assertUsable(row, validation.capabilities());
            mapper.clearActive();
        }
        mapper.insert(row);
        return validation;
    }

    @Transactional
    public CatalogEntry update(Integer id, AiModelConfig patch) {
        AiModelConfig existing = findById(id);
        if (existing == null) return null;
        boolean wasActive = Integer.valueOf(1).equals(existing.getIsActive());
        if (!isBlank(patch.getName())) existing.setName(patch.getName().trim());
        if (patch.getProviderId() != null) existing.setProviderId(patch.getProviderId());
        if (!isBlank(patch.getModel())) existing.setModel(patch.getModel().trim());
        existing.setMaxOutputTokens(patch.getMaxOutputTokens());
        existing.setThinkingEnabled(patch.getThinkingEnabled());
        if (patch.getReasoningEffort() != null) {
            existing.setReasoningEffort(normalizeReasoningEffort(patch.getReasoningEffort()));
        }
        existing.setContextWindowTokens(patch.getContextWindowTokens());
        existing.setTemperature(patch.getTemperature());
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
        ModelValidation validation = validateModel(existing, id);
        if (activating && !Integer.valueOf(1).equals(existing.getIsActive())) {
            throw new IllegalArgumentException("供应商已禁用，不能设为默认模型");
        }
        if (Integer.valueOf(1).equals(existing.getIsActive())) {
            assertUsable(existing, validation.capabilities());
        }
        if (activating && !wasActive) {
            mapper.clearActive();
        }
        mapper.update(existing);
        boolean isActiveNow = Integer.valueOf(1).equals(existing.getIsActive());
        if (wasActive || isActiveNow) {
            notifyModelRefresh();
        }
        return savedModel(id, validation.capabilities(), validation.fallbackModelName());
    }

    /** 删除按受影响行数判断，不要求解密待删除的凭据。 */
    @Transactional
    public boolean deleteById(Integer id) {
        if (id == null || mapper.deleteById(id) <= 0) return false;
        notifyModelRefresh();
        return true;
    }

    @Transactional
    public CatalogEntry activate(Integer id) {
        AiModelConfig row = findById(id);
        if (row == null) return null;
        applyProvider(row);
        requireEnabledForActivation(row);
        ProviderCapabilities capabilities = validateModelParameters(row);
        assertChatCapabilities(capabilities);
        if (!flag(row.getIsActive())) mapper.clearActive();
        mapper.setActiveById(id, nowSqlite());
        Integer fallbackId = row.getFallbackModelId();
        String fallbackName = fallbackId != null ? mapper.findNameById(fallbackId) : null;
        CatalogEntry activated = savedModel(id, capabilities, fallbackName);
        notifyModelRefresh();
        return activated;
    }

    private CatalogEntry savedModel(Integer id, ProviderCapabilities capabilities, String fallbackName) {
        // 保留数据库生成的 ID 和模型/供应商合并的更新时间，能力与备用名称复用已有结果。
        AiModelConfig saved = findById(id);
        return new CatalogEntry(saved, fallbackName, capabilityModelName(saved), capabilities);
    }

    /** 解析"要使用的模型"。requested 可为空，空则取激活记录。 */
    public AiModelConfig resolve(Integer requestedId) {
        if (requestedId != null) {
            AiModelConfig found = findById(requestedId);
            return found != null && Integer.valueOf(1).equals(found.getEnabled()) ? found : null;
        }
        return getActive();
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

    /** Read-only draft evaluation. Connection settings always come from the saved provider. */
    public ModelRequestPlan.Preview previewRequest(AiModelConfig draft) {
        if (draft == null) throw new IllegalArgumentException("请填写模型参数");
        AiModelConfig candidate = copyModel(draft);
        candidate.setName(isBlank(candidate.getName()) ? "参数预览" : candidate.getName());
        validateRequired(candidate);
        normalize(candidate);
        applyProvider(candidate);
        return ModelRequestPlan.preview(candidate, capabilitiesForModel(candidate));
    }

    private ProviderCapabilities validateModelParameters(AiModelConfig config) {
        ProviderCapabilities capabilities = capabilitiesForModel(config);
        ModelRequestPlan.resolve(config, capabilities, null, false, false);
        return capabilities;
    }

    private ModelValidation validateModel(AiModelConfig config, Integer currentId) {
        ProviderCapabilities capabilities = validateModelParameters(config);
        return new ModelValidation(capabilities, validateFallback(config, currentId));
    }

    private record ModelValidation(ProviderCapabilities capabilities, String fallbackModelName) {
    }

    private void validateRequired(AiModelConfig row) {
        if (row == null) throw new IllegalArgumentException("配置不能为空");
        if (isBlank(row.getName())) throw new IllegalArgumentException("name 不能为空");
        if (row.getProviderId() == null) throw new IllegalArgumentException("providerId 不能为空");
        if (isBlank(row.getModel())) throw new IllegalArgumentException("model 不能为空");
    }

    private void normalize(AiModelConfig row) {
        row.setName(row.getName().trim());
        row.setModel(row.getModel().trim());
        if (row.getEnabled() == null) row.setEnabled(1);
        row.setEnabled(Integer.valueOf(1).equals(row.getEnabled()) ? 1 : 0);
        row.setReasoningEffort(normalizeReasoningEffort(row.getReasoningEffort()));
    }

    private void applyProvider(AiModelConfig row) {
        AiProvider provider = findProviderById(row.getProviderId());
        if (provider == null) {
            throw new IllegalArgumentException("供应商不存在，providerId: " + row.getProviderId());
        }
        applyProviderConnection(row, provider);
    }

    private static void applyProviderConnection(AiModelConfig row, AiProvider provider) {
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

    private static void assertUsable(AiModelConfig row, ProviderCapabilities capabilities) {
        requireEnabledForActivation(row);
        assertChatCapabilities(capabilities);
    }

    private static void requireEnabledForActivation(AiModelConfig row) {
        if (!flag(row.getEnabled())) {
            throw new IllegalArgumentException("模型未启用，不能设为默认模型");
        }
    }

    private static void assertChatCapabilities(ProviderCapabilities capabilities) {
        if (!capabilities.supportsTextGeneration()) {
            throw new IllegalArgumentException("模型不支持文本生成，不能设为默认聊天模型");
        }
        if (!capabilities.supportsStreaming()) {
            throw new IllegalArgumentException("模型不支持流式输出，不能用于当前聊天通道");
        }
        if (capabilities.maxOutputTokens() <= 0) {
            throw new IllegalArgumentException("模型最大输出长度为 0，不能用于当前聊天通道");
        }
    }

    /**
     * 备用模型只允许指向已启用、可用于当前流式对话的模型；同时拒绝配置环路。
     * 这样运行时的熔断选择始终是确定且安全的。
     */
    private String validateFallback(AiModelConfig row, Integer currentId) {
        Integer fallbackId = row.getFallbackModelId();
        if (fallbackId == null) return null;
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

        if (fallback.getFallbackModelId() != null && mapper.hasFallbackCycle(fallbackId, currentId)) {
            throw new IllegalArgumentException("备用模型链不能形成循环");
        }
        return fallback.getName();
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

    private static String normalizeReasoningEffort(String value) {
        String effort = ModelRequestPlan.normalizedEffort(value);
        return effort == null ? "auto" : effort;
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
