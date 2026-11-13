package org.leo.jmg.catalog;

import org.leo.jmg.ShellGeneratorConfig;
import org.leo.jmg.ServletNamespace;
import org.leo.jmg.TargetJavaVersion;
import org.leo.jmg.TransportProtocol;
import org.leo.jmg.mem.ServerType;
import org.leo.jmg.mem.packer.PackerRegistry;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 生成器能力的唯一目录。
 *
 * <p>所有 server/protocol/injector/template 组合都在此注册。配置校验、实际生成和
 * REST/AI 元数据均从该目录查询，避免各层分别维护协议规则。</p>
 */
public final class GeneratorCatalog {

    private static final String SHELL_PACKAGE = "org.leo.jmg.mem.shell.http.";
    private static final String SHELL_FILTER = SHELL_PACKAGE + "LeoFilterTpl";
    private static final String SHELL_FILTER_CHUNK = SHELL_PACKAGE + "LeoFilterChunkTpl";
    private static final String SHELL_VALVE = SHELL_PACKAGE + "LeoValveTpl";
    private static final String SHELL_VALVE_CHUNK = SHELL_PACKAGE + "LeoValveChunkTpl";
    private static final String SHELL_LISTENER = SHELL_PACKAGE + "LeoListenerTpl";
    private static final String SHELL_LISTENER_CHUNK = SHELL_PACKAGE + "LeoListenerChunkTpl";
    private static final String SHELL_SERVLET = SHELL_PACKAGE + "LeoServletTpl";
    private static final String SHELL_SERVLET_CHUNK = SHELL_PACKAGE + "LeoServletChunkTpl";
    private static final String SHELL_WEBSOCKET = SHELL_PACKAGE + "LeoWebSocketTpl";
    private static final String SHELL_INTERCEPTOR = SHELL_PACKAGE + "LeoInterceptorTpl";
    private static final String SHELL_CONTROLLER = SHELL_PACKAGE + "LeoControllerHandlerTpl";
    private static final String SHELL_JETTY_CUSTOMIZER = SHELL_PACKAGE + "LeoJettyCustomizerTpl";
    private static final String SHELL_JETTY_HANDLER = SHELL_PACKAGE + "LeoJettyHandlerTpl";
    private static final String SHELL_JETTY_HANDLER_CHUNK = SHELL_PACKAGE + "LeoJettyHandlerChunkTpl";
    private static final String SHELL_STRUTS2 = SHELL_PACKAGE + "LeoStruts2ActionTpl";
    private static final String SHELL_AGENT = SHELL_PACKAGE + "LeoAgentTpl";
    private static final String SHELL_AGENT_CHUNK = SHELL_PACKAGE + "LeoAgentChunkTpl";
    private static final String SHELL_WEBFLUX_WEB_FILTER = SHELL_PACKAGE + "LeoWebFluxWebFilterTpl";
    private static final String SHELL_WEBFLUX_HANDLER_METHOD = SHELL_PACKAGE + "LeoWebFluxHandlerMethodTpl";
    private static final String SHELL_WEBFLUX_HANDLER_FUNCTION = SHELL_PACKAGE + "LeoWebFluxHandlerFunctionTpl";
    private static final String SHELL_NETTY_HANDLER = SHELL_PACKAGE + "LeoNettyHandlerTpl";
    private static final String SHELL_DUBBO_SERVICE = SHELL_PACKAGE + "LeoDubboServiceTpl";
    private static final Map<ServerType, List<InjectorDescriptor>> BY_SERVER;
    private static final List<InjectorDescriptor> ALL;

    static {
        LinkedHashMap<ServerType, List<InjectorDescriptor>> catalog =
                new LinkedHashMap<ServerType, List<InjectorDescriptor>>();

        registerTomcat(catalog);
        registerJbossFamily(catalog);
        registerJetty5(catalog);
        registerJetty(catalog);
        registerUndertow(catalog);
        registerWebLogic(catalog);
        registerWebSphere(catalog);
        registerResin2(catalog);
        registerResin(catalog);
        registerGlassfishFamily(catalog);
        registerSpringWebMvc(catalog);
        registerApusic(catalog);
        registerBes(catalog);
        registerInforSuite(catalog);
        registerTongWeb(catalog);
        registerStruts2(catalog);
        registerSpringWebFlux(catalog);
        registerXxlJob(catalog);
        registerDubbo(catalog);

        List<InjectorDescriptor> all = new ArrayList<InjectorDescriptor>();
        Set<String> uniqueKeys = new LinkedHashSet<String>();
        LinkedHashMap<ServerType, List<InjectorDescriptor>> immutable =
                new LinkedHashMap<ServerType, List<InjectorDescriptor>>();
        for (Map.Entry<ServerType, List<InjectorDescriptor>> entry : catalog.entrySet()) {
            List<InjectorDescriptor> descriptors =
                    Collections.unmodifiableList(new ArrayList<InjectorDescriptor>(entry.getValue()));
            for (InjectorDescriptor descriptor : descriptors) {
                String key = descriptor.getServerType().getValue() + "|"
                        + descriptor.getProtocol().getValue() + "|"
                        + descriptor.getInjectorName();
                if (!uniqueKeys.add(key)) {
                    throw new IllegalStateException("生成器目录存在重复项: " + key);
                }
                all.add(descriptor);
            }
            immutable.put(entry.getKey(), descriptors);
        }
        BY_SERVER = Collections.unmodifiableMap(immutable);
        ALL = Collections.unmodifiableList(all);
    }

