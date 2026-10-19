package org.leo.core.util.javassist;

import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtField;
import javassist.CtNewConstructor;
import javassist.CtNewMethod;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CloneWithJavassistTest {

    @Test
    void preservesTheReceiverWhenRewritingPeerInstanceFields() throws Exception {
        String className = "org.leo.generated.PeerFields" + System.nanoTime();
        ClassPool pool = new ClassPool(true);
        CtClass generated = pool.makeClass(className);
        generated.addInterface(pool.get(Runnable.class.getName()));
        generated.addConstructor(CtNewConstructor.defaultConstructor(generated));
        CtField value = CtField.make("private int value;", generated);
        CtField captured = CtField.make("private int captured;", generated);
        CtField peer = CtField.make("private " + className + " peer;", generated);
        generated.addField(value);
        generated.addField(captured);
        generated.addField(peer);
        generated.addMethod(CtNewMethod.make("public void run() { "
                + className + " other = new " + className + "();"
                + "value = 7; other.value = 42; captured = other.value;"
                + "other.value++; peer = other; }", generated));
        byte[] bytecode;
        String valueName, capturedName, peerName;
        try {
            CloneWithJavassist.randomizeNames(generated);
            valueName = value.getName();
            capturedName = captured.getName();
            peerName = peer.getName();
            bytecode = generated.toBytecode();
        } finally {
            generated.detach();
        }
        Class<?> transformed = new BytecodeLoader().define(className, bytecode);
        Runnable instance = (Runnable) transformed.getDeclaredConstructor().newInstance();
        instance.run();
        Field valueField = transformed.getDeclaredField(valueName);
        Field capturedField = transformed.getDeclaredField(capturedName);
        Field peerField = transformed.getDeclaredField(peerName);
        valueField.setAccessible(true);
        capturedField.setAccessible(true);
        peerField.setAccessible(true);
        assertEquals(7, valueField.get(instance));
        assertEquals(42, capturedField.get(instance));
        assertEquals(43, valueField.get(peerField.get(instance)));
    }

    @Test
    void rewritesSelfMethodCallsInsideStaticInitializer() throws Exception {
        String className = "org.leo.generated.StaticInit" + System.nanoTime();
        ClassPool pool = new ClassPool(true);
        CtClass generated = pool.makeClass(className);
        generated.addField(CtField.make("private static String marker = createMarker();", generated));
        generated.addMethod(CtNewMethod.make(
                "private static String createMarker() { return \"static-init-ok\"; }", generated));

        byte[] originalBytecode;
        try {
            originalBytecode = generated.toBytecode();
        } finally {
            generated.detach();
        }

        CtClass transformedClass = pool.makeClass(new ByteArrayInputStream(originalBytecode));
        byte[] bytecode;
        try {
            CloneWithJavassist.randomizeNames(transformedClass);
            bytecode = transformedClass.toBytecode();
        } finally {
            transformedClass.detach();
        }

        Class<?> transformed = new BytecodeLoader().define(className, bytecode);
        Field marker = transformed.getDeclaredFields()[0];
        marker.setAccessible(true);
        assertEquals("static-init-ok", marker.get(null));
    }

    private static final class BytecodeLoader extends ClassLoader {
        private Class<?> define(String name, byte[] bytecode) {
            return defineClass(name, bytecode, 0, bytecode.length);
        }
    }
}
