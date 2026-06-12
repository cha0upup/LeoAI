package org.leo.jmg;

import org.leo.jmg.mem.packer.Packer;
import org.leo.jmg.mem.packer.PackerRegistry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;


/**
 * 应用服务器与注入器形态映射；{@link PackerRegistry} 解析（按名称，忽略大小写）。
 *
 * @author LeoSpring
 */
public class ServerInjectorMapper {

    private static final String SHELL_FILTER = "LeoFilterTpl";
    private static final String SHELL_VALVE = "LeoValveTpl";
    private static final String SHELL_LISTENER = "LeoListenerTpl";
    private static final String SHELL_SERVLET = "LeoServletTpl";
    private static final String SHELL_WEBSOCKET = "LeoWebSocketTpl";
    private static final String SHELL_INTERCEPTOR = "LeoInterceptorTpl";
    private static final String SHELL_STRUCT2 = "LeoStruct2ActionTpl";

    /**
     * 注入器模板对：Shell 模板类名 + Injector 模板类名
     */
    public static class InjectorTemplatePair {
        private final String injectorName;
        private final String shellTplName;
        private final String injectorTplName;

        public InjectorTemplatePair(String injectorName, String shellTplName, String injectorTplName) {
            this.injectorName = injectorName;
            this.shellTplName = shellTplName;
            this.injectorTplName = injectorTplName;
        }

        public String getInjectorName() {
            return injectorName;
        }

        public String getShellTplName() {
            return shellTplName;
        }

        public String getInjectorTplName() {
            return injectorTplName;
        }
    }

    /**
     * 中间件类型到注入器模板列表的映射
     * Key: serverType（与 {@link org.leo.jmg.mem.ServerType#getValue()} 一致）
     */
    private static final Map<String, List<InjectorTemplatePair>> serverInjectorTypeMap = new ConcurrentHashMap<>();

    static {
        registerTomcat();
        registerJetty();
        registerUndertowFamily();
        registerWebLogic();
        registerWebSphere();
        registerResin();
        registerGlassfishFamily();
        registerSpringWebMvc();
        registerApusic();
        registerBes();
        registerInforSuite();
        registerTongWeb();
        registerStruct2();
        registerJBoss();
    }


    private static void registerTomcat() {
        List<InjectorTemplatePair> list = new ArrayList<>();
        list.add(pair("FilterInjector", SHELL_FILTER, "org.leo.jmg.mem.injectortpl.tomcat.TomcatFilterInjector"));
        list.add(pair("ValveInjector", SHELL_VALVE, "org.leo.jmg.mem.injectortpl.tomcat.TomcatValveInjector"));
        list.add(pair("WebSocketInjector", SHELL_WEBSOCKET, "org.leo.jmg.mem.injectortpl.tomcat.TomcatWebSocketInjector"));
        list.add(pair("ServletInjector", SHELL_SERVLET, "org.leo.jmg.mem.injectortpl.tomcat.TomcatServletInjector"));
        list.add(pair("ListenerInjector", SHELL_LISTENER, "org.leo.jmg.mem.injectortpl.tomcat.TomcatListenerInjector"));
        list.add(pair("ProxyValveInjector", SHELL_VALVE, "org.leo.jmg.mem.injectortpl.tomcat.TomcatProxyValveInjector"));
        list.add(pair("UpgradeInjector", SHELL_FILTER, "org.leo.jmg.mem.injectortpl.tomcat.TomcatUpgradeInjector"));
        serverInjectorTypeMap.put("Tomcat", list);
    }
    private static void registerJBoss() {
        List<InjectorTemplatePair> list = new ArrayList<>();
        list.add(pair("FilterInjector", SHELL_FILTER, "org.leo.jmg.mem.injectortpl.tomcat.TomcatFilterInjector"));
        list.add(pair("ListenerInjector", SHELL_LISTENER, "org.leo.jmg.mem.injectortpl.tomcat.TomcatListenerInjector"));
        list.add(pair("ValveInjector", SHELL_VALVE, "org.leo.jmg.mem.injectortpl.glassfish.GlassFishValveInjector"));
        list.add(pair("ProxyValveInjector", SHELL_VALVE, "org.leo.jmg.mem.injectortpl.tomcat.TomcatProxyValveInjector"));
        // JBoss AS / EAP 6/7 / WildFly 共用同一套注入器
        serverInjectorTypeMap.put("JBoss", list);
        serverInjectorTypeMap.put("JBossAS", list);
        serverInjectorTypeMap.put("JBossEAP6", list);
        serverInjectorTypeMap.put("JBossEAP7", list);
        serverInjectorTypeMap.put("Wildfly", list);
    }
    private static void registerJetty() {
        List<InjectorTemplatePair> list = new ArrayList<>();
        list.add(pair("FilterInjector", SHELL_FILTER, "org.leo.jmg.mem.injectortpl.jetty.JettyFilterInjector"));
        list.add(pair("ListenerInjector", SHELL_LISTENER, "org.leo.jmg.mem.injectortpl.jetty.JettyListenerInjector"));
        list.add(pair("ServletInjector", SHELL_SERVLET, "org.leo.jmg.mem.injectortpl.jetty.JettyServletInjector"));
        list.add(pair("CustomizerInjector", SHELL_FILTER, "org.leo.jmg.mem.injectortpl.jetty.JettyCustomizerInjector"));
        serverInjectorTypeMap.put("Jetty", list);
    }

