package org.leo.core.component;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.nio.charset.Charset;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 资源读取组件（在 agent 上跑）。
 *
 * <p>用途：从 agent JVM 的某个 ClassLoader 中读取资源（典型用例是类字节码 .class、
 * 配置文件 application.yml 等）。Web 端的「字节码查看」、AI 工具 readSpringBootConfig
 * 等都最终走到这里。
 *
 * <p>调用约定与 {@link PluginComponent} 一致：
 * <ul>
 *   <li>agent 把当前线程 contextClassLoader cast 成 InvocationHandler 作为参数/结果通道</li>
 *   <li>{@code h.invoke(null, null, null)} 取入参</li>
 *   <li>{@code h.invoke(null, null, new Object[]{results})} 回写结果</li>
 * </ul>
 *
 * <p>{@code run()} 捕获所有目标运行时异常并始终回写结果。资源查找依次使用线程上下文、
 * 系统以及 Tomcat Webapp ClassLoader。
 *
 * <p>兼容 Java 6+，仅使用 JDK 内置类。
 *
 * @author LeoSpring
 * @version 2.2
 */
public class ResourceComponent implements Runnable {

    private static final int MAX_RESOURCE_SIZE = 16 * 1024 * 1024;

    private HashMap<String, Object> params;
    private HashMap<String, Object> results;
    private boolean resourceTooLarge;

    public void run() {
        InvocationHandler h = (InvocationHandler) Thread.currentThread().getContextClassLoader();
        try {
            params = copyStringObjectMap(h.invoke(null, null, null));
            results = new HashMap();
            invoke();
        } catch (Throwable t) {
            if (results == null) results = new HashMap();
            setResult(500, t.getMessage() != null ? t.getMessage() : t.getClass().getName());
        }
        // 无论成功失败都回写结构化结果。
        try {
            h.invoke(null, null, new Object[]{results});
        } catch (Throwable ignored) {
        }
    }

    public void invoke() throws Exception {
        resourceTooLarge = false;
        String resourcePath = getStringParam("resourcePath");
        if (resourcePath == null || resourcePath.trim().length() == 0) {
            setResult(400, "resourcePath 不能为空");
            return;
        }
        resourcePath = resourcePath.trim().replace('\\', '/');
        while (resourcePath.startsWith("/")) {
            resourcePath = resourcePath.substring(1);
        }
        if (resourcePath.length() == 0 || "..".equals(resourcePath)
                || resourcePath.startsWith("../") || resourcePath.endsWith("/..")
                || resourcePath.indexOf("/../") >= 0) {
            setResult(400, "resourcePath 包含无效路径片段");
            return;
        }

        byte[] bytes = readResource(resourcePath);
        if (bytes == null) {
            setResult(resourceTooLarge ? 413 : 404, resourceTooLarge
                    ? "资源超过 16MB 读取上限"
                    : "找不到资源: " + resourcePath
                        + "（已尝试 contextClassLoader / systemClassLoader / 所有 Tomcat WebappClassLoader）");
            return;
        }
        results.put("code", Integer.valueOf(200));
        results.put("data", bytes);
        results.put("resourcePath", resourcePath);
        results.put("size", Integer.valueOf(bytes.length));
    }

    /** 在多个 ClassLoader 中尝试加载 resourcePath，返回第一个成功读到的字节流。 */
    private byte[] readResource(String resourcePath) {
        // 按实例去重；前一个加载器未命中时才查找后续候选。
        Set<ClassLoader> visited = Collections.newSetFromMap(new IdentityHashMap<ClassLoader, Boolean>());
        byte[] bytes = tryLoad(Thread.currentThread().getContextClassLoader(), resourcePath, visited);
        if (bytes != null) return bytes;

        bytes = tryLoad(ClassLoader.getSystemClassLoader(), resourcePath, visited);
        if (bytes != null) return bytes;

        try {
            for (ClassLoader cl : collectWebappClassLoaders()) {
                bytes = tryLoad(cl, resourcePath, visited);
                if (bytes != null) return bytes;
            }
        } catch (Throwable ignored) {
            // JMX 不可用 / 不是 Tomcat 容器，直接放弃
        }
        return null;
    }

    private byte[] tryLoad(ClassLoader cl, String resourcePath, Set<ClassLoader> visited) {
        if (cl == null || !visited.add(cl)) return null;
        InputStream in = null;
        try {
            in = cl.getResourceAsStream(resourcePath);
            if (in == null) return null;
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) != -1) {
                if (n == 0) {
                    int single = in.read();
                    if (single == -1) break;
                    buffer[0] = (byte) single;
                    n = 1;
                }
                if (baos.size() > MAX_RESOURCE_SIZE - n) {
                    resourceTooLarge = true;
                    throw new java.io.IOException("资源超过最大允许大小");
                }
                baos.write(buffer, 0, n);
            }
            return baos.toByteArray();
        } catch (Throwable t) {
            return null;
        } finally {
            if (in != null) {
                try { in.close(); } catch (Throwable ignored) {}
            }
        }
    }

    /**
     * 通过 PlatformMBeanServer 查所有 Tomcat WebModule，提取每个 StandardContext 上挂的
     * Loader.getClassLoader() 作为候选。这条路径在 agent 注入到 Tomcat 全局 CL 时尤其关键。
     */
    private Set<ClassLoader> collectWebappClassLoaders() throws Throwable {
        Set<ClassLoader> result = new HashSet<ClassLoader>();
        Class mfClass = Class.forName("java.lang.management.ManagementFactory");
        Object mbs = mfClass.getMethod("getPlatformMBeanServer").invoke(null);
        Class mbsClass = Class.forName("javax.management.MBeanServer");
        Class onClass = Class.forName("javax.management.ObjectName");
        Object pattern = onClass.getConstructor(String.class)
                .newInstance("Catalina:j2eeType=WebModule,*");
        Method queryNames = mbsClass.getMethod("queryNames", onClass,
                Class.forName("javax.management.QueryExp"));
        Set names = (Set) queryNames.invoke(mbs, pattern, null);
        if (names == null || names.isEmpty()) return result;
        Method getAttribute = mbsClass.getMethod("getAttribute", onClass, String.class);
        for (Object on : names) {
            try {
                Object ctx = getAttribute.invoke(mbs, on, "managedResource");
                if (ctx == null) continue;
                Method getLoader = ctx.getClass().getMethod("getLoader");
                Object loader = getLoader.invoke(ctx);
                if (loader == null) continue;
                Method getClassLoader = loader.getClass().getMethod("getClassLoader");
                Object cl = getClassLoader.invoke(loader);
                if (cl instanceof ClassLoader) {
                    result.add((ClassLoader) cl);
                }
            } catch (Throwable ignored) {
                // 单个 webapp 失败不影响其他
            }
        }
        return result;
    }

    private String getStringParam(String key) {
        Object value = params.get(key);
        if (value == null) return null;
        if (value instanceof byte[]) return new String((byte[]) value, Charset.forName("UTF-8"));
        return String.valueOf(value);
    }

    private static HashMap copyStringObjectMap(Object value) {
        HashMap copy = new HashMap();
        if (!(value instanceof Map)) return copy;
        for (Object item : ((Map) value).entrySet()) {
            Map.Entry entry = (Map.Entry) item;
            if (entry.getKey() instanceof String) {
                copy.put(entry.getKey(), entry.getValue());
            }
        }
        return copy;
    }

    private void setResult(int code, String message) {
        results.put("code", Integer.valueOf(code));
        results.put("msg", message);
    }
}
