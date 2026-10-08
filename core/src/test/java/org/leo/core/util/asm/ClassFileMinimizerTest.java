package org.leo.core.util.asm;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClassFileMinimizerTest {

    @Test
    void removesOptionalClassFileMetadata_butPreservesGenericSignatures() {
        byte[] minimized = ClassFileMinimizer.transform(sampleClass());
        MetadataProbe probe = new MetadataProbe();
        new ClassReader(minimized).accept(probe, 0);

        // 以下元数据仍然移除
        assertFalse(probe.source);
        assertFalse(probe.outerClass);
        assertFalse(probe.innerClass);
        assertFalse(probe.methodParameters);

        // 泛型 Signature 与 Exceptions 必须保留：运行时反射
        // (如 Class.getGenericInterfaces / Method.getGenericParameterTypes)
        // 依赖 Signature 解析参数化类型。Tomcat WebSocket 的
        // Util.getGenericType() 会通过它推断 MessageHandler.Whole<ByteBuffer>
        // 的类型参数，剥离后会导致 NullPointerException 并断开连接。
        assertTrue(probe.classSignature, "类级泛型签名必须保留");
        assertTrue(probe.fieldSignature, "字段泛型签名必须保留");
        assertTrue(probe.methodSignature, "方法泛型签名必须保留");
        assertTrue(probe.exceptions, "throws 声明必须保留");
    }

    private byte[] sampleClass() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "sample/Fixture",
                "Ljava/lang/Object;Ljava/lang/Comparable<Lsample/Fixture;>;",
                "java/lang/Object", null);
        writer.visitSource("Fixture.java", "SMAP\nFixture.java\nJava\n*S Java\n*E");
        writer.visitOuterClass("sample/Owner", "create", "()V");
        writer.visitInnerClass("sample/Fixture", "sample/Owner", "Fixture", Opcodes.ACC_PUBLIC);
        writer.visitField(Opcodes.ACC_PRIVATE, "value", "Ljava/util/List;",
                "Ljava/util/List<Ljava/lang/String;>;", null).visitEnd();

        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, "run", "(Ljava/lang/String;)V",
                "(TT;)V", new String[]{"java/io/IOException"});
        method.visitParameter("input", 0);
        method.visitCode();
        Label start = new Label();
        Label end = new Label();
        method.visitLabel(start);
        method.visitInsn(Opcodes.RETURN);
        method.visitLabel(end);
        method.visitLocalVariable("this", "Lsample/Fixture;", null, start, end, 0);
        method.visitLocalVariable("input", "Ljava/lang/String;", null, start, end, 1);
        method.visitLineNumber(12, start);
        method.visitMaxs(0, 2);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static final class MetadataProbe extends ClassVisitor {
        private boolean source;
        private boolean outerClass;
        private boolean innerClass;
        private boolean classSignature;
        private boolean fieldSignature;
        private boolean methodSignature;
        private boolean methodParameters;
        private boolean exceptions;

        private MetadataProbe() {
            super(Opcodes.ASM9);
        }

        @Override
        public void visit(int version, int access, String name, String signature,
                          String superName, String[] interfaces) {
            classSignature = signature != null;
        }

        @Override
        public void visitSource(String source, String debug) {
            this.source = true;
        }

        @Override
        public void visitOuterClass(String owner, String name, String descriptor) {
            outerClass = true;
        }

        @Override
        public void visitInnerClass(String name, String outerName, String innerName, int access) {
            innerClass = true;
        }

        @Override
        public org.objectweb.asm.FieldVisitor visitField(int access, String name,
                                                         String descriptor, String signature,
                                                         Object value) {
            fieldSignature |= signature != null;
            return null;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                         String signature, String[] declaredExceptions) {
            methodSignature |= signature != null;
            exceptions |= declaredExceptions != null && declaredExceptions.length > 0;
            return new MethodVisitor(Opcodes.ASM9) {
                @Override
                public void visitParameter(String name, int access) {
                    methodParameters = true;
                }
            };
        }
    }
}