    private GeneratorCatalog() {
    }

    public static InjectorDescriptor resolve(String serverType,
                                             String injectorName,
                                             String protocol) {
        ServerType server = ServerType.fromString(serverType);
        if (server == null || injectorName == null) {
            return null;
        }
        TransportProtocol transport;
        try {
            transport = TransportProtocol.parse(protocol);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
        String publicName = injectorName.trim();
        List<InjectorDescriptor> descriptors = BY_SERVER.get(server);
        if (descriptors == null) {
            return null;
        }
        for (InjectorDescriptor descriptor : descriptors) {
            if (descriptor.getProtocol() == transport
                    && descriptor.getInjectorName().equals(publicName)) {
                return descriptor;
            }
        }
        return null;
    }

    public static boolean supports(String serverType, String injectorName, String protocol) {
        return resolve(serverType, injectorName, protocol) != null;
    }

    public static List<InjectorDescriptor> getAllDescriptors() {
        return ALL;
    }

    /** HTTP 与 AI 共用的 Java 生成器元数据；入口自行追加运行时及展示信息。 */
    public static Map<String, Object> getMetadata() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("serverInjectorTypes", getServerInjectorMap());
        result.put("serverProtocolInjectorTypes", getProtocolInjectorMap());
        result.put("injectorCapabilities", getCapabilityDescriptors());
        result.put("packerTypes", PackerRegistry.getHierarchy());
        result.put("packerObfuscationSteps", PackerRegistry.getPackerObfuscationStepsMap());
        result.put("packerCompatibility", PackerRegistry.getCompatibilityMap());
        result.put("packerAvailability", PackerRegistry.getAvailabilityMap());
        List<String> versions = new ArrayList<String>();
        for (TargetJavaVersion version : TargetJavaVersion.values()) versions.add(version.getValue());
        result.put("targetJavaVersions", Collections.unmodifiableList(versions));
        result.put("servletNamespaces", ServletNamespace.valuesAsStrings());
        Map<String, Object> protocols = new LinkedHashMap<String, Object>();
        protocols.put("webshell", ShellGeneratorConfig.getSupportedWebShellProtocols());
        protocols.put("memoryshell", ShellGeneratorConfig.getSupportedMemoryShellProtocols());
        result.put("transportProtocols", Collections.unmodifiableMap(protocols));
        return Collections.unmodifiableMap(result);
    }

    public static Map<String, List<String>> getServerInjectorMap() {
        Map<String, List<String>> result = new LinkedHashMap<String, List<String>>();
        for (Map.Entry<ServerType, List<InjectorDescriptor>> entry : BY_SERVER.entrySet()) {
            Set<String> names = new LinkedHashSet<String>();
            for (InjectorDescriptor descriptor : entry.getValue()) {
                names.add(descriptor.getInjectorName());
            }
            result.put(entry.getKey().getValue(),
                    Collections.unmodifiableList(new ArrayList<String>(names)));
        }
        return Collections.unmodifiableMap(result);
    }

    public static Map<String, Map<String, List<String>>> getProtocolInjectorMap() {
        Map<String, Map<String, List<String>>> result =
                new LinkedHashMap<String, Map<String, List<String>>>();
        for (TransportProtocol protocol : TransportProtocol.values()) {
            Map<String, List<String>> byServer = new LinkedHashMap<String, List<String>>();
            for (Map.Entry<ServerType, List<InjectorDescriptor>> entry : BY_SERVER.entrySet()) {
                List<String> names = new ArrayList<String>();
                for (InjectorDescriptor descriptor : entry.getValue()) {
                    if (descriptor.getProtocol() == protocol) {
                        names.add(descriptor.getInjectorName());
                    }
                }
                if (!names.isEmpty()) {
                    byServer.put(entry.getKey().getValue(),
                            Collections.unmodifiableList(names));
                }
            }
            result.put(protocol.getValue(), Collections.unmodifiableMap(byServer));
        }
        return Collections.unmodifiableMap(result);
    }

