package org.leo.ai.tools.puppetnode;

import org.leo.ai.agent.AiToolContext;
import org.leo.ai.util.PuppetNodeSessionUtils;
import org.leo.core.puppet.impl.JavaPuppetNode;
import org.leo.core.session.PuppetNodeSession;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class CatalinaTools {

    @Tool("获取当前会话对应应用容器和 Spring Web 框架的管理信息。适用于查看 Tomcat、WebLogic、Filter、Servlet、Valve、Listener、Controller、Interceptor 等挂载情况。")
    public Map<String, Object> getCatalinaInfo() throws Exception {
        String sessionId = AiToolContext.requireSessionId();
        JavaPuppetNode node = PuppetNodeSessionUtils.getJavaPuppetNode(sessionId);
        return node.getCatalinaInfo(getCatalinaName(sessionId), getWebFrameworkName(sessionId));
    }

    @Tool("卸载 Filter。⚠️ 不可逆。contextName 和 filterName 从 getCatalinaInfo 返回的 filters 列表获取。")
    public Map<String, Object> unloadFilter(String contextName, String filterName) throws Exception {
        String sessionId = AiToolContext.requireSessionId();
        JavaPuppetNode node = PuppetNodeSessionUtils.getJavaPuppetNode(sessionId);
        return node.unloadCatalinaFilter(getCatalinaName(sessionId), contextName, filterName);
    }

    @Tool("卸载 Servlet 映射。⚠️ 不可逆。contextName 和 servletPattern 从 getCatalinaInfo 返回的 servlets 列表获取。")
    public Map<String, Object> unloadServlet(String contextName, String servletPattern) throws Exception {
        String sessionId = AiToolContext.requireSessionId();
        JavaPuppetNode node = PuppetNodeSessionUtils.getJavaPuppetNode(sessionId);
        return node.unloadCatalinaServlet(getCatalinaName(sessionId), contextName, servletPattern);
    }

    @Tool("卸载 Valve。⚠️ 不可逆。Tomcat 仅用。valveId 从 getCatalinaInfo 返回的 valves 列表获取。")
    public Map<String, Object> unloadValve(String valveId) throws Exception {
        String sessionId = AiToolContext.requireSessionId();
        JavaPuppetNode node = PuppetNodeSessionUtils.getJavaPuppetNode(sessionId);
        return node.unloadCatalinaValve(getCatalinaName(sessionId), valveId);
    }

    @Tool("卸载 Listener。⚠️ 不可逆。Tomcat 仅用。listenerId 从 getCatalinaInfo 返回的 listeners 列表获取。")
    public Map<String, Object> unloadListener(String listenerId) throws Exception {
        String sessionId = AiToolContext.requireSessionId();
        JavaPuppetNode node = PuppetNodeSessionUtils.getJavaPuppetNode(sessionId);
        return node.unloadCatalinaListener(getCatalinaName(sessionId), listenerId);
    }

    @Tool("卸载 Controller 映射。⚠️ 不可逆。Spring Web 仅用。mappingInfo 从 getCatalinaInfo 返回的 controllers 列表获取（如 GET /api/test）。")
    public Map<String, Object> unloadController(String mappingInfo) throws Exception {
        String sessionId = AiToolContext.requireSessionId();
        JavaPuppetNode node = PuppetNodeSessionUtils.getJavaPuppetNode(sessionId);
        return node.unloadSpringController(getWebFrameworkName(sessionId), mappingInfo);
    }

    @Tool("卸载 Interceptor。⚠️ 不可逆。Spring Web 仅用。interceptorId 从 getCatalinaInfo 返回的 interceptors 列表获取。")
    public Map<String, Object> unloadInterceptor(String interceptorId) throws Exception {
        String sessionId = AiToolContext.requireSessionId();
        JavaPuppetNode node = PuppetNodeSessionUtils.getJavaPuppetNode(sessionId);
        return node.unloadSpringInterceptor(getWebFrameworkName(sessionId), interceptorId);
    }

    private String getCatalinaName(String sessionId) {
        PuppetNodeSession session = PuppetNodeSessionUtils.getSession(sessionId);
        Map<String, Object> basicInfo = session.getBasicInfo(session.getCurrentHostId());
        if (basicInfo == null || basicInfo.get("MiddlewareInfo") == null) {
            throw new IllegalArgumentException("会话中不存在基础信息: " + sessionId);
        }
        Object middlewareInfo = basicInfo.get("MiddlewareInfo");
        if (!(middlewareInfo instanceof Map<?, ?> middlewareMap)) {
            throw new IllegalArgumentException("会话中的 MiddlewareInfo 格式无效: " + sessionId);
        }
        Object middlewareType = middlewareMap.get("MiddlewareType");
        if (middlewareType == null) {
            throw new IllegalArgumentException("会话中不存在中间件类型信息: " + sessionId);
        }
        return String.valueOf(middlewareType);
    }

    private String getWebFrameworkName(String sessionId) {
        PuppetNodeSession session = PuppetNodeSessionUtils.getSession(sessionId);
        Map<String, Object> basicInfo = session.getBasicInfo(session.getCurrentHostId());
        if (basicInfo == null) {
            throw new IllegalArgumentException("会话中不存在基础信息: " + sessionId);
        }
        Object webFramework = basicInfo.get("WebFramework");
        return webFramework == null ? "" : String.valueOf(webFramework);
    }
}
