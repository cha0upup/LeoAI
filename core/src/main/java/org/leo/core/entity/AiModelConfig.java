package org.leo.core.entity;

/**
 * AI 模型配置（ccswitch 风格）：一行 = 一组可切换的"模型 + 凭证"。
 *
 * <p>每条记录都是自包含的，没有 provider/profile 两层分裂。
 * 任意时刻只有一条 {@code is_active=1}，主 Agent / 子 Agent / summary 全部使用同一条记录。
 *
 * <p>{@link #thinkingEnabled} 三态：1=显式启用 reasoning，0=显式禁用，null=按
 * {@link ModelDefaults} 推断。
 */
public class AiModelConfig {

    private Integer id;
    private String name;
    private String baseUrl;
    private String apiKey;
    private String model;
    private String completionsPath;
    private Integer isActive;
    private Integer maxOutputTokens;
    private Integer thinkingEnabled;
    private Integer contextWindowTokens;
    private String createTime;
    private String updateTime;
    private String remark;

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public String getCompletionsPath() { return completionsPath; }
    public void setCompletionsPath(String completionsPath) { this.completionsPath = completionsPath; }

    public Integer getIsActive() { return isActive; }
    public void setIsActive(Integer isActive) { this.isActive = isActive; }

    public Integer getMaxOutputTokens() { return maxOutputTokens; }
    public void setMaxOutputTokens(Integer maxOutputTokens) { this.maxOutputTokens = maxOutputTokens; }

    public Integer getThinkingEnabled() { return thinkingEnabled; }
    public void setThinkingEnabled(Integer thinkingEnabled) { this.thinkingEnabled = thinkingEnabled; }

    public Integer getContextWindowTokens() { return contextWindowTokens; }
    public void setContextWindowTokens(Integer contextWindowTokens) { this.contextWindowTokens = contextWindowTokens; }

    public String getCreateTime() { return createTime; }
    public void setCreateTime(String createTime) { this.createTime = createTime; }

    public String getUpdateTime() { return updateTime; }
    public void setUpdateTime(String updateTime) { this.updateTime = updateTime; }

    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
}