    /** 返回前端/AI 可直接消费的结构化挂载能力目录。 */
    public static List<Map<String, Object>> getCapabilityDescriptors() {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (InjectorDescriptor descriptor : ALL) {
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("serverType", descriptor.getServerType().getValue());
            row.put("protocol", descriptor.getProtocol().getValue());
            row.put("injectorName", descriptor.getInjectorName());
            row.put("mountType", descriptor.getMountType().getValue());
            row.put("mountLabel", descriptor.getMountType().getLabel());
            row.put("servletNamespaces", descriptor.getSupportedServletNamespaces());
            row.put("defaultUrlPattern",
                    descriptor.getProtocol() == TransportProtocol.WEBSOCKET ? "/leo" : "/*");
            row.put("supportsHeaderGate", descriptor.supportsHeaderGate());
            row.put("supportsLambdaSuffix", true);
            row.put("supportsStaticInitialize", descriptor.supportsStaticInitialize());
            row.put("supportsUrlPattern", descriptor.supportsUrlPattern());
            if (descriptor.getMountType() == MountType.UPGRADE) {
                row.put("activationHeaders", Arrays.asList(
                        "Connection: Upgrade",
                        "Upgrade: ${shellClassName}"));
            }
            row.put("requiresServerVersion", descriptor.requiresServerVersion());
            row.put("serverVersions", descriptor.getSupportedServerVersions());
            row.put("supportedPackers", descriptor.getSupportedPackers());
            if (descriptor.getServerType() == ServerType.XXL_JOB) {
                Map<String, List<String>> byVersion = new LinkedHashMap<String, List<String>>();
                for (String version : descriptor.getSupportedServerVersions()) {
                    byVersion.put(version, descriptor.getSupportedPackers(version));
                }
                row.put("supportedPackersByServerVersion",
                        Collections.unmodifiableMap(byVersion));
            }
            result.add(Collections.unmodifiableMap(row));
        }
        return Collections.unmodifiableList(result);
    }

    private static void registerTomcat(Map<ServerType, List<InjectorDescriptor>> catalog) {
        List<Entry> entries = entries(
                httpAndChunk("FilterInjector", SHELL_FILTER, SHELL_FILTER_CHUNK,
                        "org.leo.jmg.mem.injectortpl.tomcat.TomcatFilterInjector"),
                httpAndChunk("ValveInjector", SHELL_VALVE, SHELL_VALVE_CHUNK,
                        "org.leo.jmg.mem.injectortpl.tomcat.TomcatValveInjector"),
                websocket("WebSocketInjector",
                        "org.leo.jmg.mem.injectortpl.tomcat.TomcatWebSocketInjector"),
                websocket("ByPassNginxWebSocketInjector",
                        "org.leo.jmg.mem.injectortpl.tomcat.TomcatWebSocketByPassInjector"),
                httpAndChunk("UpgradeInjector", SHELL_AGENT, SHELL_AGENT_CHUNK,
                        "org.leo.jmg.mem.injectortpl.tomcat.TomcatUpgradeInjector"),
                httpAndChunk("ServletInjector", SHELL_SERVLET, SHELL_SERVLET_CHUNK,
                        "org.leo.jmg.mem.injectortpl.tomcat.TomcatServletInjector"),
                httpAndChunk("ListenerInjector", SHELL_LISTENER, SHELL_LISTENER_CHUNK,
                        "org.leo.jmg.mem.injectortpl.tomcat.TomcatListenerInjector"),
                agentHttpAndChunk("AgentFilterChain",
                        "org.leo.jmg.mem.injectortpl.tomcat.TomcatFilterChainAgentInjector"),
                agentHttpAndChunk("AgentContextValve",
                        "org.leo.jmg.mem.injectortpl.tomcat.TomcatContextValveAgentInjector"),
                http("ProxyValveInjector", SHELL_VALVE,
                        "org.leo.jmg.mem.injectortpl.tomcat.TomcatProxyValveInjector"));
        put(catalog, ServerType.TOMCAT, entries);
    }