    private static void registerUndertowFamily() {
        List<InjectorTemplatePair> list = new ArrayList<>();
        list.add(pair("FilterInjector", SHELL_FILTER, "org.leo.jmg.mem.injectortpl.undertow.UndertowFilterInjector"));
        list.add(pair("ListenerInjector", SHELL_LISTENER, "org.leo.jmg.mem.injectortpl.undertow.UndertowListenerInjector"));
        list.add(pair("ServletInjector", SHELL_SERVLET, "org.leo.jmg.mem.injectortpl.undertow.UndertowServletInjector"));
        serverInjectorTypeMap.put("Undertow", list);
    }

    private static void registerWebLogic() {
        List<InjectorTemplatePair> list = new ArrayList<>();
        list.add(pair("FilterInjector", SHELL_FILTER, "org.leo.jmg.mem.injectortpl.weblogic.WebLogicFilterInjector"));
        list.add(pair("ListenerInjector", SHELL_LISTENER, "org.leo.jmg.mem.injectortpl.weblogic.WebLogicListenerInjector"));
        list.add(pair("ServletInjector", SHELL_SERVLET, "org.leo.jmg.mem.injectortpl.weblogic.WebLogicServletInjector"));
        serverInjectorTypeMap.put("WebLogic", list);
    }

    private static void registerWebSphere() {
        List<InjectorTemplatePair> list = new ArrayList<>();
        list.add(pair("FilterInjector", SHELL_FILTER, "org.leo.jmg.mem.injectortpl.websphere.WebSphereFilterInjector"));
        list.add(pair("ListenerInjector", SHELL_LISTENER, "org.leo.jmg.mem.injectortpl.websphere.WebSphereListenerInjector"));
        list.add(pair("ServletInjector", SHELL_SERVLET, "org.leo.jmg.mem.injectortpl.websphere.WebSphereServletInjector"));
        serverInjectorTypeMap.put("WebSphere", list);
    }

    private static void registerResin() {
        List<InjectorTemplatePair> list = new ArrayList<>();
        list.add(pair("FilterInjector", SHELL_FILTER, "org.leo.jmg.mem.injectortpl.resin.ResinFilterInjector"));
        list.add(pair("ListenerInjector", SHELL_LISTENER, "org.leo.jmg.mem.injectortpl.resin.ResinListenerInjector"));
        list.add(pair("ServletInjector", SHELL_SERVLET, "org.leo.jmg.mem.injectortpl.resin.ResinServletInjector"));
        serverInjectorTypeMap.put("Resin", list);
    }

    private static void registerGlassfishFamily() {
        List<InjectorTemplatePair> list = new ArrayList<>();
        list.add(pair("FilterInjector", SHELL_FILTER, "org.leo.jmg.mem.injectortpl.glassfish.GlassFishFilterInjector"));
        list.add(pair("ValveInjector", SHELL_VALVE, "org.leo.jmg.mem.injectortpl.glassfish.GlassFishValveInjector"));
        // Payara 是 GlassFish 的社区分支，共用同一套注入器
        serverInjectorTypeMap.put("Glassfish", list);
        serverInjectorTypeMap.put("Payara", list);
    }

    private static void registerSpringWebMvc() {
        List<InjectorTemplatePair> list = new ArrayList<>();
        list.add(pair("InterceptorInjector", SHELL_INTERCEPTOR, "org.leo.jmg.mem.injectortpl.springwebmvc.SpringWebMvcInterceptorInjector"));
        list.add(pair("MVCInterceptor", SHELL_INTERCEPTOR, "org.leo.jmg.mem.injectortpl.springwebmvc.SpringWebMvcInterceptorInjector"));
        list.add(pair("ControllerHandlerInjector", SHELL_INTERCEPTOR, "org.leo.jmg.mem.injectortpl.springwebmvc.SpringWebMvcControllerHandlerInjector"));
        serverInjectorTypeMap.put("SpringWebMVC", list);
    }

