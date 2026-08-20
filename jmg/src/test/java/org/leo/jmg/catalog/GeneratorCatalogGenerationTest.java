package org.leo.jmg.catalog;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.leo.core.entity.Disguise;
import org.leo.jmg.ServletNamespace;
import org.leo.jmg.ShellGenerator;
import org.leo.jmg.ShellGeneratorConfig;
import org.leo.jmg.TransportProtocol;
import org.leo.jmg.generation.GenerationRequest;
import org.leo.jmg.generation.GenerationResult;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.InputStream;
import java.util.Collection;
import java.util.Arrays;
import java.util.Base64;
import java.util.jar.JarInputStream;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import javassist.bytecode.ClassFile;
import javassist.bytecode.MethodInfo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 确保能力目录中的每个公开组合都能走完整 Core → Shell → Injector 管线。 */
class GeneratorCatalogGenerationTest {

    @TestFactory
    Collection<DynamicTest> everyCatalogEntryGeneratesValidClassBytes() {
        AtomicLong seed = new AtomicLong(9000L);
        return GeneratorCatalog.getAllDescriptors().stream()
                .map(descriptor -> DynamicTest.dynamicTest(label(descriptor), () -> {
                    GenerationResult result = generate(descriptor, seed.getAndIncrement());
                    assertNotNull(result.getShellClassBytes());
                    assertNotNull(result.getInjectorClassBytes());
                    assertTrue(result.getShellClassBytes().length > 4);
                    assertTrue(result.getInjectorClassBytes().length > 4);
                    assertEquals((byte) 0xca, result.getInjectorClassBytes()[0]);
                    assertEquals((byte) 0xfe, result.getInjectorClassBytes()[1]);
                    assertEquals((byte) 0xba, result.getInjectorClassBytes()[2]);
                    assertEquals((byte) 0xbe, result.getInjectorClassBytes()[3]);
                    if (isGlobalMount(descriptor.getMountType())) {
                        assertFieldAbsent(result.getInjectorClassBytes(), "urlPattern");
                    }
                    if (descriptor.getProtocol() == TransportProtocol.WEBSOCKET) {
                        assertFieldAbsent(result.getShellClassBytes(), "respCode");
                    }
                }))
                .collect(Collectors.toList());
    }

    @TestFactory
    Collection<DynamicTest> everyJakartaCapabilityGeneratesValidClassBytes() {
        AtomicLong seed = new AtomicLong(12000L);
        return GeneratorCatalog.getAllDescriptors().stream()
                .filter(descriptor -> descriptor.supportsServletNamespace(ServletNamespace.JAKARTA))
                .map(descriptor -> DynamicTest.dynamicTest(
                        label(descriptor) + " / jakarta", () -> {
                            GenerationResult result = generate(
                                    descriptor, seed.getAndIncrement(), ServletNamespace.JAKARTA);
                            assertNotNull(result.getShellClassBytes());
                            assertNotNull(result.getInjectorClassBytes());
                            assertEquals((byte) 0xca, result.getShellClassBytes()[0]);
                            assertEquals((byte) 0xca, result.getInjectorClassBytes()[0]);
                        }))
                .collect(Collectors.toList());
    }

