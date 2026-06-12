package org.leo.core.util.javassist;

import javassist.*;
import javassist.expr.ExprEditor;
import javassist.expr.FieldAccess;
import javassist.expr.MethodCall;
import org.leo.core.util.request.ClassNameGenerator;

import java.io.InputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class CloneWithJavassist {

    private static final String COMPONENT_PREFIX = "component/";

    public static byte[] cloneClass(String componentName, String newClassName) throws Exception {
        String resourcePath = COMPONENT_PREFIX + componentName + ".payload";
        InputStream is = openResource(resourcePath);
        if (is == null) {
            throw new RuntimeException("Cannot find class bytes for " + componentName + " at classpath:" + resourcePath);
        }

        try (InputStream in = is) {
            ClassPool pool = ClassPool.getDefault();
            CtClass cc = pool.makeClass(in);
            cc.setName(newClassName);
            cc.getClassFile().setVersionToJava5();
            randomizeNames(cc);
            try {
                return cc.toBytecode();
            } finally {
                cc.detach();
            }
        }
    }

    /**
     * 对 Component 字节码做方法名和字段名随机化：
     *
     * 不能随机化：
     *   - run()        Runnable 接口约束，LeoCore 通过 ((Runnable)obj).run() 调用
     *   - params       run() 模板体直接引用，随机化需同时重写 run() 体，暂跳过
     *   - results      同上
     *
     * 可以随机化：
     *   - invoke() 及所有其他方法（setName 改声明，ExprEditor 更新调用点）
     *   - 除 params/results 之外的所有字段（setName 改声明，ExprEditor 更新访问点）
     */
    private static void randomizeNames(final CtClass cc) throws Exception {
        final Set<String> used = new HashSet<String>();
        used.add("run"); // 保护 Runnable 接口方法

        // ── 预保护：收集所有接口方法名，不能重命名（否则接口契约被破坏，如 InvocationHandler.invoke）──
        final Set<String> interfaceMethodNames = new HashSet<String>();
        try {
            for (CtClass iface : cc.getInterfaces()) {
                for (CtMethod im : iface.getMethods()) {
                    interfaceMethodNames.add(im.getName());
                }
            }
        } catch (Exception ignored) {
            // getInterfaces / getMethods 可能因类型解析失败抛异常，保守跳过即可
        }

        // ── Step 1: 收集方法重命名映射（rename 前先记录 void/static，避免后续 getMethod() 失败）──
        final Map<String, String>  methodRenames  = new HashMap<String, String>();
        final Map<String, Boolean> methodIsVoid   = new HashMap<String, Boolean>();
        final Map<String, Boolean> methodIsStatic = new HashMap<String, Boolean>();

        CtMethod[] declaredMethods = cc.getDeclaredMethods();
        for (CtMethod m : declaredMethods) {
            String name = m.getName();
            if (name.equals("run")) continue;
            // 接口约束方法（如 InvocationHandler.invoke）不能重命名，否则代理调用时抛 AbstractMethodError
            if (interfaceMethodNames.contains(name)) continue;
            // 同名重载共享同一个新名字，保持重载关系
            if (!methodRenames.containsKey(name)) {
                String newName = ClassNameGenerator.randomMethodName(used);
                methodRenames.put(name, newName);
                methodIsVoid.put(name,   m.getReturnType() == CtClass.voidType);
                methodIsStatic.put(name, Modifier.isStatic(m.getModifiers()));
            }
            m.setName(methodRenames.get(name));
        }

        // ── Step 2: 收集字段重命名映射（全字段，含 params/results）──────────────────
        // run() 体内对 params/results 的访问由 Step 4 的 ExprEditor 同步更新，无需跳过
        final Map<String, String> fieldRenames = new HashMap<String, String>();
        for (CtField f : cc.getDeclaredFields()) {
            String name = f.getName();
            String newName = ClassNameGenerator.randomFieldName(used);
            fieldRenames.put(name, newName);
            f.setName(newName);
        }

        // ── Step 3: 更新方法调用点（含 run() 体内的 invoke() 调用）───────────────────
        // 只处理对本类方法的调用，过滤掉 JDK/外部类（如 Method.invoke）
        final ExprEditor methodEditor = new ExprEditor() {
            public void edit(MethodCall mc) throws CannotCompileException {
                if (!cc.getName().equals(mc.getClassName())) return;
                String newName = methodRenames.get(mc.getMethodName());
                if (newName == null) return;
                boolean isVoid   = Boolean.TRUE.equals(methodIsVoid.get(mc.getMethodName()));
                boolean isStatic = Boolean.TRUE.equals(methodIsStatic.get(mc.getMethodName()));
                if (isStatic) {
                    mc.replace(isVoid ? "{ " + newName + "($$); }"
                                      : "{ $_ = " + newName + "($$); }");
                } else {
                    mc.replace(isVoid ? "{ $0." + newName + "($$); }"
                                      : "{ $_ = $0." + newName + "($$); }");
                }
            }
        };

        for (CtMethod m : cc.getDeclaredMethods()) {
            m.instrument(methodEditor);
        }
        for (CtConstructor c : cc.getDeclaredConstructors()) {
            c.instrument(methodEditor);
        }

        // ── Step 4: 更新字段访问点（含 <clinit> 静态初始化块）───────────────────────
        if (fieldRenames.isEmpty()) return;

        final ExprEditor fieldEditor = new ExprEditor() {
            public void edit(FieldAccess fa) throws CannotCompileException {
                if (!cc.getName().equals(fa.getClassName())) return;
                String newName = fieldRenames.get(fa.getFieldName());
                if (newName == null) return;
                if (fa.isReader()) {
                    fa.replace("{ $_ = " + newName + "; }");
                } else {
                    fa.replace("{ " + newName + " = $1; }");
                }
            }
        };

        for (CtMethod m : cc.getDeclaredMethods()) {
            m.instrument(fieldEditor);
        }
        for (CtConstructor c : cc.getDeclaredConstructors()) {
            c.instrument(fieldEditor);
        }
        CtConstructor clinit = cc.getClassInitializer();
        if (clinit != null) {
            clinit.instrument(fieldEditor);
        }
    }

    private static InputStream openResource(String resourcePath) {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl != null) {
            InputStream s = cl.getResourceAsStream(resourcePath);
            if (s != null) return s;
        }
        cl = CloneWithJavassist.class.getClassLoader();
        if (cl != null) {
            InputStream s = cl.getResourceAsStream(resourcePath);
            if (s != null) return s;
        }
        cl = ClassLoader.getSystemClassLoader();
        return cl != null ? cl.getResourceAsStream(resourcePath) : null;
    }
}
