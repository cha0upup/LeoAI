package org.leo.web.controller.puppetnode.component;

import org.leo.core.puppet.impl.JavaPuppetNode;
import org.leo.core.util.ApiResponse;
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
@RequestMapping("/puppet-node")
public class ComponentController {

    /**
     * 获取已加载的组件 + 全部可用组件
     */
    @RequestMapping(value = "/get-loaded-components", method = RequestMethod.POST)
    public HashMap<String, Object> getLoadedComponents(@RequestBody HashMap<String, Object> params) {
        try {
            JavaPuppetNode javaPuppetNode = ControllerUtil.getPuppetNode(params);
            Set<String> loaded = javaPuppetNode.getLoadedComponents();
            List<String> loadedList = loaded == null ? new ArrayList<>() : new ArrayList<>(loaded);
            Collections.sort(loadedList);

            // 扫描所有可用 .payload 组件
            List<String> availableList = scanAvailableComponents();

            HashMap<String, Object> data = new HashMap<>();
            data.put("components", loadedList);
            data.put("available", availableList);
            data.put("loadedCount", loadedList.size());
            data.put("availableCount", availableList.size());
            return ApiResponse.success(data);
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
            JavaPuppetNode javaPuppetNode = ControllerUtil.getPuppetNode(params);
            String componentName = getComponentNameFromParams(params);
            HashMap<String, Object> results = (HashMap<String, Object>) javaPuppetNode.loadComponent(componentName);
            return ApiResponse.success(results != null ? results : new HashMap());
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
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
            JavaPuppetNode javaPuppetNode = ControllerUtil.getPuppetNode(params);
            HashMap<String, Object> componentParams = getComponentParams(params);
            String componentName = getComponentName(componentParams);
            HashMap<String, Object> results = (HashMap<String, Object>) javaPuppetNode.loadComponent(componentName);
            return ApiResponse.success(results != null ? results : new HashMap());
        } catch (Exception e) {
            return ApiResponse.error("加载组件失败: " + e.getMessage());
        }
    }

    /**
     * 调用组件
     */
    @RequestMapping(value = "/invoke-component", method = RequestMethod.POST)
    public HashMap<String, Object> invokeComponent(@RequestBody HashMap<String, Object> params) throws Exception {
        JavaPuppetNode javaPuppetNode = null;
        String componentName = null;
        try {
            javaPuppetNode = ControllerUtil.getPuppetNode(params);
            HashMap<String, Object> componentParams = getComponentParams(params);
            componentName = getComponentName(componentParams);
            HashMap<String, Object> results = (HashMap<String, Object>) javaPuppetNode.invokeComponent(componentName, componentParams);
            AuditLogUtil.logSuccess(javaPuppetNode, "COMPONENT_INVOKE", "调用组件", componentName, params,
                    ApiResponse.CODE_SUCCESS, "调用组件成功", AuditLogUtil.getClientIp());
            return ApiResponse.success(results != null ? results : new HashMap());
        } catch (Exception e) {
            AuditLogUtil.logFailure(javaPuppetNode, "COMPONENT_INVOKE", "调用组件", componentName, params,
                    e.getMessage(), AuditLogUtil.getClientIp());
            return ApiResponse.error("调用组件失败: " + e.getMessage());
        }
    }


    /**
     * 获取组件参数
     */
    private HashMap<String, Object> getComponentParams(HashMap<String, Object> params) {
        HashMap<String, Object> componentParams = (HashMap<String, Object>) params.get("params");
        if (componentParams == null) {
            throw new IllegalArgumentException("params中的componentParams不能为空");
        }
        return componentParams;
    }

    /**
     * 获取类名
     */
    private String getComponentName(HashMap<String, Object> componentParams) {
        String className = (String) componentParams.get("classname");
        if (className == null || className.trim().equals("")) {
            throw new IllegalArgumentException("classname不能为空");
        }
        return className;
    }

    /**
     * 从请求参数中解析组件类名（支持 params.classname 或顶层 classname/componentName）
     */
    private String getComponentNameFromParams(HashMap<String, Object> params) {
        HashMap<String, Object> componentParams = (HashMap<String, Object>) params.get("params");
        if (componentParams != null) {
            String className = (String) componentParams.get("classname");
            if (className != null && !className.trim().isEmpty()) {
                return className.trim();
            }
        }
        String topLevel = (String) params.get("classname");
        if (topLevel != null && !topLevel.trim().isEmpty()) {
            return topLevel.trim();
        }
        topLevel = (String) params.get("componentName");
        if (topLevel != null && !topLevel.trim().isEmpty()) {
            return topLevel.trim();
        }
        throw new IllegalArgumentException("请提供组件类名: params.classname、classname 或 componentName");
    }
}
