package org.leo.web.controller.puppetnode.catalina;


import org.leo.core.puppet.impl.JavaPuppetNode;
import org.leo.core.session.PuppetNodeSession;
import org.leo.core.util.ApiResponse;
import org.leo.core.util.session.PuppetNodeSessionWorkDirUtil;
import org.leo.web.util.ControllerUtil;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/puppet-node/catalina-manage")
public class CatalinaManageController {

    @RequestMapping(value = "/get-all", method = RequestMethod.POST)
    public HashMap<String, Object> getCatalinaInfo(@RequestBody HashMap<String, Object> params) {

        String sessionId = (String) params.get("sessionId");

        try {
            String catalinaName = getCatalinaName(sessionId);
            String webFramework = getWebFrameworkName(sessionId);
            JavaPuppetNode javaPuppetNode = ControllerUtil.getPuppetNode(params);
            Map<String, Object> results = javaPuppetNode.getCatalinaInfo(catalinaName, webFramework);
            if (sessionId != null && !sessionId.isBlank() && results != null) {
                try {
                    PuppetNodeSessionWorkDirUtil.saveCatalinaInfo(sessionId, results);
                } catch (Exception ex) {
                    // 持久化失败不影响接口返回，仅忽略
                }
            }
            return ApiResponse.success(results);
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        } catch (Exception e) {
            return ApiResponse.error("获取容器管理信息失败: " + e.getMessage());
        }
    }


    @SuppressWarnings("unchecked")
    private Map<String, Object> resolveBasicInfo(String sessionId) {
        PuppetNodeSession session = ControllerUtil.getPuppetNodeSession(sessionId);
        Map<String, Object> basicInfo = session != null ? session.getBasicInfo(session.getCurrentHostId()) : null;
        if (basicInfo == null) {
            basicInfo = PuppetNodeSessionWorkDirUtil.loadBasicInfo(sessionId);
        }
        if (basicInfo == null) {
            throw new IllegalArgumentException("会话中不存在基础信息: " + sessionId);
        }
        return basicInfo;
    }

    @SuppressWarnings("unchecked")
    private String getCatalinaName(String sessionId) {
        Map<String, Object> basicInfo = resolveBasicInfo(sessionId);
        Map<String, Object> middlewareInfo = (Map<String, Object>) basicInfo.get("MiddlewareInfo");
        if (middlewareInfo == null) {
            throw new IllegalArgumentException("会话中不存在基础信息: " + sessionId);
        }
        return (String) middlewareInfo.get("MiddlewareType");
    }

    private String getWebFrameworkName(String sessionId) {
        return (String) resolveBasicInfo(sessionId).get("WebFramework");
    }

    @RequestMapping(value = "/unload-filter", method = RequestMethod.POST)
    public HashMap<String, Object> unloadFilter(@RequestBody HashMap<String, Object> params) {
        String sessionId = (String) params.get("sessionId");

        try {
            String catalinaName = getCatalinaName(sessionId);
            JavaPuppetNode javaPuppetNode = ControllerUtil.getPuppetNode(params);
            String contextName = ControllerUtil.getRequiredStringParam(params, "contextName");
            String filterName = ControllerUtil.getRequiredStringParam(params, "filterName");
            Map<String, Object> results = javaPuppetNode.unloadCatalinaFilter(catalinaName, contextName, filterName);
            return ApiResponse.success(results);
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        } catch (Exception e) {
            return ApiResponse.error("卸载过滤器失败: " + e.getMessage());
        }
    }

    @RequestMapping(value = "/unload-servlet", method = RequestMethod.POST)
    public HashMap<String, Object> unloadServlet(@RequestBody HashMap<String, Object> params) {
        String sessionId = (String) params.get("sessionId");

        try {
            String catalinaName = getCatalinaName(sessionId);
            JavaPuppetNode javaPuppetNode = ControllerUtil.getPuppetNode(params);
            String contextName = ControllerUtil.getRequiredStringParam(params, "contextName");
            String servletPattern = ControllerUtil.getRequiredStringParam(params, "servletPattern");
            Map<String, Object> results = javaPuppetNode.unloadCatalinaServlet(catalinaName, contextName, servletPattern);
            return ApiResponse.success(results);
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        } catch (Exception e) {
            return ApiResponse.error("卸载Servlet失败: " + e.getMessage());
        }
    }

    @RequestMapping(value = "/unload-valve", method = RequestMethod.POST)
    public HashMap<String, Object> unloadValve(@RequestBody HashMap<String, Object> params) {
        String sessionId = (String) params.get("sessionId");

        try {
            String catalinaName = getCatalinaName(sessionId);
            JavaPuppetNode javaPuppetNode = ControllerUtil.getPuppetNode(params);
            String valveId = ControllerUtil.getRequiredStringParam(params, "valveId");
            Map<String, Object> results = javaPuppetNode.unloadCatalinaValve(catalinaName, valveId);
            return ApiResponse.success(results);
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        } catch (Exception e) {
            return ApiResponse.error("卸载Valve失败: " + e.getMessage());
        }
    }

    @RequestMapping(value = "/unload-listener", method = RequestMethod.POST)
    public HashMap<String, Object> unloadListener(@RequestBody HashMap<String, Object> params) {
        String sessionId = (String) params.get("sessionId");

        try {
            String catalinaName = getCatalinaName(sessionId);
            JavaPuppetNode javaPuppetNode = ControllerUtil.getPuppetNode(params);
            String listenerId = ControllerUtil.getRequiredStringParam(params, "listenerId");
            Map<String, Object> results = javaPuppetNode.unloadCatalinaListener(catalinaName, listenerId);
            return ApiResponse.success(results);
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        } catch (Exception e) {
            return ApiResponse.error("卸载Listener失败: " + e.getMessage());
        }
    }

    @RequestMapping(value = "/unload-controller", method = RequestMethod.POST)
    public HashMap<String, Object> unloadController(@RequestBody HashMap<String, Object> params) {
        String sessionId = (String) params.get("sessionId");

        try {
            String webFramework = getWebFrameworkName(sessionId);
            JavaPuppetNode javaPuppetNode = ControllerUtil.getPuppetNode(params);
            String mappingInfo = ControllerUtil.getRequiredStringParam(params, "mappingInfo");
            Map<String, Object> results = javaPuppetNode.unloadSpringController(webFramework, mappingInfo);
            return ApiResponse.success(results);
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        } catch (Exception e) {
            return ApiResponse.error("卸载Controller失败: " + e.getMessage());
        }
    }

    @RequestMapping(value = "/unload-interceptor", method = RequestMethod.POST)
    public HashMap<String, Object> unloadInterceptor(@RequestBody HashMap<String, Object> params) {
        String sessionId = (String) params.get("sessionId");

        try {
            String webFramework = getWebFrameworkName(sessionId);
            JavaPuppetNode javaPuppetNode = ControllerUtil.getPuppetNode(params);
            String interceptorId = ControllerUtil.getRequiredStringParam(params, "interceptorId");
            Map<String, Object> results = javaPuppetNode.unloadSpringInterceptor(webFramework, interceptorId);
            return ApiResponse.success(results);
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        } catch (Exception e) {
            return ApiResponse.error("卸载Interceptor失败: " + e.getMessage());
        }
    }
}
