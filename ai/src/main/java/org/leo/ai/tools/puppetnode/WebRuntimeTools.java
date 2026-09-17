package org.leo.ai.tools.puppetnode;

import org.leo.ai.agent.AiToolContext;
import org.leo.ai.agent.AiToolException;
import org.leo.ai.util.PuppetNodeSessionUtils;
import org.leo.core.puppet.capability.WebRuntimeManageCapable;
import org.leo.core.session.PuppetNodeSession;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.P;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Java Web Runtime 管理工具。
 *
 * <p>提供主流 Servlet 容器与 Java Web 框架组件的信息查看和运行期移除能力。
 * 注册功能不在此处（通过 Java 插件注入）。
 */
@Component
@org.leo.ai.agent.AiToolPolicy(kind = org.leo.ai.agent.AiToolKind.COMMAND,
        operation = org.leo.ai.agent.AiToolOperation.WRITE)
public class WebRuntimeTools {

    @Tool("获取当前会话对应应用容器和 Java Web 框架的管理信息。支持 Tomcat、WebLogic、Jetty、Undertow、WildFly/JBoss、WebSphere、Resin、Apusic、GlassFish/Payara、TongWeb、BES，以及 Spring MVC/WebFlux、Struts2、JSF/Jakarta Faces、JAX-RS 等；可查看 Filter、Servlet、Valve、Listener、Controller、Interceptor 等挂载情况。")
    @org.leo.ai.agent.AiToolPolicy(kind = org.leo.ai.agent.AiToolKind.QUERY,
            operation = org.leo.ai.agent.AiToolOperation.READ_ONLY, parallelizable = true)
    public Map<String, Object> inspectWebRuntime() throws Exception {
        String sessionId = AiToolContext.requireSessionId();
        WebRuntimeManageCapable node = PuppetNodeSessionUtils.requireCapability(sessionId, WebRuntimeManageCapable.class);
        Map<String, Object> runtime = getRuntimeInfo(sessionId);
        return node.inspectWebRuntime(String.valueOf(runtime.get("family")),
                String.valueOf(runtime.get("version")), getWebFrameworkName(sessionId));
    }

    @org.leo.ai.agent.AiToolPolicy(kind = org.leo.ai.agent.AiToolKind.COMMAND,
            operation = org.leo.ai.agent.AiToolOperation.DESTRUCTIVE, exclusive = true)
    @Tool("移除 Web Runtime 中的一个运行期组件。⚠️ 不可逆。\n"
            + "componentType: filter | servlet | valve | listener | controller | interceptor\n"
            + "详细信息从 inspectWebRuntime 返回结果中获取：\n"
            + "• filter → 传 contextId（来自 contextId 字段）+ identifier = filterName\n"
            + "• servlet → 传 contextId（来自 contextId 字段）+ identifier = url（servletPattern）\n"
            + "• valve → 传 identifier = valveId（Tomcat）\n"
            + "• listener → 传 identifier = listenerId\n"
            + "• controller → 传 identifier = mappingInfo（Spring/Struts2）\n"
            + "• interceptor → 传 identifier = interceptorId（Spring/Struts2/JSF）")
    public Map<String, Object> removeWebRuntimeComponent(
            @P("组件类型: filter/servlet/valve/listener/controller/interceptor") String componentType,
            @P("inspectWebRuntime 返回的 contextId，所有移除操作必填") String contextId,
            @P("组件标识，含义因类型而异（见工具描述）") String identifier) throws Exception {
        String sessionId = AiToolContext.requireSessionId();
        WebRuntimeManageCapable node = PuppetNodeSessionUtils.requireCapability(sessionId, WebRuntimeManageCapable.class);
        Map<String, Object> runtime = getRuntimeInfo(sessionId);
        String runtimeFamily = String.valueOf(runtime.get("family"));
        String runtimeVersion = String.valueOf(runtime.get("version"));
        String webFramework = getWebFrameworkName(sessionId);

        return node.removeWebRuntimeComponent(runtimeFamily, runtimeVersion, webFramework,
                componentType, requireNonEmpty(contextId, "contextId"), requireNonEmpty(identifier, "identifier"));
    }

    private Map<String, Object> getRuntimeInfo(String sessionId) {
        PuppetNodeSession session = PuppetNodeSessionUtils.getSession(sessionId);
        Map<String, Object> basicInfo = session.getBasicInfo(session.getCurrentHostId());
        if (basicInfo == null || !(basicInfo.get("MiddlewareInfo") instanceof Map<?, ?> middleware)) {
            throw missingBasicInfo();
        }
        Object family = middleware.get("MiddlewareType");
        if (family == null) throw missingBasicInfo();
        Object version = middleware.get("Version");
        return Map.of("family", String.valueOf(family),
                "version", version == null ? "unknown" : String.valueOf(version));
    }

    private String getWebFrameworkName(String sessionId) {
        PuppetNodeSession session = PuppetNodeSessionUtils.getSession(sessionId);
        Map<String, Object> basicInfo = session.getBasicInfo(session.getCurrentHostId());
        if (basicInfo == null) {
            throw missingBasicInfo();
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

    private AiToolException missingBasicInfo() {
        return AiToolException.modelCorrectable(
                "PRECONDITION_REQUIRED",
                "当前会话还没有容器基础信息。",
                "先调用基础信息工具收集中间件信息，再调用 Web Runtime 管理工具。");
    }
}