    private static void registerJbossFamily(Map<ServerType, List<InjectorDescriptor>> catalog) {
        List<Entry> jbossWebEntries = entries(
                httpAndChunk("FilterInjector", SHELL_FILTER, SHELL_FILTER_CHUNK,
                        "org.leo.jmg.mem.injectortpl.tomcat.TomcatFilterInjector"),
                httpAndChunk("ListenerInjector", SHELL_LISTENER, SHELL_LISTENER_CHUNK,
                        "org.leo.jmg.mem.injectortpl.tomcat.TomcatListenerInjector"),
                http("ValveInjector", SHELL_VALVE,
                        "org.leo.jmg.mem.injectortpl.glassfish.GlassFishValveInjector"),
                http("ProxyValveInjector", SHELL_VALVE,
                        "org.leo.jmg.mem.injectortpl.tomcat.TomcatProxyValveInjector"),
                websocket("WebSocketInjector",
                        "org.leo.jmg.mem.injectortpl.tomcat.TomcatWebSocketInjector"),
                agentHttpAndChunk("AgentFilterChain",
                        "org.leo.jmg.mem.injectortpl.tomcat.TomcatFilterChainAgentInjector"),
                agentHttpAndChunk("AgentContextValve",
                        "org.leo.jmg.mem.injectortpl.tomcat.TomcatContextValveAgentInjector"));
        put(catalog, ServerType.JBOSS, jbossWebEntries);
        put(catalog, ServerType.JBOSS_AS, jbossWebEntries);
        put(catalog, ServerType.JBOSS_EAP6, jbossWebEntries);

        List<Entry> undertowEntries = entries(
                httpAndChunk("FilterInjector", SHELL_FILTER, SHELL_FILTER_CHUNK,
                        "org.leo.jmg.mem.injectortpl.undertow.UndertowFilterInjector"),
                httpAndChunk("ListenerInjector", SHELL_LISTENER, SHELL_LISTENER_CHUNK,
                        "org.leo.jmg.mem.injectortpl.undertow.UndertowListenerInjector"),
                httpAndChunk("ServletInjector", SHELL_SERVLET, SHELL_SERVLET_CHUNK,
                        "org.leo.jmg.mem.injectortpl.undertow.UndertowServletInjector"),
                agentHttpAndChunk("AgentServletHandler",
                        "org.leo.jmg.mem.injectortpl.undertow.UndertowServletHandlerAgentInjector"));
        put(catalog, ServerType.JBOSS_EAP7, undertowEntries);
        put(catalog, ServerType.WILDFLY, undertowEntries);
    }

    private static void registerJetty(Map<ServerType, List<InjectorDescriptor>> catalog) {
        put(catalog, ServerType.JETTY, entries(
                httpAndChunk("FilterInjector", SHELL_FILTER, SHELL_FILTER_CHUNK,
                        "org.leo.jmg.mem.injectortpl.jetty.JettyFilterInjector"),
                httpAndChunk("ListenerInjector", SHELL_LISTENER, SHELL_LISTENER_CHUNK,
                        "org.leo.jmg.mem.injectortpl.jetty.JettyListenerInjector"),
                httpAndChunk("ServletInjector", SHELL_SERVLET, SHELL_SERVLET_CHUNK,
                        "org.leo.jmg.mem.injectortpl.jetty.JettyServletInjector"),
                versionedHttpAndChunk("HandlerInjector", SHELL_JETTY_HANDLER,
                        SHELL_JETTY_HANDLER_CHUNK, Arrays.asList("7-10", "11"),
                        "org.leo.jmg.mem.injectortpl.jetty.JettyHandlerInjector"),
                http("CustomizerInjector", SHELL_JETTY_CUSTOMIZER,
                        "org.leo.jmg.mem.injectortpl.jetty.JettyCustomizerInjector"),
                agentHttpAndChunk("AgentHandler",
                        "org.leo.jmg.mem.injectortpl.jetty.JettyHandlerAgentInjector")));
    }

    private static void registerJetty5(Map<ServerType, List<InjectorDescriptor>> catalog) {
        put(catalog, ServerType.JETTY5, entries(
                javaxHttpAndChunk("FilterInjector", SHELL_FILTER, SHELL_FILTER_CHUNK,
                        "org.leo.jmg.mem.injectortpl.jetty5.Jetty5FilterInjector"),
                javaxHttpAndChunk("ListenerInjector", SHELL_LISTENER, SHELL_LISTENER_CHUNK,
                        "org.leo.jmg.mem.injectortpl.jetty5.Jetty5ListenerInjector"),
                javaxHttpAndChunk("ServletInjector", SHELL_SERVLET, SHELL_SERVLET_CHUNK,
                        "org.leo.jmg.mem.injectortpl.jetty5.Jetty5ServletInjector"),
                agentHttpAndChunk("AgentHandler",
                        "org.leo.jmg.mem.injectortpl.jetty.JettyHandlerAgentInjector")));
    }

