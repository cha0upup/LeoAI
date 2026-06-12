package org.leo.jmg.util.javassist;

import javassist.*;
import javassist.bytecode.AnnotationsAttribute;
import javassist.bytecode.ClassFile;
import javassist.bytecode.ConstPool;
import javassist.bytecode.annotation.Annotation;

public class JavassistUtil {

    private static ClassPool newPool() {
        return new ClassPool(ClassPool.getDefault());
    }

    public static void addMethod(CtClass ctClass, String methodName, String methodBody) throws Exception {
        ctClass.defrost();
        try {
            // 已存在，修改
            CtMethod ctMethod = ctClass.getDeclaredMethod(methodName);
            ctMethod.setBody(methodBody);
        } catch (NotFoundException ignored) {
            // 不存在，直接添加
            CtMethod method = CtNewMethod.make(methodBody, ctClass);
            ctClass.addMethod(method);
        }
    }


    public static void addField(CtClass ctClass, String fieldName, String fieldValue) throws Exception {
        ctClass.defrost();
        try {
            // 已存在，删除后重建
            CtField field = ctClass.getDeclaredField(fieldName);
            ctClass.removeField(field);
        } catch (NotFoundException ignored) {
            // 不存在，继续添加
        }
        try {
            CtField defField = new CtField(newPool().getCtClass("java.lang.String"), fieldName, ctClass);
            defField.setModifiers(Modifier.PUBLIC);
            ctClass.addField(defField, "\"" + fieldValue + "\"");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static void addStaticField(CtClass ctClass, String fieldName, String fieldValue) throws Exception {
        ctClass.defrost();
        try {
            // 已存在，删除后重建
            CtField field = ctClass.getDeclaredField(fieldName);
            ctClass.removeField(field);
        } catch (NotFoundException ignored) {
            // 不存在，继续添加
        }
        try {
            CtField defField = new CtField(newPool().getCtClass("java.lang.String"), fieldName, ctClass);
            // PUBLIC | STATIC 需要用位或合并，不能连续调用 setModifiers（后者会覆盖前者）
            defField.setModifiers(Modifier.PUBLIC | Modifier.STATIC);
            ctClass.addField(defField, "\"" + fieldValue + "\"");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }


    public static void extendClass(CtClass ctClass, String superClassName) throws Exception {
        ctClass.defrost();
        ClassPool pool = newPool();
        ctClass.setSuperclass(pool.get(superClassName));
    }

    public static void implementInterface(CtClass ctClass, String interfaceClassName) throws Exception {
        ctClass.defrost();
        ClassPool pool = newPool();
        CtClass interfaceClass = pool.makeInterface(interfaceClassName);
        CtClass[] ctClasses = new CtClass[]{interfaceClass};
        ctClass.setInterfaces(ctClasses);
    }


    public static void addAnnotation(CtClass ctClass, String interfaceClassName) throws Exception {
        ctClass.defrost();
        ClassFile classFile = ctClass.getClassFile();
        ConstPool constPool = classFile.getConstPool();
        AnnotationsAttribute clazzAnnotationsAttribute = new AnnotationsAttribute(constPool, AnnotationsAttribute.visibleTag);
        Annotation clazzAnnotation = new Annotation(convertClassNameToFilePath(interfaceClassName), constPool);
        clazzAnnotationsAttribute.setAnnotation(clazzAnnotation);
        ctClass.getClassFile().addAttribute(clazzAnnotationsAttribute);
    }


    public static void addFieldIfNotNull(CtClass ctClass, String fieldName, String fieldValue) throws Exception {
        if (fieldValue != null) {
            JavassistUtil.addField(ctClass, fieldName, fieldValue);
        }
    }

    public static void addStaticFieldIfNotNull(CtClass ctClass, String fieldName, String fieldValue) throws Exception {
        if (fieldValue != null) {
            JavassistUtil.addStaticField(ctClass, fieldName, fieldValue);
        }
    }

    public static void setNameIfNotNull(CtClass ctClass, String className) throws Exception {
        if (className != null) {
            ctClass.setName(className);
        }
    }

    public static String convertClassNameToFilePath(String className) {
        return className.replace(".", "/");
    }

}
