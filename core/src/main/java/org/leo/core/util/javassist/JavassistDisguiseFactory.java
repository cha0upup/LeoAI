package org.leo.core.util.javassist;


import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtNewMethod;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicLong;

public final class JavassistDisguiseFactory {

    private static final AtomicLong CLASS_SEQUENCE = new AtomicLong();

    private JavassistDisguiseFactory() {
    }

    /** Creates a wire-only adapter. Methods must be encodeTraffic/decodeTraffic(byte[]). */
    public static Class<?> createTrafficDisguiseClass(String trafficEncodeBody, String trafficDecodeBody) throws Exception {
        ClassPool pool = ClassPool.getDefault();
        String tempClassName = buildTempClassName();
        CtClass cc = pool.makeClass(tempClassName);
        cc.addMethod(CtNewMethod.make(trafficEncodeBody, cc));
        cc.addMethod(CtNewMethod.make(trafficDecodeBody, cc));
        try {
            return cc.toClass(JavassistDisguiseFactory.class);
        } finally {
            cc.detach();
        }
    }
    /** Validate a traffic-only adapter without ever interpreting payload bytes. */
    public static boolean testTrafficDisguise(String trafficEncodeBody, String trafficDecodeBody) throws Exception {
        byte[] sample = new byte[]{0, 1, 2, 3, 7, 13, 42, (byte) 0xff};
        ClassPool pool = ClassPool.getDefault();
        String tempClassName = buildTempClassName();
        CtClass cc = pool.makeClass(tempClassName);
        cc.addMethod(CtNewMethod.make(trafficEncodeBody, cc));
        cc.addMethod(CtNewMethod.make(trafficDecodeBody, cc));
        Class<?> tempClass;
        try {
            tempClass = cc.toClass(JavassistDisguiseFactory.class);
        } finally {
            cc.detach();
        }
        Object instance = tempClass.getDeclaredConstructor().newInstance();
        Method encode = tempClass.getMethod("encodeTraffic", byte[].class);
        Method decode = tempClass.getMethod("decodeTraffic", byte[].class);
        byte[] encoded = (byte[]) encode.invoke(instance, sample);
        byte[] decoded = (byte[]) decode.invoke(instance, encoded);
        return java.util.Arrays.equals(sample, decoded);
    }


    private static String buildTempClassName() {
        return JavassistDisguiseFactory.class.getPackageName()
                + ".GeneratedDisguise_" + CLASS_SEQUENCE.incrementAndGet();
    }

}