    private static void registerUndertow(Map<ServerType, List<InjectorDescriptor>> catalog) {
        put(catalog, ServerType.UNDERTOW, entries(
                httpAndChunk("FilterInjector", SHELL_FILTER, SHELL_FILTER_CHUNK,
                        "org.leo.jmg.mem.injectortpl.undertow.UndertowFilterInjector"),
                httpAndChunk("ListenerInjector", SHELL_LISTENER, SHELL_LISTENER_CHUNK,
                        "org.leo.jmg.mem.injectortpl.undertow.UndertowListenerInjector"),
                httpAndChunk("ServletInjector", SHELL_SERVLET, SHELL_SERVLET_CHUNK,
                        "org.leo.jmg.mem.injectortpl.undertow.UndertowServletInjector"),
                agentHttpAndChunk("AgentServletHandler",
                        "org.leo.jmg.mem.injectortpl.undertow.UndertowServletHandlerAgentInjector")));
    }

    private static void registerWebLogic(Map<ServerType, List<InjectorDescriptor>> catalog) {
        put(catalog, ServerType.WEBLOGIC, entries(
                httpAndChunk("FilterInjector", SHELL_FILTER, SHELL_FILTER_CHUNK,
                        "org.leo.jmg.mem.injectortpl.weblogic.WebLogicFilterInjector"),
                httpAndChunk("ListenerInjector", SHELL_LISTENER, SHELL_LISTENER_CHUNK,
                        "org.leo.jmg.mem.injectortpl.weblogic.WebLogicListenerInjector"),
                httpAndChunk("ServletInjector", SHELL_SERVLET, SHELL_SERVLET_CHUNK,
                        "org.leo.jmg.mem.injectortpl.weblogic.WebLogicServletInjector"),
                agentHttpAndChunk("AgentServletContext",
                        "org.leo.jmg.mem.injectortpl.weblogic.WebLogicServletContextAgentInjector"),
                websocket("WebSocketInjector",
                        "org.leo.jmg.mem.injectortpl.weblogic.WebLogicWebSocketInjector")));
    }

    private static void registerWebSphere(Map<ServerType, List<InjectorDescriptor>> catalog) {
        put(catalog, ServerType.WEBSPHERE, entries(
                httpAndChunk("FilterInjector", SHELL_FILTER, SHELL_FILTER_CHUNK,
                        "org.leo.jmg.mem.injectortpl.websphere.WebSphereFilterInjector"),
                httpAndChunk("ListenerInjector", SHELL_LISTENER, SHELL_LISTENER_CHUNK,
                        "org.leo.jmg.mem.injectortpl.websphere.WebSphereListenerInjector"),
                httpAndChunk("ServletInjector", SHELL_SERVLET, SHELL_SERVLET_CHUNK,
                        "org.leo.jmg.mem.injectortpl.websphere.WebSphereServletInjector"),
                agentHttpAndChunk("AgentFilterManager",
                        "org.leo.jmg.mem.injectortpl.websphere.WebSphereFilterChainAgentInjector")));
    }

    private static void registerResin(Map<ServerType, List<InjectorDescriptor>> catalog) {
        put(catalog, ServerType.RESIN, entries(
                httpAndChunk("FilterInjector", SHELL_FILTER, SHELL_FILTER_CHUNK,
                        "org.leo.jmg.mem.injectortpl.resin.ResinFilterInjector"),
                httpAndChunk("ListenerInjector", SHELL_LISTENER, SHELL_LISTENER_CHUNK,
                        "org.leo.jmg.mem.injectortpl.resin.ResinListenerInjector"),
                httpAndChunk("ServletInjector", SHELL_SERVLET, SHELL_SERVLET_CHUNK,
                        "org.leo.jmg.mem.injectortpl.resin.ResinServletInjector"),
                agentHttpAndChunk("AgentFilterChain",
                        "org.leo.jmg.mem.injectortpl.resin.ResinFilterChainAgentInjector")));
    }

    private static void registerResin2(Map<ServerType, List<InjectorDescriptor>> catalog) {
        put(catalog, ServerType.RESIN2, entries(
                javaxHttpAndChunk("FilterInjector", SHELL_FILTER, SHELL_FILTER_CHUNK,
                        "org.leo.jmg.mem.injectortpl.resin2.Resin2FilterInjector"),
                javaxHttpAndChunk("ServletInjector", SHELL_SERVLET, SHELL_SERVLET_CHUNK,
                        "org.leo.jmg.mem.injectortpl.resin2.Resin2ServletInjector")));
    }

    private static void registerGlassfishFamily(Map<ServerType, List<InjectorDescriptor>> catalog) {
        List<Entry> entries = entries(
                httpAndChunk("FilterInjector", SHELL_FILTER, SHELL_FILTER_CHUNK,
                        "org.leo.jmg.mem.injectortpl.glassfish.GlassFishFilterInjector"),
                httpAndChunk("ListenerInjector", SHELL_LISTENER, SHELL_LISTENER_CHUNK,
                        "org.leo.jmg.mem.injectortpl.tomcat.TomcatListenerInjector"),
                httpAndChunk("ValveInjector", SHELL_VALVE, SHELL_VALVE_CHUNK,
                        "org.leo.jmg.mem.injectortpl.glassfish.GlassFishValveInjector"),
                agentHttpAndChunk("AgentFilterChain",
                        "org.leo.jmg.mem.injectortpl.tomcat.TomcatFilterChainAgentInjector"),
                agentHttpAndChunk("AgentContextValve",
                        "org.leo.jmg.mem.injectortpl.tomcat.TomcatContextValveAgentInjector"));
        put(catalog, ServerType.GLASSFISH, entries);
        put(catalog, ServerType.PAYARA, entries);
    }

