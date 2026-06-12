package org.leo.core.util.asm;

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

    // ClassWriter标志常量
    private static final int CLASS_WRITER_FLAGS = ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES;

    /**
     * 转换类文件，移除调试信息和注解
     *
     * @param classByte 原始类文件的字节数组
     * @return 最小化后的类文件字节数组
     */
    public static byte[] transform(byte[] classByte) {
        ClassReader classReader = new ClassReader(classByte);
        // COMPUTE_FRAMES 重算栈帧时会调用 getCommonSuperClass() 加载类型。
        // 伪装类名（如 $$Lambda$7）在任何 ClassLoader 里都不存在，加载必然失败。
        // 覆写该方法：加载失败时回退到 java/lang/Object，对 Java 1.6 语法的
        // 单继承组件字节码完全安全。
        ClassWriter classWriter = new ClassWriter(CLASS_WRITER_FLAGS) {
            @Override
            protected String getCommonSuperClass(String type1, String type2) {
                try {
                    return super.getCommonSuperClass(type1, type2);
                } catch (Throwable t) {
                    return "java/lang/Object";
                }
            }
        };
        ClassVisitor classVisitor = new ClassVisitor(ASM_VERSION, classWriter) {
            @Override
            public void visitSource(String source, String debug) {
                // 移除源文件和调试信息，不调用 super.visitSource
            }

            @Override
            public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                // 移除所有类注解
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
                };
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                return new MethodVisitor(ASM_VERSION, super.visitMethod(access, name, descriptor, signature, exceptions)) {
                    @Override
                    public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                        // 移除所有方法注解
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
        classReader.accept(classVisitor, 0);
        byte[] minimizedClass = classWriter.toByteArray();
        return minimizedClass;
    }
}