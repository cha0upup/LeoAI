package org.leo.core.puppet.service;

import org.leo.core.net.Communication;
import org.leo.core.net.layer.RequestLayer;
import org.leo.core.net.layer.ResponseLayer;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CatalinaManageService extends ComponentService {

    public CatalinaManageService(Communication communication, List<RequestLayer> requestLayers, List<ResponseLayer> responseLayers) {
        super(communication, requestLayers, responseLayers);
    }

    public Map<String, Object> getCatalinaInfo(String catalinaName, String webFramework) throws Exception {
        Map<String, Object> results = invokeComponent(resolveCatalinaComponent(catalinaName), createMethodParams("getCatalinaInfo"));
        if (results == null) {
            results = new HashMap<>();
        }
        if (isSpringFramework(webFramework)) {
            Map<String, Object> frameworkResults = invokeComponent(
                    "SpringFrameworkManageComponent",
                    createMethodParams("getFrameworkInfo")
            );
            if (frameworkResults != null && Integer.valueOf(200).equals(frameworkResults.get("code"))) {
                Object frameworkInfo = frameworkResults.get("frameworkInfo");
                if (frameworkInfo instanceof Map) {
                    Map<String, Object> frameworkInfoMap = (Map<String, Object>) frameworkInfo;
                    HashMap<String, Object> mergedFrameworkInfo = new HashMap<>();
                    mergedFrameworkInfo.putAll(frameworkInfoMap);
                    mergedFrameworkInfo.put("webFramework", webFramework);
                    results.put("frameworkInfo", mergedFrameworkInfo);
                }
            }
        }
        return results;
    }

    public Map<String, Object> unloadFilter(String catalinaName, String contextName, String filterName) throws Exception {
        HashMap<String, Object> params = createMethodParams("unLoadFilter");
        params.put("contextName", contextName);
        params.put("filterName", filterName);
        return invokeComponent(resolveCatalinaComponent(catalinaName), params);
    }

    public Map<String, Object> unloadServlet(String catalinaName, String contextName, String servletPattern) throws Exception {
        HashMap<String, Object> params = createMethodParams("unLoadServlet");
        params.put("contextName", contextName);
        params.put("servletPattern", servletPattern);
        return invokeComponent(resolveCatalinaComponent(catalinaName), params);
    }

    public Map<String, Object> unloadValve(String catalinaName, String valveId) throws Exception {
        if (!"Tomcat".equals(catalinaName)) {
            throw new IllegalArgumentException("当前中间件不支持卸载Valve功能，仅支持Tomcat: " + catalinaName);
        }
        HashMap<String, Object> params = createMethodParams("unLoadValve");
        params.put("valveId", valveId);
        return invokeComponent("TomcatCatalinaManageComponent", params);
    }

    public Map<String, Object> unloadListener(String catalinaName, String listenerId) throws Exception {
        if (!"Tomcat".equals(catalinaName)) {
            throw new IllegalArgumentException("当前中间件不支持卸载Listener功能，仅支持Tomcat: " + catalinaName);
        }
        HashMap<String, Object> params = createMethodParams("unLoadListener");
        params.put("listenerId", listenerId);
        return invokeComponent("TomcatCatalinaManageComponent", params);
    }

    public Map<String, Object> unloadController(String webFramework, String mappingInfo) throws Exception {
        if (!isSpringFramework(webFramework)) {
            throw new IllegalArgumentException("当前Web框架不支持卸载Controller功能，仅支持Spring框架: " + webFramework);
        }
        HashMap<String, Object> params = createMethodParams("unLoadController");
        params.put("mappingInfo", mappingInfo);
        return invokeComponent("SpringFrameworkManageComponent", params);
    }

    public Map<String, Object> unloadInterceptor(String webFramework, String interceptorId) throws Exception {
        if (!isSpringFramework(webFramework)) {
            throw new IllegalArgumentException("当前Web框架不支持卸载Interceptor功能，仅支持Spring框架: " + webFramework);
        }
        HashMap<String, Object> params = createMethodParams("unLoadInterceptor");
        params.put("interceptorId", interceptorId);
        return invokeComponent("SpringFrameworkManageComponent", params);
    }

    private HashMap<String, Object> createMethodParams(String methodName) {
        HashMap<String, Object> params = new HashMap<>();
        params.put("methodName", methodName);
        return params;
    }

    private String resolveCatalinaComponent(String catalinaName) {
        if ("Tomcat".equals(catalinaName)) {
            return "TomcatCatalinaManageComponent";
        }
        if ("WebLogic".equals(catalinaName)) {
            return "WeblogicCatalinaManageComponent";
        }
        throw new IllegalArgumentException("不支持的中间件类型: " + catalinaName);
    }

    private boolean isSpringFramework(String webFramework) {
        return webFramework != null && webFramework.contains("Spring");
    }
}