    @Test
    void specialMountsExposeTheirRuntimeContracts() throws Exception {
        GenerationResult controllerJavax = generate(
                GeneratorCatalog.resolve("SpringWebMVC", "ControllerHandlerInjector", "http"),
                10001L, ServletNamespace.JAVAX);
        assertContract(controllerJavax.getShellClassBytes(),
                "org.springframework.web.servlet.mvc.Controller",
                "handleRequest",
                "(Ljavax/servlet/http/HttpServletRequest;Ljavax/servlet/http/HttpServletResponse;)Lorg/springframework/web/servlet/ModelAndView;");

        GenerationResult controllerJakarta = generate(
                GeneratorCatalog.resolve("SpringWebMVC", "ControllerHandlerInjector", "http"),
                10002L, ServletNamespace.JAKARTA);
        assertContract(controllerJakarta.getShellClassBytes(),
                "org.springframework.web.servlet.mvc.Controller",
                "handleRequest",
                "(Ljakarta/servlet/http/HttpServletRequest;Ljakarta/servlet/http/HttpServletResponse;)Lorg/springframework/web/servlet/ModelAndView;");

        GenerationResult customizer = generate(
                GeneratorCatalog.resolve("Jetty", "CustomizerInjector", "http"),
                10003L, ServletNamespace.JAVAX);
        assertContract(customizer.getShellClassBytes(),
                "org.eclipse.jetty.server.HttpConfiguration$Customizer",
                "customize",
                "(Lorg/eclipse/jetty/server/Connector;Lorg/eclipse/jetty/server/HttpConfiguration;Lorg/eclipse/jetty/server/Request;)V");

        GenerationResult handler = generate(
                GeneratorCatalog.resolve("Jetty", "HandlerInjector", "http"),
                10004L, ServletNamespace.JAVAX, "7-10");
        ClassFile handlerClass = new ClassFile(new DataInputStream(
                new ByteArrayInputStream(handler.getShellClassBytes())));
        assertEquals("org.eclipse.jetty.server.handler.AbstractHandler",
                handlerClass.getSuperclass());
        assertTrue(handlerClass.getMethods().stream().map(MethodInfo.class::cast)
                .anyMatch(method -> "handle".equals(method.getName())
                        && "(Ljava/lang/String;Lorg/eclipse/jetty/server/Request;"
                        .concat("Ljavax/servlet/http/HttpServletRequest;")
                        .concat("Ljavax/servlet/http/HttpServletResponse;)V")
                        .equals(method.getDescriptor())));

        GenerationResult webSocketByPass = generate(
                GeneratorCatalog.resolve("Tomcat",
                        "ByPassNginxWebSocketInjector", "websocket"),
                10005L, ServletNamespace.JAVAX);
        ClassFile byPassInjector = new ClassFile(new DataInputStream(
                new ByteArrayInputStream(webSocketByPass.getInjectorClassBytes())));
        assertTrue(Arrays.asList(byPassInjector.getInterfaces())
                .contains("java.lang.reflect.InvocationHandler"));
        assertTrue(new String(webSocketByPass.getInjectorClassBytes(), "ISO-8859-1")
                .contains("X-Catalog-Test"));

        GenerationResult upgrade = generate(
                GeneratorCatalog.resolve("Tomcat", "UpgradeInjector", "http"),
                10006L, ServletNamespace.JAVAX);
        assertContract(upgrade.getShellClassBytes(),
                "org.apache.coyote.UpgradeProtocol",
                "accept", "(Lorg/apache/coyote/Request;)Z");
        ClassFile upgradeClass = new ClassFile(new DataInputStream(
                new ByteArrayInputStream(upgrade.getShellClassBytes())));
        assertTrue(upgradeClass.getMethods().stream().map(MethodInfo.class::cast)
                .anyMatch(method -> "getInternalUpgradeHandler".equals(method.getName())
                        && "(Lorg/apache/coyote/Adapter;Lorg/apache/coyote/Request;)"
                        .concat("Lorg/apache/coyote/http11/upgrade/InternalHttpUpgradeHandler;")
                        .equals(method.getDescriptor())));
        assertTrue(upgradeClass.getMethods().stream().map(MethodInfo.class::cast)
                .anyMatch(method -> "getInternalUpgradeHandler".equals(method.getName())
                        && "(Lorg/apache/tomcat/util/net/SocketWrapperBase;"
                        .concat("Lorg/apache/coyote/Adapter;Lorg/apache/coyote/Request;)")
                        .concat("Lorg/apache/coyote/http11/upgrade/InternalHttpUpgradeHandler;")
                        .equals(method.getDescriptor())));

        assertTongWebValveContract("6", "com.tongweb.web.thor");
        assertTongWebValveContract("7", "com.tongweb.catalina");
        assertTongWebValveContract("8", "com.tongweb.server");

        assertTongWebAgentContract("AgentFilterChain", "doFilter");
        assertTongWebAgentContract("AgentContextValve", "invoke");

    }

