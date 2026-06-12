package org.leo.core.util.decompiler;

import javassist.ClassPool;
import javassist.CtClass;
import org.benf.cfr.reader.api.CfrDriver;
import org.benf.cfr.reader.api.OutputSinkFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.*;

public class DecompilerUtil {

    private static final String TEMP_DIR_NAME = "leo_decompiler_temp";

    private static File getTempDir() {
        String sysTemp = System.getProperty("java.io.tmpdir");
        File tempDir = new File(sysTemp, TEMP_DIR_NAME);
        if (!tempDir.exists()) {
            tempDir.mkdirs();
        }
        return tempDir;
    }

    /**
     * 反编译字节码为Java源代码（使用 CFR）
     *
     * @param bytecode 字节码数组
     * @return 反编译后的Java源代码
     * @throws IOException 反编译失败时抛出异常
     */
    public static String decompile(byte[] bytecode) throws IOException {
        if (bytecode == null || bytecode.length == 0) {
            throw new IllegalArgumentException("bytecode参数不能为空");
        }

        File tempDir = getTempDir();
        File tempClass = File.createTempFile("decompiler_", ".class", tempDir);

        try {
            try (FileOutputStream fos = new FileOutputStream(tempClass)) {
                fos.write(bytecode);
                fos.flush();
            }

            StringBuilder result = new StringBuilder();

            OutputSinkFactory sinkFactory = new OutputSinkFactory() {
                @Override
                public List<SinkClass> getSupportedSinks(SinkType sinkType, Collection<SinkClass> available) {
                    return Collections.singletonList(SinkClass.STRING);
                }

                @Override
                public <T> Sink<T> getSink(SinkType sinkType, SinkClass sinkClass) {
                    return sinkType == SinkType.JAVA
                            ? content -> result.append(content)
                            : content -> {};
                }
            };

            Map<String, String> options = new HashMap<>();
            options.put("showversion", "false");
            options.put("comments", "false");

            CfrDriver driver = new CfrDriver.Builder()
                    .withOutputSink(sinkFactory)
                    .withOptions(options)
                    .build();

            driver.analyse(Collections.singletonList(tempClass.getAbsolutePath()));

            String code = result.toString();
            if (code.isEmpty()) {
                throw new IOException("反编译结果为空");
            }
            return code;
        } finally {
            if (tempClass.exists()) {
                tempClass.delete();
            }
        }
    }

    /**
     * 从字节码中提取类名（简单类名，不包含包名）
     */
    public static String extractClassName(byte[] bytecode) throws Exception {
        if (bytecode == null || bytecode.length == 0) {
            throw new IllegalArgumentException("bytecode参数不能为空");
        }

        try {
            ClassPool pool = ClassPool.getDefault();
            CtClass ctClass = pool.makeClass(new java.io.ByteArrayInputStream(bytecode));
            String simpleClassName = ctClass.getSimpleName();
            ctClass.detach();
            return simpleClassName;
        } catch (Exception e) {
            throw new RuntimeException("无法从bytecode中提取类名: " + e.getMessage(), e);
        }
    }

    /**
     * 验证字节码是否有效
     */
    public static boolean validateBytecode(byte[] bytecode) {
        if (bytecode == null || bytecode.length == 0) {
            return false;
        }

        try {
            decompile(bytecode);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}