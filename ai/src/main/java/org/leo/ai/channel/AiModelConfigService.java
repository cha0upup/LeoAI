package org.leo.ai.channel;

import org.leo.core.entity.AiModelConfig;
import org.leo.core.entity.AiModelCapability;
import org.leo.core.entity.AiProvider;
import org.leo.core.entity.ProviderCapabilities;
import org.leo.dao.mapper.AiModelCapabilityMapper;
import org.leo.dao.mapper.AiModelConfigMapper;
import org.leo.dao.mapper.AiProviderMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

/**
 * AI 模型配置服务（ccswitch 风格的单表 CRUD）。
 *
 * <p>每条记录都是一组完整的 "name + base_url + api_key + model + 可选高级项"。
 * 同一时刻只允许一条 {@code is_active=1}。激活时会触发 {@link DynamicModelProvider#refresh()}
 * 热切换底层模型。
 */
@Service
public class AiModelConfigService {

    private final AiModelConfigMapper mapper;
    private final AiProviderMapper providerMapper;
    private final AiModelCapabilityMapper capabilityMapper;
    private volatile DynamicModelProvider dynamicModelProvider;

    public AiModelConfigService(AiModelConfigMapper mapper,
                                AiProviderMapper providerMapper,
                                AiModelCapabilityMapper capabilityMapper) {
        this.mapper = mapper;
        this.providerMapper = providerMapper;
        this.capabilityMapper = capabilityMapper;
    }

    /** 由 DynamicModelProvider 在初始化后回调注入，避免循环依赖。 */
    public void setDynamicModelProvider(DynamicModelProvider provider) {
        this.dynamicModelProvider = provider;
    }

    public List<AiModelConfig> listAll() {
        return mapper.listAll();
    }

    public List<AiModelConfig> listEnabled() {
        return mapper.listEnabled();
    }

    public List<AiProvider> listProviders() {
        return providerMapper.listAll();
    }

    public List<AiModelCapability> listModelCapabilities() {
        return capabilityMapper.listAll();
    }

    public ProviderCapabilities capabilitiesForModel(String modelName) {
        if (modelName == null || modelName.isBlank()) {
            return ProviderCapabilities.forModel(modelName);
        }
        AiModelCapability row = capabilityMapper.findByModelName(modelName.trim());
        if (row == null) {
            return ProviderCapabilities.forModel(modelName);
        }
        return new ProviderCapabilities(true,
                "recognized",
                row.getSource() == null || row.getSource().isBlank() ? "system" : row.getSource(),
                positiveOrDefault(row.getContextWindowTokens(), 32_768),
                positiveOrDefault(row.getMaxOutputTokens(), 4_096),
                flag(row.getSupportsTextGeneration()),
                flag(row.getSupportsReasoning()),
                flag(row.getSupportsStreaming()),
                flag(row.getSupportsFunctionCalling()),
                flag(row.getSupportsStructuredOutput()),
                flag(row.getSupportsWebSearch()),
                flag(row.getSupportsParallelToolCalls()));
    }

    public AiProvider findProviderById(Integer id) {
        return id == null ? null : providerMapper.findById(id);
    }

    public AiProvider createProvider(AiProvider row) {
        validateProvider(row, true);
        normalizeProvider(row, null);
        String now = nowSqlite();
        row.setCreateTime(now);
        row.setUpdateTime(now);
        providerMapper.insert(row);
        return providerMapper.findById(row.getId());
    }

    @Transactional
    public AiProvider createProviderWithModels(AiProvider row) {
        AiProvider saved = createProvider(row);
        List<AiModelConfig> models = row.getModels();
        if (models == null || models.isEmpty()) {
            return saved;
        }

        boolean hasExistingModels = mapper.countAll() > 0;
        int requestedDefaultIndex = -1;
        for (int i = 0; i < models.size(); i++) {
            if (Integer.valueOf(1).equals(models.get(i).getIsActive())) {
                requestedDefaultIndex = i;
                break;
            }
        }
        int defaultIndex = requestedDefaultIndex >= 0 ? requestedDefaultIndex : (hasExistingModels ? -1 : 0);
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
        return providerMapper.findById(saved.getId());
    }

    public AiProvider updateProvider(Integer id, AiProvider patch) {
        AiProvider existing = findProviderById(id);
        if (existing == null) return null;
        validateProvider(patch, false);
        normalizeProvider(patch, existing);
        existing.setUpdateTime(nowSqlite());
        providerMapper.update(existing);
        syncProviderSnapshot(existing);
        if (!Integer.valueOf(1).equals(existing.getEnabled())) {
            mapper.disableByProviderId(existing.getId(), nowSqlite());
        }
        notifyModelRefresh();
        return providerMapper.findById(id);
    }

