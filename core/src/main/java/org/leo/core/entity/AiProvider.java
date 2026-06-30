package org.leo.core.entity;

import java.util.List;

public class AiProvider {
    private Integer id;
    private String name;
    private String providerKey;
    private String baseUrl;
    private String apiKey;
    private String protocol;
    private String completionsPath;
    private String headersJson;
    private Integer enabled;
    private String createTime;
    private String updateTime;
    private String remark;
    private List<AiModelConfig> models;

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getProviderKey() { return providerKey; }
    public void setProviderKey(String providerKey) { this.providerKey = providerKey; }

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }

    public String getProtocol() { return protocol; }
    public void setProtocol(String protocol) { this.protocol = protocol; }

    public String getCompletionsPath() { return completionsPath; }
    public void setCompletionsPath(String completionsPath) { this.completionsPath = completionsPath; }

    public String getHeadersJson() { return headersJson; }
    public void setHeadersJson(String headersJson) { this.headersJson = headersJson; }

    public Integer getEnabled() { return enabled; }
    public void setEnabled(Integer enabled) { this.enabled = enabled; }

    public String getCreateTime() { return createTime; }
    public void setCreateTime(String createTime) { this.createTime = createTime; }

    public String getUpdateTime() { return updateTime; }
    public void setUpdateTime(String updateTime) { this.updateTime = updateTime; }

    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }

    public List<AiModelConfig> getModels() { return models; }
    public void setModels(List<AiModelConfig> models) { this.models = models; }
}
