package org.leo.web.controller.agentnode.component;

import org.leo.core.agent.AbstractAgentNode;
import org.leo.core.agent.capability.ComponentManageCapable;
import org.leo.core.util.ApiResponse;
import org.leo.web.exception.ApiException;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.leo.web.util.AuditLogUtil;
import org.leo.web.util.ControllerUtil;

import java.util.*;

@RestController
@RequestMapping("/agent-node")
public class ComponentController {

    /**
     * 获取已加载的组件 + 全部可用组件
     */
    @RequestMapping(value = "/get-loaded-components", method = RequestMethod.POST)
    public HashMap<String, Object> getLoadedComponents(@RequestBody HashMap<String, Object> params) {
        try {
            ComponentManageCapable componentNode = ControllerUtil.requireCapability(params, ComponentManageCapable.class);
            Set<String> loaded = componentNode.getLoadedComponents();
            List<String> loadedList = loaded == null ? new ArrayList<>() : new ArrayList<>(loaded);
            Collections.sort(loadedList);

            // Runtime 优先提供动态制品列表；未提供时扫描 classpath 中的 .payload。
            Set<String> runtimeAvailable = componentNode.getAvailableComponents();
            List<String> availableList = runtimeAvailable == null || runtimeAvailable.isEmpty()
                    ? scanAvailableComponents() : new ArrayList<>(runtimeAvailable);
            Collections.sort(availableList);

            HashMap<String, Object> data = new HashMap<>();
            data.put("components", loadedList);
            data.put("available", availableList);
            data.put("loadedCount", loadedList.size());
            data.put("availableCount", availableList.size());
            return ApiResponse.success(data);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            return ApiResponse.error("获取已加载组件失败: " + e.getMessage());
        }
    }

    /**
     * 扫描 classpath 中所有可用的 .payload 组件
     */
    private List<String> scanAvailableComponents() {
        List<String> result = new ArrayList<>();
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources("classpath:component/*.payload");
            for (Resource resource : resources) {
                String filename = resource.getFilename();
                if (filename != null && filename.endsWith(".payload")) {
                    result.add(filename.replace(".payload", ""));
                }
            }
            Collections.sort(result);
        } catch (Exception ignored) {
        }
        return result;
    }

    /**
     * 重新加载指定 Component（先加载或覆盖当前会话中该组件的加载状态）
     */
    @RequestMapping(value = "/reload-component", method = RequestMethod.POST)
    public HashMap<String, Object> reloadComponent(@RequestBody HashMap<String, Object> params) {
        try {
            ComponentManageCapable componentNode = ControllerUtil.requireCapability(params, ComponentManageCapable.class);
            String componentName = getComponentNameFromParams(params);
            Map<String, Object> results = componentNode.reloadComponent(componentName);
            return ApiResponse.success(results != null ? results : Collections.emptyMap());
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            return ApiResponse.error("重新加载组件失败: " + e.getMessage());
        }
    }

    /**
     * 加载组件
     */
    @RequestMapping(value = "/load-component", method = RequestMethod.POST)
    public HashMap<String, Object> loadComponent(@RequestBody HashMap<String, Object> params) {
        try {
            ComponentManageCapable componentNode = ControllerUtil.requireCapability(params, ComponentManageCapable.class);
            HashMap<String, Object> componentParams = getComponentParams(params);
            String componentName = getComponentName(componentParams);
            Map<String, Object> results = componentNode.loadComponent(componentName);
            return ApiResponse.success(results != null ? results : Collections.emptyMap());
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            return ApiResponse.error("加载组件失败: " + e.getMessage());
        }
    }

    /**
     * 调用组件
     */
    @RequestMapping(value = "/invoke-component", method = RequestMethod.POST)
    public HashMap<String, Object> invokeComponent(@RequestBody HashMap<String, Object> params) throws Exception {
        AbstractAgentNode javaAgentNode = null;
        String componentName = null;
        try {
            javaAgentNode = ControllerUtil.getAbstractAgentNode(params);
            ComponentManageCapable componentNode = ControllerUtil.requireCapability(params, ComponentManageCapable.class);
            HashMap<String, Object> componentParams = getComponentParams(params);
            componentName = getComponentName(componentParams);
            Map<String, Object> results = componentNode.invokeComponent(componentName, componentParams);
            AuditLogUtil.logSuccess(javaAgentNode, "COMPONENT_INVOKE", "调用组件", componentName, params,
                    ApiResponse.CODE_SUCCESS, "调用组件成功", AuditLogUtil.getClientIp());
            return ApiResponse.success(results != null ? results : Collections.emptyMap());
        } catch (IllegalArgumentException e) {
            AuditLogUtil.logFailure(javaAgentNode, "COMPONENT_INVOKE", "调用组件", componentName, params,
                    e.getMessage(), AuditLogUtil.getClientIp());
            return ApiResponse.badRequest(e.getMessage());
        } catch (ApiException e) {
            AuditLogUtil.logFailure(javaAgentNode, "COMPONENT_INVOKE", "调用组件", componentName, params,
                    e.getMessage(), AuditLogUtil.getClientIp());
            throw e;
        } catch (Exception e) {
            AuditLogUtil.logFailure(javaAgentNode, "COMPONENT_INVOKE", "调用组件", componentName, params,
                    e.getMessage(), AuditLogUtil.getClientIp());
            return ApiResponse.error("调用组件失败: " + e.getMessage());
        }
    }


    /**
     * 获取组件参数
     */
    private HashMap<String, Object> getComponentParams(HashMap<String, Object> params) {
        if (params == null || !(params.get("params") instanceof Map<?, ?> componentParams)) {
            throw new IllegalArgumentException("params中的componentParams不能为空");
        }
        return copyStringKeyMap(componentParams);
    }

    /**
     * 获取类名
     */
    private String getComponentName(HashMap<String, Object> componentParams) {
        Object classNameValue = componentParams.get("classname");
        if (!(classNameValue instanceof String className) || className.isBlank()) {
            throw new IllegalArgumentException("classname不能为空");
        }
        return className.trim();
    }

    /**
     * 从请求参数中解析组件类名（支持 params.classname 或顶层 classname/componentName）
     */
    private String getComponentNameFromParams(HashMap<String, Object> params) {
        if (params != null && params.get("params") instanceof Map<?, ?> componentParams) {
            Object classNameValue = componentParams.get("classname");
            if (classNameValue instanceof String className && !className.isBlank()) {
                return className.trim();
            }
        }
        Object topLevelValue = params == null ? null : params.get("classname");
        if (topLevelValue instanceof String topLevel && !topLevel.isBlank()) {
            return topLevel.trim();
        }
        topLevelValue = params == null ? null : params.get("componentName");
        if (topLevelValue instanceof String topLevel && !topLevel.isBlank()) {
            return topLevel.trim();
        }
        throw new IllegalArgumentException("请提供组件类名: params.classname、classname 或 componentName");
    }

    private HashMap<String, Object> copyStringKeyMap(Map<?, ?> source) {
        HashMap<String, Object> copy = new HashMap<>();
        source.forEach((key, value) -> {
            if (key instanceof String stringKey) {
                copy.put(stringKey, value);
            }
        });
        return copy;
    }
}
