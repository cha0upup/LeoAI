package org.leo.core.infrastructure.bytecode.asm;

import org.objectweb.asm.*;

/**
 * 类文件最小化工具
 * 移除类文件中的调试信息、注解、行号等，减小文件大小
 *
 * @author LeoSpring
 * @version 2.0
 */
public class ClassFileMinimizer {

    // ASM版本常量
    private static final int ASM_VERSION = Opcodes.ASM5;

    /**
     * 转换类文件，移除调试信息和注解
     *
     * @param classByte 原始类文件的字节数组
     * @return 最小化后的类文件字节数组
     */
    public static byte[] transform(byte[] classByte) {
        ClassReader classReader = new ClassReader(classByte);
        // Javassist 改名后统一重算 frame；伪装类名本身不在 ClassLoader 中，
        // 类型解析失败时按 Object 合并，保持 Java 6 Component 的栈图稳定。
        ClassWriter classWriter = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected String getCommonSuperClass(String type1, String type2) {
                try {
                    return super.getCommonSuperClass(type1, type2);
                } catch (Throwable ignored) {
                    return "java/lang/Object";
                }
            }
        };
        ClassVisitor classVisitor = new ClassVisitor(ASM_VERSION, classWriter) {
            @Override
            public void visit(int version, int access, String name, String signature,
                              String superName, String[] interfaces) {
                // 保留泛型 Signature 属性：JVM 链接不依赖它，但运行时反射
                // (如 Class.getGenericInterfaces / Method.getGenericParameterTypes)
                // 依赖它解析参数化类型。Tomcat WebSocket 的 Util.getGenericType()
                // 会通过它推断 MessageHandler.Whole<ByteBuffer> 的类型参数，
                // 剥离后会导致 NullPointerException 并断开连接。
                super.visit(version, access, name, signature, superName, interfaces);
            }

            @Override
            public void visitSource(String source, String debug) {
                // 移除源文件和调试信息，不调用 super.visitSource
            }

            @Override
            public void visitOuterClass(String owner, String name, String descriptor) {
                // Component 均为可独立加载的顶级类，移除 EnclosingMethod。
            }

            @Override
            public void visitInnerClass(String name, String outerName, String innerName, int access) {
                // 单文件 payload 不携带配套内部类，移除 InnerClasses 表。
            }

            @Override
            public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                // 移除所有类注解
                return null;
            }

            @Override
            public AnnotationVisitor visitTypeAnnotation(int typeRef, TypePath typePath,
                                                         String descriptor, boolean visible) {
                return null;
            }

            @Override
            public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
                return new FieldVisitor(ASM_VERSION, super.visitField(access, name, descriptor, signature, value)) {
                    @Override
                    public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                        // 移除所有字段注解
                        return null;
                    }

                    @Override
                    public AnnotationVisitor visitTypeAnnotation(int typeRef, TypePath typePath,
                                                                 String descriptor, boolean visible) {
                        return null;
                    }
                };
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                // 保留 Signature：运行时反射依赖它解析方法泛型类型。
                // Exceptions (throws) 不影响执行，保留也不增加体积，一并不剥离。
                return new MethodVisitor(ASM_VERSION, super.visitMethod(access, name, descriptor, signature, exceptions)) {
                    @Override
                    public void visitParameter(String name, int access) {
                        // 移除 MethodParameters。
                    }

                    @Override
                    public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                        // 移除所有方法注解
                        return null;
                    }

                    @Override
                    public AnnotationVisitor visitAnnotationDefault() {
                        return null;
                    }

                    @Override
                    public AnnotationVisitor visitParameterAnnotation(int parameter,
                                                                      String descriptor,
                                                                      boolean visible) {
                        return null;
                    }

                    @Override
                    public AnnotationVisitor visitTypeAnnotation(int typeRef, TypePath typePath,
                                                                 String descriptor, boolean visible) {
                        return null;
                    }

                    @Override
                    public void visitLineNumber(int line, Label start) {
                        // 移除行号信息，不调用 super.visitLineNumber
                    }

                    @Override
                    public void visitLocalVariable(String name, String descriptor, String signature, Label start, Label end, int index) {
                        // 移除局部变量信息，不调用 super.visitLocalVariable
                    }
                };
            }
        };
        classReader.accept(classVisitor, ClassReader.SKIP_DEBUG);
        return classWriter.toByteArray();
    }
}
