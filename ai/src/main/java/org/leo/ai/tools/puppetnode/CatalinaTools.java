package org.leo.ai.tools.puppetnode;

import org.leo.ai.agent.AiToolContext;
import org.leo.ai.util.PuppetNodeSessionUtils;
import org.leo.core.puppet.impl.JavaPuppetNode;
import org.leo.core.session.PuppetNodeSession;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.P;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Catalina / Spring Web 容器管理工具。
 *
 * <p>提供 Tomcat/WebLogic/Spring Web 组件的信息查看和卸载能力。
 * 注册功能不在此处（通过 Java 插件注入）。
 */
@Component
public class CatalinaTools {

    @Tool("获取当前会话对应应用容器和 Spring Web 框架的管理信息。适用于查看 Tomcat、WebLogic、Filter、Servlet、Valve、Listener、Controller、Interceptor 等挂载情况。")
    public Map<String, Object> getCatalinaInfo() throws Exception {
        String sessionId = AiToolContext.requireSessionId();
        JavaPuppetNode node = PuppetNodeSessionUtils.getJavaPuppetNode(sessionId);
        return node.getCatalinaInfo(getCatalinaName(sessionId), getWebFrameworkName(sessionId));
    }

    @Tool("卸载 Web 容器中的一个组件。⚠️ 不可逆。\n"
            + "componentType: filter | servlet | valve | listener | controller | interceptor\n"
            + "详细信息从 getCatalinaInfo 返回结果中获取：\n"
            + "• filter → 传 contextName（来自 name 字段）+ identifier = filterName\n"
            + "• servlet → 传 contextName（来自 name 字段）+ identifier = url（servletPattern）\n"
            + "• valve → 传 identifier = valveId（仅 Tomcat）\n"
            + "• listener → 传 identifier = listenerId（仅 Tomcat）\n"
            + "• controller → 传 identifier = mappingInfo（仅 Spring，如 GET /api/test）\n"
            + "• interceptor → 传 identifier = interceptorId（仅 Spring）")
    public Map<String, Object> unloadWebComponent(
            @P("组件类型: filter/servlet/valve/listener/controller/interceptor") String componentType,
            @P("容器上下文名称。valve/listener/controller/interceptor 时可为空") String contextName,
            @P("组件标识，含义因类型而异（见工具描述）") String identifier) throws Exception {
        String sessionId = AiToolContext.requireSessionId();
        JavaPuppetNode node = PuppetNodeSessionUtils.getJavaPuppetNode(sessionId);
        String catalinaName = getCatalinaName(sessionId);
        String webFramework = getWebFrameworkName(sessionId);

        switch (componentType) {
            case "filter":
                return node.unloadCatalinaFilter(catalinaName,
                        requireNonEmpty(contextName, "contextName"), requireNonEmpty(identifier, "filterName"));
            case "servlet":
                return node.unloadCatalinaServlet(catalinaName,
                        requireNonEmpty(contextName, "contextName"), requireNonEmpty(identifier, "servletPattern"));
            case "valve":
                return node.unloadCatalinaValve(catalinaName,
                        requireNonEmpty(identifier, "valveId"));
            case "listener":
                return node.unloadCatalinaListener(catalinaName,
                        requireNonEmpty(identifier, "listenerId"));
            case "controller":
                return node.unloadSpringController(webFramework,
                        requireNonEmpty(identifier, "mappingInfo"));
            case "interceptor":
                return node.unloadSpringInterceptor(webFramework,
                        requireNonEmpty(identifier, "interceptorId"));
            default:
                throw new IllegalArgumentException(
                        "不支持的 componentType: " + componentType +
                                "，有效值: filter/servlet/valve/listener/controller/interceptor");
        }
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

    private static String requireNonEmpty(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " 不能为空");
        }
        return value;
    }
}
