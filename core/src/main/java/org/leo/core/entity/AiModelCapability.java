package org.leo.core.entity;

public class AiModelCapability {

    private String modelName;
    private String providerKey;
    private String source;
    private Integer contextWindowTokens;
    private Integer maxOutputTokens;
    private Integer supportsTextGeneration;
    private Integer supportsReasoning;
    private Integer supportsStreaming;
    private Integer supportsFunctionCalling;
    private Integer supportsStructuredOutput;
    private Integer supportsWebSearch;
    private Integer supportsParallelToolCalls;
    private String createTime;
    private String updateTime;
    private String remark;

    public String getModelName() { return modelName; }
    public void setModelName(String modelName) { this.modelName = modelName; }

    public String getProviderKey() { return providerKey; }
    public void setProviderKey(String providerKey) { this.providerKey = providerKey; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public Integer getContextWindowTokens() { return contextWindowTokens; }
    public void setContextWindowTokens(Integer contextWindowTokens) { this.contextWindowTokens = contextWindowTokens; }

    public Integer getMaxOutputTokens() { return maxOutputTokens; }
    public void setMaxOutputTokens(Integer maxOutputTokens) { this.maxOutputTokens = maxOutputTokens; }

    public Integer getSupportsTextGeneration() { return supportsTextGeneration; }
    public void setSupportsTextGeneration(Integer supportsTextGeneration) { this.supportsTextGeneration = supportsTextGeneration; }

    public Integer getSupportsReasoning() { return supportsReasoning; }
    public void setSupportsReasoning(Integer supportsReasoning) { this.supportsReasoning = supportsReasoning; }

    public Integer getSupportsStreaming() { return supportsStreaming; }
    public void setSupportsStreaming(Integer supportsStreaming) { this.supportsStreaming = supportsStreaming; }

    public Integer getSupportsFunctionCalling() { return supportsFunctionCalling; }
    public void setSupportsFunctionCalling(Integer supportsFunctionCalling) { this.supportsFunctionCalling = supportsFunctionCalling; }

    public Integer getSupportsStructuredOutput() { return supportsStructuredOutput; }
    public void setSupportsStructuredOutput(Integer supportsStructuredOutput) { this.supportsStructuredOutput = supportsStructuredOutput; }

    public Integer getSupportsWebSearch() { return supportsWebSearch; }
    public void setSupportsWebSearch(Integer supportsWebSearch) { this.supportsWebSearch = supportsWebSearch; }

    public Integer getSupportsParallelToolCalls() { return supportsParallelToolCalls; }
    public void setSupportsParallelToolCalls(Integer supportsParallelToolCalls) { this.supportsParallelToolCalls = supportsParallelToolCalls; }

    public String getCreateTime() { return createTime; }
    public void setCreateTime(String createTime) { this.createTime = createTime; }

    public String getUpdateTime() { return updateTime; }
    public void setUpdateTime(String updateTime) { this.updateTime = updateTime; }

    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
}
