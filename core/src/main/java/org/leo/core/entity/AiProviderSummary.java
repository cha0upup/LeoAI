package org.leo.core.entity;

/** 管理目录展示的供应商信息，不携带凭据或请求头内容。 */
public record AiProviderSummary(
        Integer id, String name, String providerKey, String baseUrl, String protocol,
        String completionsPath, Integer enabled, String createTime, String updateTime,
        String remark, boolean headersConfigured, boolean apiKeyConfigured) {
}