    private static void registerSpringWebMvc(Map<ServerType, List<InjectorDescriptor>> catalog) {
        put(catalog, ServerType.SPRING_WEBMVC, entries(
                http("InterceptorInjector", SHELL_INTERCEPTOR,
                        "org.leo.jmg.mem.injectortpl.springwebmvc.SpringWebMvcInterceptorInjector"),
                http("ControllerHandlerInjector", SHELL_CONTROLLER,
                        "org.leo.jmg.mem.injectortpl.springwebmvc.SpringWebMvcControllerHandlerInjector"),
                agentHttpAndChunk("AgentFrameworkServlet",
                        "org.leo.jmg.mem.injectortpl.springwebmvc.SpringWebMvcFrameworkServletAgentInjector")));
    }

    private static void registerApusic(Map<ServerType, List<InjectorDescriptor>> catalog) {
        put(catalog, ServerType.APUSIC, entries(
                httpAndChunk("FilterInjector", SHELL_FILTER, SHELL_FILTER_CHUNK,
                        "org.leo.jmg.mem.injectortpl.apusic.ApusicFilterInjector"),
                httpAndChunk("ListenerInjector", SHELL_LISTENER, SHELL_LISTENER_CHUNK,
                        "org.leo.jmg.mem.injectortpl.apusic.ApusicListenerInjector"),
                httpAndChunk("ServletInjector", SHELL_SERVLET, SHELL_SERVLET_CHUNK,
                        "org.leo.jmg.mem.injectortpl.apusic.ApusicServletInjector"),
                agentHttpAndChunk("AgentFilterChain",
                        "org.leo.jmg.mem.injectortpl.apusic.ApusicFilterChainAgentInjector")));
    }

    private static void registerBes(Map<ServerType, List<InjectorDescriptor>> catalog) {
        put(catalog, ServerType.BES, entries(
                httpAndChunk("FilterInjector", SHELL_FILTER, SHELL_FILTER_CHUNK,
                        "org.leo.jmg.mem.injectortpl.bes.BesFilterInjector"),
                httpAndChunk("ListenerInjector", SHELL_LISTENER, SHELL_LISTENER_CHUNK,
                        "org.leo.jmg.mem.injectortpl.bes.BesListenerInjector"),
                httpAndChunk("ValveInjector", SHELL_VALVE, SHELL_VALVE_CHUNK,
                        "org.leo.jmg.mem.injectortpl.bes.BesValveInjector"),
                agentHttpAndChunk("AgentFilterChain",
                        "org.leo.jmg.mem.injectortpl.bes.BesFilterChainAgentInjector"),
                agentHttpAndChunk("AgentContextValve",
                        "org.leo.jmg.mem.injectortpl.bes.BesContextValveAgentInjector")));
    }

    private static void registerInforSuite(Map<ServerType, List<InjectorDescriptor>> catalog) {
        put(catalog, ServerType.INFORSUITE, entries(
                httpAndChunk("FilterInjector", SHELL_FILTER, SHELL_FILTER_CHUNK,
                        "org.leo.jmg.mem.injectortpl.inforsuite.InforSuiteFilterInjector"),
                httpAndChunk("ListenerInjector", SHELL_LISTENER, SHELL_LISTENER_CHUNK,
                        "org.leo.jmg.mem.injectortpl.tomcat.TomcatListenerInjector"),
                httpAndChunk("ValveInjector", SHELL_VALVE, SHELL_VALVE_CHUNK,
                        "org.leo.jmg.mem.injectortpl.glassfish.GlassFishValveInjector"),
                agentHttpAndChunk("AgentFilterChain",
                        "org.leo.jmg.mem.injectortpl.tomcat.TomcatFilterChainAgentInjector"),
                agentHttpAndChunk("AgentContextValve",
                        "org.leo.jmg.mem.injectortpl.tomcat.TomcatContextValveAgentInjector")));
    }

