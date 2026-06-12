package org.leo.core.util.javassist;


import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtNewMethod;

import java.lang.reflect.Method;
import java.util.HashMap;

public class JavassistDisguiseFactory {

    public static byte[] createDisguiseBytecode(String encodeBody, String decodeBody) throws Exception {

        ClassPool pool = ClassPool.getDefault();

        String tempClassName = buildTempClassName();
        CtClass cc = pool.makeClass(tempClassName);

        // encode 方法
        cc.addMethod(CtNewMethod.make(encodeBody, cc));

        // decode 方法
        cc.addMethod(CtNewMethod.make(decodeBody, cc));

        byte[] bytecode = cc.toBytecode();
        cc.detach();
        return bytecode;
    }
    public static Class createDisguiseClass(String encodeBody, String decodeBody) throws Exception {

        ClassPool pool = ClassPool.getDefault();

        String tempClassName = buildTempClassName();
        CtClass cc = pool.makeClass(tempClassName);

        // encode 方法
        cc.addMethod(CtNewMethod.make(encodeBody, cc));

        // decode 方法
        cc.addMethod(CtNewMethod.make(decodeBody, cc));


        cc.detach();
        return cc.toClass();
    }
    public static boolean testDisguise(String encodeBody, String decodeBody) throws Exception {
        HashMap<String, Object> testHashMap = new HashMap<String, Object>();
        testHashMap.put("testString", "54ikun");
        ClassPool pool = ClassPool.getDefault();

        String tempClassName = buildTempClassName();
        CtClass cc = pool.makeClass(tempClassName);

        // encode 方法
        cc.addMethod(CtNewMethod.make(encodeBody, cc));

        // decode 方法
        cc.addMethod(CtNewMethod.make(decodeBody, cc));
        Class tempClass = cc.toClass();
        Object tempObject = tempClass.newInstance();

        Method encode = tempClass.getMethod("encode", new Class[]{HashMap.class});
        Method decode = tempClass.getMethod("decode", new Class[]{byte[].class});

        encode.setAccessible(true);
        decode.setAccessible(true);
        byte[] encodeByte = (byte[]) encode.invoke(tempObject, new Object[]{testHashMap});
        @SuppressWarnings("unchecked")
        HashMap<String, Object> decodeHashMap = (HashMap<String, Object>) decode.invoke(tempObject, new Object[]{encodeByte});

        return testHashMap.equals(decodeHashMap);
    }


    private static String buildTempClassName() {
        return "org.leo.disguise.dynamic." + System.currentTimeMillis();
    }

}
