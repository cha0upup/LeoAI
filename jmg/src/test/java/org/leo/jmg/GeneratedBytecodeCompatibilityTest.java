package org.leo.jmg;

import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtNewMethod;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.leo.core.entity.Disguise;
import org.leo.core.util.asm.ClassFileMinimizer;
import org.leo.jmg.core.LeoCore;
import org.leo.jmg.generation.GenerationRequest;
import org.leo.jmg.generation.GenerationResult;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeneratedBytecodeCompatibilityTest {

    private static final int JAVA_5_CLASS_MAJOR = 49;

    @Test
    void coreShellAndInjectorStayLoadableBySupportedJvms() throws Exception {
        GenerationResult result = new ShellGenerator(
                GenerationRequest.from(createConfig()))
                .generateFormattedInjector();

        assertSupportedClassFile(result.getCoreClassBytes(), "LeoCore");
        assertSupportedClassFile(result.getShellClassBytes(), "Shell");
        assertSupportedClassFile(result.getInjectorClassBytes(), "Injector");
    }

    @Test
    void generatedInjectorPassesJvmVerificationWithoutInitialization() throws Exception {
        GenerationResult result = new ShellGenerator(
                GenerationRequest.from(createConfig()))
                .generateFormattedInjector();

        Class<?> injectorClass = new ByteArrayClassLoader()
                .defineAndResolve(result.getInjectorClassBytes());

        assertEquals("org.example.Java6Injector", injectorClass.getName());
        injectorClass.getDeclaredConstructor();
    }

    @Test
    void generatedCoreLoadsInConfiguredCompatibilityJvm(@TempDir Path tempDir) throws Exception {
        String javaExecutable = System.getProperty("jmg.compat.java");
        Assumptions.assumeTrue(javaExecutable != null && !javaExecutable.trim().isEmpty(),
                "通过 -Djmg.compat.java=/path/to/java 启用指定 JDK 验证");

        ShellGeneratorConfig config = createConfig();
        byte[] core = new LeoCore(config.getReqDisguise(), config.getRespDisguise(),
                config.getPayloadKey())
                .genLeoCoreByClassName(config.getCoreClassName(), config);
        core = ClassFileMinimizer.transform(core);

        writeClass(tempDir, config.getCoreClassName(), core);
        writeClass(tempDir, "org.example.Java6Verifier", createJava6Verifier());

        Process process = new ProcessBuilder(
                javaExecutable,
                "-Xverify:all",
                "-cp",
                tempDir.toString(),
                "org.example.Java6Verifier",
                config.getCoreClassName()
        ).redirectErrorStream(true).start();
        byte[] output = readAll(process.getInputStream());
        int exitCode = process.waitFor();

        assertEquals(0, exitCode,
                "兼容性 JDK 加载生成类失败: " + new String(output, StandardCharsets.UTF_8));
        assertEquals("OK", new String(output, StandardCharsets.UTF_8));
    }

    @Test
    void jakartaNamespaceRemapsShellAndInjectorTypeReferences() throws Exception {
        GenerationResult result = new ShellGenerator(GenerationRequest.from(
                createConfig(ServletNamespace.JAKARTA, "Undertow")))
                .generateFormattedInjector();
        byte[] shell = result.getShellClassBytes();
        String shellConstants = new String(shell, StandardCharsets.ISO_8859_1);
        assertFalse(shellConstants.contains("javax/servlet"));
        assertTrue(shellConstants.contains("jakarta/servlet"));
        Class<?> shellClass = new ByteArrayClassLoader().define(shell);
        assertTrue(Filter.class.isAssignableFrom(shellClass));

        byte[] injector = result.getInjectorClassBytes();
        String injectorConstants = new String(injector, StandardCharsets.ISO_8859_1);
        assertFalse(injectorConstants.contains("javax/servlet/DispatcherType"));
        assertTrue(injectorConstants.contains("jakarta/servlet/DispatcherType"));
    }

    @Test
    void jakartaNamespaceRemapsWebSocketEndpointReferences() throws Exception {
        ShellGeneratorConfig config = createConfig(ServletNamespace.JAKARTA);
        ShellGeneratorConfig websocketConfig = ShellGeneratorConfig
                .builder(config.getReqDisguise(), config.getRespDisguise())
                .coreClassName(config.getCoreClassName())
                .shellClassName("org.example.JakartaWebSocket")
                .injectorClassName("org.example.JakartaWebSocketInjector")
                .serverType("Tomcat")
                .shellType("WebSocketInjector")
                .packerType("DefaultBase64")
                .protocol("websocket")
                .urlPattern("/socket")
                .servletNamespace(ServletNamespace.JAKARTA)
                .build();

        byte[] shell = new ShellGenerator(GenerationRequest.from(websocketConfig))
                .generateFormattedInjector()
                .getShellClassBytes();
        String constants = new String(shell, StandardCharsets.ISO_8859_1);
        assertFalse(constants.contains("javax/websocket"));
        assertTrue(constants.contains("jakarta/websocket"));
    }

    /**
     * 回归测试：ClassFileMinimizer.transform() 必须保留泛型 Signature 属性。
     * <p>
     * Tomcat 8.5 的 Util.getGenericType() 通过 Class.getGenericInterfaces() 推断
     * MessageHandler.Whole&lt;ByteBuffer&gt; 的类型参数。若 Signature 被剥离，
     * getGenericInterfaces() 返回原始 Class 而非 ParameterizedType，
     * Tomcat 得到 null 并抛出 NullPointerException，导致 WebSocket 连接 1006 断开。
     */
    @Test
    void webSocketShellPreservesGenericSignatureAfterMinimization() throws Exception {
        ShellGeneratorConfig base = createConfig(ServletNamespace.AUTO);
        ShellGeneratorConfig websocketConfig = ShellGeneratorConfig
                .builder(base.getReqDisguise(), base.getRespDisguise())
                .coreClassName(base.getCoreClassName())
                .shellClassName("org.example.WebSocketSigShell")
                .injectorClassName("org.example.WebSocketSigInjector")
                .serverType("Tomcat")
                .shellType("WebSocketInjector")
                .packerType("DefaultBase64")
                .protocol("websocket")
                .urlPattern("/sig")
                .servletNamespace(ServletNamespace.AUTO)
                .build();

        byte[] shell = new ShellGenerator(GenerationRequest.from(websocketConfig))
                .generateFormattedInjector()
                .getShellClassBytes();

        Class<?> shellClass = new ByteArrayClassLoader().define(shell);
        assertTrue(javax.websocket.Endpoint.class.isAssignableFrom(shellClass),
                "WebSocket Shell 必须继承 javax.websocket.Endpoint");

        boolean foundParameterized = false;
        for (java.lang.reflect.Type type : shellClass.getGenericInterfaces()) {
            if (type instanceof java.lang.reflect.ParameterizedType) {
                java.lang.reflect.ParameterizedType pt =
                        (java.lang.reflect.ParameterizedType) type;
                if (pt.getRawType() == javax.websocket.MessageHandler.Whole.class) {
                    assertEquals(java.nio.ByteBuffer.class,
                            pt.getActualTypeArguments()[0],
                            "MessageHandler.Whole 的类型参数必须是 ByteBuffer");
                    foundParameterized = true;
                }
            }
        }
        assertTrue(foundParameterized,
                "WebSocket Shell 必须保留 MessageHandler.Whole<ByteBuffer> 泛型签名");
    }

    private static void assertSupportedClassFile(byte[] classBytes, String label) {
        assertEquals(JAVA_5_CLASS_MAJOR, majorVersion(classBytes),
                label + " 字节码版本必须兼容 JDK 6/7/8");
        assertFalse(new String(classBytes, StandardCharsets.ISO_8859_1)
                        .contains("java/util/Base64"),
                label + " 不应直接链接仅 JDK 8 提供的 Base64 API");
    }

    private static int majorVersion(byte[] classBytes) {
        return ((classBytes[6] & 0xff) << 8) | (classBytes[7] & 0xff);
    }

    private static byte[] createJava6Verifier() throws Exception {
        ClassPool pool = new ClassPool(true);
        CtClass verifier = pool.makeClass("org.example.Java6Verifier");
        verifier.getClassFile().setVersionToJava5();
        verifier.addMethod(CtNewMethod.make(
                "public static void main(String[] args) throws Exception {"
                        + "Class.forName(args[0],true,Thread.currentThread().getContextClassLoader());"
                        + "System.out.print(\"OK\");}",
                verifier
        ));
        try {
            return ClassFileMinimizer.transform(verifier.toBytecode());
        } finally {
            verifier.detach();
        }
    }

    private static void writeClass(Path root, String className, byte[] bytes) throws Exception {
        Path target = root.resolve(className.replace('.', '/') + ".class");
        Files.createDirectories(target.getParent());
        Files.write(target, bytes);
    }

    private static byte[] readAll(java.io.InputStream input) throws Exception {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int length;
        while ((length = input.read(buffer)) != -1) {
            output.write(buffer, 0, length);
        }
        return output.toByteArray();
    }

    private static ShellGeneratorConfig createConfig() {
        return createConfig(ServletNamespace.AUTO);
    }

    private static ShellGeneratorConfig createConfig(ServletNamespace servletNamespace) {
        return createConfig(servletNamespace, "Tomcat");
    }

    private static ShellGeneratorConfig createConfig(ServletNamespace servletNamespace,
                                                     String serverType) {
        Disguise request = new Disguise();
        request.setTrafficDecodeBody(
                "public byte[] decodeTraffic(byte[] data){return data;}"
        );

        Disguise response = new Disguise();
        response.setTrafficEncodeBody(
                "public byte[] encodeTraffic(byte[] data){return data;}"
        );

        return ShellGeneratorConfig.builder(request, response)
                .payloadKey("compatibility-test-key")
                .coreClassName("org.example.Java6Core")
                .shellClassName("org.example.Java6Filter")
                .injectorClassName("org.example.Java6Injector")
                .header("X-Test", "fixture")
                .serverType(serverType)
                .shellType("FilterInjector")
                .packerType("DefaultBase64")
                .servletNamespace(servletNamespace)
                // 固定种子确保生成符号与 Javassist 局部变量保持唯一。
                .obfuscationSeed(-2840755419257969001L)
                .build();
    }

    private static final class ByteArrayClassLoader extends ClassLoader {
        private Class<?> define(byte[] bytes) {
            return defineClass(null, bytes, 0, bytes.length);
        }

        private Class<?> defineAndResolve(byte[] bytes) {
            Class<?> type = define(bytes);
            resolveClass(type);
            return type;
        }
    }
}