    private static void registerTongWeb(Map<ServerType, List<InjectorDescriptor>> catalog) {
        put(catalog, ServerType.TONGWEB, entries(
                httpAndChunk("FilterInjector", SHELL_FILTER, SHELL_FILTER_CHUNK,
                        "org.leo.jmg.mem.injectortpl.tongweb.TongWebFilterInjector"),
                httpAndChunk("ListenerInjector", SHELL_LISTENER, SHELL_LISTENER_CHUNK,
                        "org.leo.jmg.mem.injectortpl.tongweb.TongWebListenerInjector"),
                versionedHttpAndChunk("ValveInjector", SHELL_VALVE, SHELL_VALVE_CHUNK,
                        Arrays.asList("6", "7", "8"),
                        "org.leo.jmg.mem.injectortpl.tongweb.TongWebValveInjector"),
                agentHttpAndChunk("AgentFilterChain",
                        "org.leo.jmg.mem.injectortpl.tongweb.TongWebFilterChainAgentInjector"),
                agentHttpAndChunk("AgentContextValve",
                        "org.leo.jmg.mem.injectortpl.tongweb.TongWebContextValveAgentInjector")));
    }

    private static void registerStruts2(Map<ServerType, List<InjectorDescriptor>> catalog) {
        put(catalog, ServerType.STRUTS2, entries(
                http("ActionInjector", SHELL_STRUTS2,
                        "org.leo.jmg.mem.injectortpl.struts2.Struts2ActionInjector")));
    }

    private static void registerSpringWebFlux(Map<ServerType, List<InjectorDescriptor>> catalog) {
        put(catalog, ServerType.SPRING_WEBFLUX, entries(
                http("WebFilterInjector", SHELL_WEBFLUX_WEB_FILTER,
                        "org.leo.jmg.mem.injectortpl.springwebflux.SpringWebFluxWebFilterInjector"),
                http("HandlerMethodInjector", SHELL_WEBFLUX_HANDLER_METHOD,
                        "org.leo.jmg.mem.injectortpl.springwebflux.SpringWebFluxHandlerMethodInjector"),
                http("HandlerFunctionInjector", SHELL_WEBFLUX_HANDLER_FUNCTION,
                        "org.leo.jmg.mem.injectortpl.springwebflux.SpringWebFluxHandlerFunctionInjector"),
                http("NettyHandlerInjector", SHELL_NETTY_HANDLER,
                        "org.leo.jmg.mem.injectortpl.springwebflux.SpringWebFluxNettyHandlerInjector")));
    }

    private static void registerXxlJob(Map<ServerType, List<InjectorDescriptor>> catalog) {
        put(catalog, ServerType.XXL_JOB, entries(
                versionedHttp("NettyHandlerInjector", SHELL_NETTY_HANDLER,
                        Arrays.asList("2.2-2.5", "2.0-2.1"),
                        Arrays.asList("XxlJob", "XxlJobJson", "XxlJobHessian"),
                        "org.leo.jmg.mem.injectortpl.xxljob.XxlJobNettyHandlerInjector")));
    }

    private static void registerDubbo(Map<ServerType, List<InjectorDescriptor>> catalog) {
        put(catalog, ServerType.DUBBO, entries(
                http("ApacheDubboServiceInjector", SHELL_DUBBO_SERVICE,
                        "org.leo.jmg.mem.injectortpl.dubbo.ApacheDubboServiceInjector"),
                http("AlibabaDubboServiceInjector", SHELL_DUBBO_SERVICE,
                        "org.leo.jmg.mem.injectortpl.dubbo.AlibabaDubboServiceInjector")));
    }

    private static Entry[] http(String injectorName,
                                String shellTemplate,
                                String injectorTemplate) {
        return new Entry[]{
                new Entry(TransportProtocol.HTTP, injectorName, shellTemplate, injectorTemplate)
        };
    }

    private static Entry[] websocket(String injectorName, String injectorTemplate) {
        return new Entry[]{
                new Entry(TransportProtocol.WEBSOCKET, injectorName,
                        SHELL_WEBSOCKET, injectorTemplate)
        };
    }

    private static Entry[] httpAndChunk(String injectorName,
                                        String httpShellTemplate,
                                        String chunkShellTemplate,
                                        String injectorTemplate) {
        return new Entry[]{
                new Entry(TransportProtocol.HTTP, injectorName,
                        httpShellTemplate, injectorTemplate),
                new Entry(TransportProtocol.HTTP_CHUNK, injectorName,
                        chunkShellTemplate, injectorTemplate)
        };
    }

    private static Entry[] javaxHttpAndChunk(String injectorName,
                                             String httpShellTemplate,
                                             String chunkShellTemplate,
                                             String injectorTemplate) {
        return new Entry[]{
                new Entry(TransportProtocol.HTTP, injectorName,
                        httpShellTemplate, injectorTemplate, false),
                new Entry(TransportProtocol.HTTP_CHUNK, injectorName,
                        chunkShellTemplate, injectorTemplate, false)
        };
    }