    @Transactional
    public boolean deleteProvider(Integer id) {
        AiProvider existing = findProviderById(id);
        if (existing == null) return false;
        mapper.deleteByProviderId(id);
        providerMapper.deleteById(id);
        notifyModelRefresh();
        return true;
    }

    public AiModelConfig findById(Integer id) {
        return id == null ? null : mapper.findById(id);
    }

    public AiModelConfig getActive() {
        return mapper.findActive();
    }

    public AiModelConfig create(AiModelConfig row) {
        return createModel(row, true, true);
    }

    private AiModelConfig createModel(AiModelConfig row, boolean autoActivateFirst, boolean notify) {
        validateRequired(row);
        normalize(row);
        applyProvider(row);
        String now = nowSqlite();
        row.setCreateTime(now);
        row.setUpdateTime(now);
        if (row.getIsActive() == null) {
            row.setIsActive(0);
        }
        if (row.getEnabled() == null) {
            row.setEnabled(1);
        }
        if (autoActivateFirst && mapper.countAll() == 0) {
            row.setIsActive(1);
            row.setEnabled(1);
        } else if (Integer.valueOf(1).equals(row.getIsActive())) {
            assertUsable(row);
            mapper.clearActive();
        }
        mapper.insert(row);
        if (notify && Integer.valueOf(1).equals(row.getIsActive())) {
            notifyModelRefresh();
        }
        return mapper.findById(row.getId());
    }

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
        if (!isBlank(patch.getCompletionsPath())) {
            existing.setCompletionsPath(patch.getCompletionsPath().trim());
        }
        if (patch.getMaxOutputTokens() != null) {
            existing.setMaxOutputTokens(patch.getMaxOutputTokens() > 0 ? patch.getMaxOutputTokens() : null);
        }
        if (patch.getThinkingEnabled() != null) {
            existing.setThinkingEnabled(normalizeTriStateFlag(patch.getThinkingEnabled()));
        }
        if (patch.getReasoningEffort() != null) {
            existing.setReasoningEffort(normalizeReasoningEffort(patch.getReasoningEffort()));
        }
        if (patch.getContextWindowTokens() != null) {
            existing.setContextWindowTokens(patch.getContextWindowTokens() > 0
                    ? patch.getContextWindowTokens() : null);
        }
        if (patch.getTemperature() != null) {
            existing.setTemperature(normalizeTemperature(patch.getTemperature()));
        }
        if (patch.getHeadersJson() != null) existing.setHeadersJson(blankToNull(patch.getHeadersJson()));
        if (patch.getEnabled() != null) {
            existing.setEnabled(Integer.valueOf(1).equals(patch.getEnabled()) ? 1 : 0);
        }
        if (patch.getRemark() != null) existing.setRemark(patch.getRemark());
        if (patch.getIsActive() != null) {
            if (Integer.valueOf(1).equals(patch.getIsActive())) {
                mapper.clearActive();
                existing.setIsActive(1);
                existing.setEnabled(1);
            } else {
                existing.setIsActive(0);
            }
        }
        if (Integer.valueOf(1).equals(existing.getIsActive())
                && !Integer.valueOf(1).equals(existing.getEnabled())) {
            throw new IllegalArgumentException("默认模型必须保持启用，请先设置新的默认模型");
        }
        existing.setUpdateTime(nowSqlite());
        normalize(existing);
        applyProvider(existing);
        mapper.update(existing);
        boolean isActiveNow = Integer.valueOf(1).equals(existing.getIsActive());
        if (wasActive || isActiveNow) {
            notifyModelRefresh();
        }
        return mapper.findById(id);
    }

    public boolean deleteById(Integer id) {
        AiModelConfig row = findById(id);
        if (row == null) return false;
        mapper.deleteById(id);
        return true;
    }

    public AiModelConfig activate(Integer id) {
        AiModelConfig row = findById(id);
        if (row == null) return null;
        assertUsable(row);
        mapper.clearActive();
        mapper.setActiveById(id, nowSqlite());
        AiModelConfig activated = mapper.findById(id);
        notifyModelRefresh();
        return activated;
    }

    /** 解析"要使用的模型"。requested 可为空，空则取激活记录。 */
    public AiModelConfig resolve(Integer requestedId) {
        if (requestedId != null) {
            AiModelConfig found = mapper.findById(requestedId);
            if (found != null && Integer.valueOf(1).equals(found.getEnabled())) return found;
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

    /**
     * 获取当前激活模型的上下文窗口 token 数。
     * 优先从数据库配置 {@code contextWindowTokens} 字段读取，
     * 为空时根据模型名推断默认值。
     */
    public int getActiveContextWindowTokens() {
        AiModelConfig active = getActive();
        if (active == null) {
            return capabilitiesForModel("gpt-4o").contextWindowTokens();
        }
        ProviderCapabilities capabilities = capabilitiesForModel(active.getModel());
        Integer custom = active.getContextWindowTokens();
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

    // ── 内部工具 ──────────────────────────────────────────────────────────

    private void validateRequired(AiModelConfig row) {
        if (row == null) throw new IllegalArgumentException("配置不能为空");
        if (isBlank(row.getName())) throw new IllegalArgumentException("name 不能为空");
        if (row.getProviderId() == null && isBlank(row.getApiKey())) throw new IllegalArgumentException("apiKey 不能为空");
        if (row.getProviderId() == null && isBlank(row.getBaseUrl())) throw new IllegalArgumentException("baseUrl 不能为空");
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
        if (isBlank(row.getCompletionsPath())) {
            row.setCompletionsPath("/v1/responses");
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
        if (row.getProviderId() == null) return;
        AiProvider provider = providerMapper.findById(row.getProviderId());
        if (provider == null) {
            throw new IllegalArgumentException("供应商不存在，providerId: " + row.getProviderId());
        }
        row.setProviderKey(provider.getProviderKey());
        row.setProviderName(provider.getName());
        row.setBaseUrl(provider.getBaseUrl());
        row.setApiKey(provider.getApiKey());
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
        if (row.getProviderId() == null) return;
        AiProvider provider = providerMapper.findById(row.getProviderId());
        if (provider == null) {
            throw new IllegalArgumentException("供应商不存在，providerId: " + row.getProviderId());
        }
        if (!Integer.valueOf(1).equals(provider.getEnabled())) {
            throw new IllegalArgumentException("供应商已禁用，不能设为默认模型");
        }
    }

    private void syncProviderSnapshot(AiProvider provider) {
        AiModelConfig snapshot = new AiModelConfig();
        snapshot.setProviderId(provider.getId());
        snapshot.setProviderKey(provider.getProviderKey());
        snapshot.setProviderName(provider.getName());
        snapshot.setApiKey(provider.getApiKey());
        snapshot.setBaseUrl(provider.getBaseUrl());
        snapshot.setCompletionsPath(provider.getCompletionsPath());
        snapshot.setHeadersJson(provider.getHeadersJson());
        snapshot.setUpdateTime(nowSqlite());
        mapper.updateProviderSnapshot(snapshot);
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
            if (!isBlank(patch.getCompletionsPath())) target.setCompletionsPath(patch.getCompletionsPath().trim());
            if (patch.getHeadersJson() != null) target.setHeadersJson(blankToNull(patch.getHeadersJson()));
            if (patch.getEnabled() != null) target.setEnabled(Integer.valueOf(1).equals(patch.getEnabled()) ? 1 : 0);
            if (patch.getRemark() != null) target.setRemark(patch.getRemark());
        } else {
            target.setName(target.getName().trim());
            target.setProviderKey(isBlank(target.getProviderKey()) ? "custom" : target.getProviderKey().trim());
            target.setBaseUrl(target.getBaseUrl().trim());
            target.setCompletionsPath(isBlank(target.getCompletionsPath())
                    ? "/v1/responses" : target.getCompletionsPath().trim());
            target.setHeadersJson(blankToNull(target.getHeadersJson()));
            target.setEnabled(target.getEnabled() == null || Integer.valueOf(1).equals(target.getEnabled()) ? 1 : 0);
        }
    }

    private static Integer normalizeTriStateFlag(Integer v) {
        if (v == null) return null;
        return v > 0 ? 1 : 0;
    }

    private static String normalizeReasoningEffort(String value) {
        if (value == null || value.isBlank()) return "auto";
        String normalized = value.trim().toLowerCase();
        return switch (normalized) {
            case "auto", "low", "medium", "high", "xhigh" -> normalized;
            default -> throw new IllegalArgumentException("reasoningEffort 只支持 auto/low/medium/high/xhigh");
        };
    }

    private static Double normalizeTemperature(Double value) {
        if (value == null) return null;
        if (value < 0 || value > 2) {
            throw new IllegalArgumentException("temperature 必须在 0 到 2 之间");
        }
        return value;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private void notifyModelRefresh() {
        DynamicModelProvider provider = this.dynamicModelProvider;
        if (provider != null) provider.refresh();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String nowSqlite() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
    }
}