    @Test
    void tomcatUpgradeExecutesHeaderGateAndKeepsConfiguredResponseCode()
            throws Exception {
        Disguise request = new Disguise();
        request.setTrafficDecodeBody(
                "public byte[] decodeTraffic(byte[] data){return data;}");
        Disguise response = new Disguise();
        response.setTrafficEncodeBody(
                "public byte[] encodeTraffic(byte[] data){return data;}");
        ShellGeneratorConfig config = ShellGeneratorConfig.builder(request, response)
                .payloadKey("catalog-test-key")
                .serverType("Tomcat")
                .shellType("UpgradeInjector")
                .protocol("http")
                .packerType("DefaultBase64")
                .header("X-Upgrade-Test", "enabled")
                .respCode(299)
                .obfuscationSeed(10007L)
                .build();
        GenerationResult result = new ShellGenerator(GenerationRequest.from(config))
                .generateFormattedInjector();

        Class<?> shellType = new ByteArrayClassLoader(getClass().getClassLoader())
                .define(result.getShellClassBytes());
        Object shell = shellType.newInstance();
        UpgradeResponse upgradeResponse = new UpgradeResponse();
        UpgradeRequest servletRequest = new UpgradeRequest(upgradeResponse, "enabled");
        org.apache.coyote.Request coyoteRequest = new org.apache.coyote.Request();
        coyoteRequest.setNote(1, servletRequest);

        Object accepted = shellType
                .getMethod("accept", org.apache.coyote.Request.class)
                .invoke(shell, coyoteRequest);

        // Shell 已写回响应；返回 false 阻止 Tomcat 继续切换到空的 101 Handler。
        assertEquals(Boolean.FALSE, accepted);
        assertEquals(299, upgradeResponse.status);

        UpgradeResponse rejectedResponse = new UpgradeResponse();
        org.apache.coyote.Request rejectedRequest = new org.apache.coyote.Request();
        rejectedRequest.setNote(1,
                new UpgradeRequest(rejectedResponse, "disabled"));
        assertEquals(Boolean.FALSE, shellType
                .getMethod("accept", org.apache.coyote.Request.class)
                .invoke(shell, rejectedRequest));
        assertEquals(0, rejectedResponse.status);
    }

    public static final class UpgradeRequest {
        private final UpgradeResponse response;
        private final String headerValue;

        UpgradeRequest(UpgradeResponse response, String headerValue) {
            this.response = response;
            this.headerValue = headerValue;
        }

        public String getHeader(String name) {
            return "X-Upgrade-Test".equals(name) ? headerValue : null;
        }

        public InputStream getInputStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        public UpgradeResponse getResponse() {
            return response;
        }
    }

    public static final class UpgradeResponse {
        private int status;
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();

        public void setStatus(int status) {
            this.status = status;
        }

        public ByteArrayOutputStream getOutputStream() {
            return output;
        }
    }

    private static final class ByteArrayClassLoader extends ClassLoader {
        ByteArrayClassLoader(ClassLoader parent) {
            super(parent);
        }

        Class<?> define(byte[] bytes) {
            return defineClass(null, bytes, 0, bytes.length);
        }
    }

    private static void assertTongWebAgentContract(String injectorName,
                                                    String targetMethod) throws Exception {
        InjectorDescriptor descriptor = GeneratorCatalog.resolve(
                "TongWeb", injectorName, "http");
        GenerationResult result = generate(descriptor, 13000L, ServletNamespace.JAVAX);
        ClassFile injector = new ClassFile(new DataInputStream(
                new ByteArrayInputStream(result.getInjectorClassBytes())));
        assertTrue(injector.getMethods().stream().map(MethodInfo.class::cast)
                .anyMatch(method -> "premain".equals(method.getName())));
        assertTrue(injector.getMethods().stream().map(MethodInfo.class::cast)
                .anyMatch(method -> "agentmain".equals(method.getName())));
        assertTrue(new String(result.getInjectorClassBytes(), "ISO-8859-1")
                .contains(targetMethod));

        JarInputStream jar = new JarInputStream(new ByteArrayInputStream(
                Base64.getDecoder().decode(result.getContent())));
        assertEquals(result.getInjectorClassName(),
                jar.getManifest().getMainAttributes().getValue("Agent-Class"));
        assertEquals(result.getInjectorClassName(),
                jar.getManifest().getMainAttributes().getValue("Premain-Class"));
        assertEquals("true", jar.getManifest().getMainAttributes()
                .getValue("Can-Retransform-Classes"));
        jar.close();
    }

    private static void assertTongWebValveContract(String serverVersion,
                                                   String valvePackage) throws Exception {
        for (String protocol : Arrays.asList("http", "httpchunk")) {
            InjectorDescriptor descriptor = GeneratorCatalog.resolve(
                    "TongWeb", "ValveInjector", protocol);
            GenerationResult result = generate(
                    descriptor, 11000L + Integer.parseInt(serverVersion),
                    ServletNamespace.JAVAX, serverVersion);
            String internal = valvePackage.replace('.', '/');
            assertContract(result.getShellClassBytes(),
                    valvePackage + ".Valve",
                    "invoke",
                    "(L" + internal + "/connector/Request;L"
                            + internal + "/connector/Response;)V");
        }
    }

