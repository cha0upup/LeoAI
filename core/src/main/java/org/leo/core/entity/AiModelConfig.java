package org.leo.core.entity;

/**
 * AI 模型配置。模型归属于供应商，并保存运行时所需的供应商配置快照。
 *
 * <p>任意时刻只有一条 {@code is_active=1}，主 Agent、子 Agent和摘要任务使用同一条记录。
 * {@link #thinkingEnabled} 三态：1=显式启用 reasoning，0=显式禁用，null=按
 * 模型能力库决定。
 */
public class AiModelConfig {

    private Integer id;
    private Integer providerId;
    private String name;
    private String providerKey;
    private String providerName;
    private String baseUrl;
    private String apiKey;
    private String model;
    private String protocol;
    private String completionsPath;
    private Integer isActive;
    private Integer enabled;
    /** 当前模型熔断时，下一轮请求可自动切换到的备用模型。 */
    private Integer fallbackModelId;
    private Integer maxOutputTokens;
    private Integer thinkingEnabled;
    private String reasoningEffort;
    private Integer contextWindowTokens;
    private Double temperature;
    private String headersJson;
    private String createTime;
    private String updateTime;
    private String remark;

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }

    public Integer getProviderId() { return providerId; }
    public void setProviderId(Integer providerId) { this.providerId = providerId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getProviderKey() { return providerKey; }
    public void setProviderKey(String providerKey) { this.providerKey = providerKey; }

    public String getProviderName() { return providerName; }
    public void setProviderName(String providerName) { this.providerName = providerName; }

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public String getProtocol() { return protocol; }
    public void setProtocol(String protocol) { this.protocol = protocol; }

    public String getCompletionsPath() { return completionsPath; }
    public void setCompletionsPath(String completionsPath) { this.completionsPath = completionsPath; }

    public Integer getIsActive() { return isActive; }
    public void setIsActive(Integer isActive) { this.isActive = isActive; }

    public Integer getEnabled() { return enabled; }
    public void setEnabled(Integer enabled) { this.enabled = enabled; }

    public Integer getFallbackModelId() { return fallbackModelId; }
    public void setFallbackModelId(Integer fallbackModelId) { this.fallbackModelId = fallbackModelId; }

    public Integer getMaxOutputTokens() { return maxOutputTokens; }
    public void setMaxOutputTokens(Integer maxOutputTokens) { this.maxOutputTokens = maxOutputTokens; }

    public Integer getThinkingEnabled() { return thinkingEnabled; }
    public void setThinkingEnabled(Integer thinkingEnabled) { this.thinkingEnabled = thinkingEnabled; }

    public String getReasoningEffort() { return reasoningEffort; }
    public void setReasoningEffort(String reasoningEffort) { this.reasoningEffort = reasoningEffort; }

    public Integer getContextWindowTokens() { return contextWindowTokens; }
    public void setContextWindowTokens(Integer contextWindowTokens) { this.contextWindowTokens = contextWindowTokens; }

    public Double getTemperature() { return temperature; }
    public void setTemperature(Double temperature) { this.temperature = temperature; }

    public String getHeadersJson() { return headersJson; }
    public void setHeadersJson(String headersJson) { this.headersJson = headersJson; }

    public String getCreateTime() { return createTime; }
    public void setCreateTime(String createTime) { this.createTime = createTime; }

    public String getUpdateTime() { return updateTime; }
    public void setUpdateTime(String updateTime) { this.updateTime = updateTime; }

    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
}