    private static void registerApusic() {
        List<InjectorTemplatePair> list = new ArrayList<>();
        list.add(pair("FilterInjector_V9", SHELL_FILTER, "org.leo.jmg.mem.injectortpl.apusic.ApusicFilterInjector"));
        list.add(pair("FilterInjector_V10", SHELL_FILTER, "org.leo.jmg.mem.injectortpl.apusic.ApusicFilterInjector"));
        list.add(pair("FilterInjector", SHELL_FILTER, "org.leo.jmg.mem.injectortpl.apusic.ApusicFilterInjector"));
        list.add(pair("ListenerInjector", SHELL_LISTENER, "org.leo.jmg.mem.injectortpl.apusic.ApusicListenerInjector"));
        list.add(pair("ServletInjector", SHELL_SERVLET, "org.leo.jmg.mem.injectortpl.apusic.ApusicServletInjector"));
        serverInjectorTypeMap.put("Apusic", list);
    }

    private static void registerBes() {
        List<InjectorTemplatePair> list = new ArrayList<>();
        list.add(pair("FilterInjector", SHELL_FILTER, "org.leo.jmg.mem.injectortpl.bes.BesFilterInjector"));
        list.add(pair("ListenerInjector", SHELL_LISTENER, "org.leo.jmg.mem.injectortpl.bes.BesListenerInjector"));
        list.add(pair("ValveInjector", SHELL_VALVE, "org.leo.jmg.mem.injectortpl.bes.BesValveInjector"));
        serverInjectorTypeMap.put("BES", list);
    }

    private static void registerInforSuite() {
        List<InjectorTemplatePair> list = new ArrayList<>();
        list.add(pair("FilterInjector", SHELL_FILTER, "org.leo.jmg.mem.injectortpl.inforsuite.InforSuiteFilterInjector"));
        serverInjectorTypeMap.put("InforSuite", list);
    }


    private static void registerTongWeb() {
        List<InjectorTemplatePair> list = new ArrayList<>();
        list.add(pair("FilterInjector", SHELL_FILTER, "org.leo.jmg.mem.injectortpl.tongweb.TongWebFilterInjector"));
        list.add(pair("ListenerInjector", SHELL_LISTENER, "org.leo.jmg.mem.injectortpl.tongweb.TongWebListenerInjector"));
        list.add(pair("ValveInjector", SHELL_VALVE, "org.leo.jmg.mem.injectortpl.tongweb.TongWebValveInjector"));
        serverInjectorTypeMap.put("TongWeb", list);
    }

    private static void registerStruct2() {
        List<InjectorTemplatePair> list = new ArrayList<>();
        list.add(pair("ActionInjector", SHELL_STRUCT2, "org.leo.jmg.mem.injectortpl.struct2.Struct2ActionInjectorTpl"));
        serverInjectorTypeMap.put("Struct2", list);
    }

    private static InjectorTemplatePair pair(String injectorName, String shellTplName, String injectorTplName) {
        return new InjectorTemplatePair(injectorName, shellTplName, injectorTplName);
    }

    /**
     * 检查应用服务器是否支持指定的注入器形态
     */
    public static boolean isInjectorTypeSupported(String serverType, String injectorType) {
        List<InjectorTemplatePair> pairs = serverInjectorTypeMap.get(serverType);
        if (pairs == null) {
            return false;
        }
        for (InjectorTemplatePair pair : pairs) {
            if (pair.getInjectorName().equals(injectorType)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 获取指定 serverType + shellType（注入器形态）的模板对
     */
    public static InjectorTemplatePair getInjectorTemplate(String serverType, String shellType) {
        List<InjectorTemplatePair> pairs = serverInjectorTypeMap.get(serverType);
        if (pairs == null) {
            return null;
        }
        for (InjectorTemplatePair pair : pairs) {
            if (pair.getInjectorName().equals(shellType)) {
                return pair;
            }
        }
        return null;
    }

    /**
     * 所有应用服务器类型及其支持的注入器形态名列表
     */
    public static Map<String, List<String>> getAllServerInjectorMapAsString() {
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (Map.Entry<String, List<InjectorTemplatePair>> entry : serverInjectorTypeMap.entrySet()) {
            List<String> injectorNames = new ArrayList<>();
            for (InjectorTemplatePair pair : entry.getValue()) {
                injectorNames.add(pair.getInjectorName());
            }
            result.put(entry.getKey(), injectorNames);
        }
        return result;
    }

    /**
     * 打包器类型的层级结构，供前端分组展示。
     * <p>
     * 返回的 Map 结构固定为：
     * <ul>
     *   <li>{@code groups}：{@code List}&lt;{@code Map}&gt;，每项含 {@code groupName}（字符串）、{@code packers}（该组下名称列表）</li>
     *   <li>{@code ungrouped}：无分组标记的打包器名称列表</li>
     * </ul>
     */
    public static Map<String, Object> getSupportedPackerTypesHierarchy() {
        return PackerRegistry.getHierarchy();
    }


    /**
     * 根据类型获取 {@link Packer} 实例，从 {@link PackerRegistry} 获取。
     */
    public static Packer getPacker(String packerType) {
        return PackerRegistry.get(packerType);
    }

}
