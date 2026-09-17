package org.leo.web.controller.puppetnode.webruntime;

import org.leo.core.puppet.capability.WebRuntimeManageCapable;
import org.leo.core.repository.session.PuppetWebRuntimeRepository;
import org.leo.core.session.PuppetNodeSession;
import org.leo.core.util.ApiResponse;
import org.leo.core.repository.session.PuppetHostCacheRepository;
import org.leo.web.util.ControllerUtil;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/puppet-node/web-runtime")
public class WebRuntimeManageController {

    private final PuppetHostCacheRepository hostCacheRepository;
    private final PuppetWebRuntimeRepository webRuntimeRepository;

    public WebRuntimeManageController(PuppetHostCacheRepository hostCacheRepository,
                                      PuppetWebRuntimeRepository webRuntimeRepository) {
        this.hostCacheRepository = hostCacheRepository;
        this.webRuntimeRepository = webRuntimeRepository;
    }

    @RequestMapping(value = "/inspect", method = RequestMethod.POST)
    public HashMap<String, Object> inspect(@RequestBody HashMap<String, Object> params) {
        String sessionId = (String) params.get("sessionId");
        try {
            Map<String, Object> basicInfo = resolveBasicInfo(sessionId);
            Map<String, Object> middleware = middlewareInfo(basicInfo);
            WebRuntimeManageCapable node = ControllerUtil.requireCapability(params, WebRuntimeManageCapable.class);
            Map<String, Object> snapshot = node.inspectWebRuntime(
                    string(middleware.get("MiddlewareType")),
                    string(middleware.get("Version")),
                    string(basicInfo.get("WebFramework")));
            if (sessionId != null && !sessionId.isBlank() && snapshot != null) {
                webRuntimeRepository.save(sessionId, snapshot);
            }
            return ApiResponse.success(snapshot);
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        } catch (Exception e) {
            return ApiResponse.error("获取 Web Runtime 信息失败: " + e.getMessage());
        }
    }

    @RequestMapping(value = "/components/remove", method = RequestMethod.POST)
    public HashMap<String, Object> remove(@RequestBody HashMap<String, Object> params) {
        String sessionId = (String) params.get("sessionId");
        try {
            Map<String, Object> basicInfo = resolveBasicInfo(sessionId);
            Map<String, Object> middleware = middlewareInfo(basicInfo);
            WebRuntimeManageCapable node = ControllerUtil.requireCapability(params, WebRuntimeManageCapable.class);
            Map<String, Object> result = node.removeWebRuntimeComponent(
                    string(middleware.get("MiddlewareType")),
                    string(middleware.get("Version")),
                    string(basicInfo.get("WebFramework")),
                    ControllerUtil.getRequiredStringParam(params, "componentType"),
                    ControllerUtil.getRequiredStringParam(params, "contextId"),
                    ControllerUtil.getRequiredStringParam(params, "identifier"));
            return ApiResponse.success(result);
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        } catch (Exception e) {
            return ApiResponse.error("移除 Web Runtime 组件失败: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> resolveBasicInfo(String sessionId) {
        PuppetNodeSession session = ControllerUtil.getPuppetNodeSession(sessionId);
        Map<String, Object> basicInfo = session != null ? session.getBasicInfo(session.getCurrentHostId()) : null;
        if (basicInfo == null) basicInfo = hostCacheRepository.loadBasicInfo(sessionId);
        if (basicInfo == null) throw new IllegalArgumentException("会话中不存在基础信息: " + sessionId);
        return basicInfo;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> middlewareInfo(Map<String, Object> basicInfo) {
        Object value = basicInfo.get("MiddlewareInfo");
        if (!(value instanceof Map)) throw new IllegalArgumentException("基础信息中不存在 MiddlewareInfo");
        return (Map<String, Object>) value;
    }

    private String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