    private static GenerationResult generate(InjectorDescriptor descriptor,
                                             long seed) throws Exception {
        return generate(descriptor, seed, ServletNamespace.JAVAX);
    }

    private static GenerationResult generate(InjectorDescriptor descriptor,
                                             long seed,
                                             ServletNamespace namespace) throws Exception {
        return generate(descriptor, seed, namespace,
                descriptor.requiresServerVersion()
                        ? descriptor.getSupportedServerVersions().get(0) : null);
    }

    private static GenerationResult generate(InjectorDescriptor descriptor,
                                             long seed,
                                             ServletNamespace namespace,
                                             String serverVersion) throws Exception {
        Disguise request = new Disguise();
        request.setTrafficDecodeBody(
                "public byte[] decodeTraffic(byte[] data){return data;}");
        Disguise response = new Disguise();
        response.setTrafficEncodeBody(
                "public byte[] encodeTraffic(byte[] data){return data;}");

        ShellGeneratorConfig.Builder builder = ShellGeneratorConfig.builder(request, response)
                .payloadKey("catalog-test-key")
                .serverType(descriptor.getServerType().getValue())
                .shellType(descriptor.getInjectorName())
                .protocol(descriptor.getProtocol().getValue())
                .packerType("DefaultBase64")
                .servletNamespace(namespace)
                .obfuscationSeed(seed);
        if (!descriptor.getSupportedPackers().isEmpty()) {
            builder.packerType(descriptor.getSupportedPackers().get(0));
        }
        if (serverVersion != null) {
            builder.serverVersion(serverVersion);
        }
        if (descriptor.getProtocol() == TransportProtocol.WEBSOCKET) {
            builder.urlPattern("/catalog-smoke")
                    .header("X-Catalog-Test", "enabled");
        } else {
            builder.header("X-Catalog-Test", "enabled")
                    .urlPattern("/*");
        }
        return new ShellGenerator(GenerationRequest.from(builder.build()))
                .generateFormattedInjector();
    }

    private static void assertContract(byte[] bytes,
                                       String interfaceName,
                                       String methodName,
                                       String descriptor) throws Exception {
        ClassFile classFile = new ClassFile(
                new DataInputStream(new ByteArrayInputStream(bytes)));
        assertTrue(Arrays.asList(classFile.getInterfaces()).contains(interfaceName),
                "缺少运行时接口: " + interfaceName);
        assertTrue(classFile.getMethods().stream()
                        .map(MethodInfo.class::cast)
                        .anyMatch(method -> methodName.equals(method.getName())
                                && descriptor.equals(method.getDescriptor())),
                "缺少运行时方法: " + methodName + descriptor);
    }

    private static boolean isGlobalMount(MountType mountType) {
        return mountType == MountType.LISTENER
                || mountType == MountType.VALVE
                || mountType == MountType.AGENT_FILTER_CHAIN
                || mountType == MountType.AGENT_CONTEXT_VALVE
                || mountType == MountType.AGENT_HANDLER
                || mountType == MountType.AGENT_SERVLET_HANDLER
                || mountType == MountType.AGENT_FILTER_MANAGER
                || mountType == MountType.AGENT_SERVLET_CONTEXT
                || mountType == MountType.AGENT_FRAMEWORK_SERVLET
                || mountType == MountType.INTERCEPTOR
                || mountType == MountType.CUSTOMIZER
                || mountType == MountType.HANDLER
                || mountType == MountType.UPGRADE;
    }

    private static void assertFieldAbsent(byte[] bytes, String fieldName) throws Exception {
        ClassFile classFile = new ClassFile(
                new DataInputStream(new ByteArrayInputStream(bytes)));
        assertTrue(classFile.getFields().stream()
                        .noneMatch(field -> fieldName.equals(
                                ((javassist.bytecode.FieldInfo) field).getName())),
                "生成类包含无用字段: " + fieldName);
    }

    private static String label(InjectorDescriptor descriptor) {
        return descriptor.getServerType().getValue() + " / "
                + descriptor.getProtocol().getValue() + " / "
                + descriptor.getInjectorName();
    }
}