    private static Entry[] versionedHttpAndChunk(String injectorName,
                                                  String httpShellTemplate,
                                                  String chunkShellTemplate,
                                                  List<String> serverVersions,
                                                  String injectorTemplate) {
        return new Entry[]{
                new Entry(TransportProtocol.HTTP, injectorName,
                        httpShellTemplate, injectorTemplate, true, serverVersions),
                new Entry(TransportProtocol.HTTP_CHUNK, injectorName,
                        chunkShellTemplate, injectorTemplate, true, serverVersions)
        };
    }

    private static Entry[] versionedHttp(String injectorName,
                                         String shellTemplate,
                                         List<String> serverVersions,
                                         List<String> supportedPackers,
                                         String injectorTemplate) {
        return new Entry[]{
                new Entry(TransportProtocol.HTTP, injectorName,
                        shellTemplate, injectorTemplate, true,
                        serverVersions, supportedPackers, true)
        };
    }

    private static Entry[] agentHttpAndChunk(String injectorName,
                                              String injectorTemplate) {
        List<String> packers = Collections.singletonList("AgentJarBase64");
        return new Entry[]{
                new Entry(TransportProtocol.HTTP, injectorName,
                        SHELL_AGENT, injectorTemplate, true,
                        Collections.<String>emptyList(), packers, false),
                new Entry(TransportProtocol.HTTP_CHUNK, injectorName,
                        SHELL_AGENT_CHUNK, injectorTemplate, true,
                        Collections.<String>emptyList(), packers, false)
        };
    }

    private static List<Entry> entries(Entry[]... groups) {
        List<Entry> result = new ArrayList<Entry>();
        for (Entry[] group : groups) {
            Collections.addAll(result, group);
        }
        return result;
    }

    private static void put(Map<ServerType, List<InjectorDescriptor>> catalog,
                            ServerType serverType,
                            List<Entry> entries) {
        List<InjectorDescriptor> descriptors = new ArrayList<InjectorDescriptor>();
        for (Entry entry : entries) {
            descriptors.add(new InjectorDescriptor(serverType, entry.protocol,
                    entry.injectorName, entry.shellTemplate, entry.injectorTemplate,
                    entry.supportsJakarta, entry.serverVersions, entry.supportedPackers,
                    entry.supportsStaticInitialize));
        }
        catalog.put(serverType, descriptors);
    }

    private static final class Entry {
        private final TransportProtocol protocol;
        private final String injectorName;
        private final String shellTemplate;
        private final String injectorTemplate;
        private final boolean supportsJakarta;
        private final List<String> serverVersions;
        private final List<String> supportedPackers;
        private final boolean supportsStaticInitialize;

        private Entry(TransportProtocol protocol,
                      String injectorName,
                      String shellTemplate,
                      String injectorTemplate) {
            this(protocol, injectorName, shellTemplate, injectorTemplate,
                    true, Collections.<String>emptyList(), Collections.<String>emptyList(), true);
        }

        private Entry(TransportProtocol protocol,
                      String injectorName,
                      String shellTemplate,
                      String injectorTemplate,
                      boolean supportsJakarta) {
            this(protocol, injectorName, shellTemplate, injectorTemplate,
                    supportsJakarta, Collections.<String>emptyList(), Collections.<String>emptyList(), true);
        }

        private Entry(TransportProtocol protocol,
                      String injectorName,
                      String shellTemplate,
                      String injectorTemplate,
                      boolean supportsJakarta,
                      List<String> serverVersions) {
            this(protocol, injectorName, shellTemplate, injectorTemplate,
                    supportsJakarta, serverVersions, Collections.<String>emptyList(), true);
        }

        private Entry(TransportProtocol protocol,
                      String injectorName,
                      String shellTemplate,
                      String injectorTemplate,
                      boolean supportsJakarta,
                      List<String> serverVersions,
                      List<String> supportedPackers) {
            this(protocol, injectorName, shellTemplate, injectorTemplate,
                    supportsJakarta, serverVersions, supportedPackers, true);
        }

        private Entry(TransportProtocol protocol,
                      String injectorName,
                      String shellTemplate,
                      String injectorTemplate,
                      boolean supportsJakarta,
                      List<String> serverVersions,
                      List<String> supportedPackers,
                      boolean supportsStaticInitialize) {
            this.protocol = protocol;
            this.injectorName = injectorName;
            this.shellTemplate = shellTemplate;
            this.injectorTemplate = injectorTemplate;
            this.supportsJakarta = supportsJakarta;
            this.serverVersions = serverVersions;
            this.supportedPackers = supportedPackers;
            this.supportsStaticInitialize = supportsStaticInitialize;
        }
    }
}
