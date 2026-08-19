package org.leo.core.component;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * 资源读取组件（在 puppet 上跑）。
 *
 * <p>用途：从 puppet JVM 的某个 ClassLoader 中读取资源（典型用例是类字节码 .class、
 * 配置文件 application.yml 等）。Web 端的「字节码查看」、AI 工具 readSpringBootConfig
 * 等都最终走到这里。
 *
 * <p>调用约定与 {@link PluginComponent} 一致：
 * <ul>
 *   <li>puppet 把当前线程 contextClassLoader cast 成 InvocationHandler 作为参数/结果通道</li>
 *   <li>{@code h.invoke(null, null, null)} 取入参</li>
 *   <li>{@code h.invoke(null, null, new Object[]{results})} 回写结果</li>
 * </ul>
 *
 * <p>{@code run()} 捕获所有目标运行时异常并始终回写结果。资源查找依次使用线程上下文、
 * 系统以及 Tomcat Webapp ClassLoader。
 *
 * <p>兼容 Java 1.5+，避免使用 lambda、try-with-resources、新集合 API。
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
            if (results == null) {
                results = new HashMap();
            }
            results.put("code", Integer.valueOf(500));
            String msg = t.getMessage();
            results.put("msg", msg != null ? msg : t.getClass().getName());
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
            results.put("code", Integer.valueOf(400));
            results.put("msg", "resourcePath 不能为空");
            return;
        }
        resourcePath = resourcePath.trim().replace('\\', '/');
        while (resourcePath.startsWith("/")) {
            resourcePath = resourcePath.substring(1);
        }
        if (resourcePath.length() == 0 || "..".equals(resourcePath)
                || resourcePath.startsWith("../") || resourcePath.endsWith("/..")
                || resourcePath.indexOf("/../") >= 0) {
            results.put("code", Integer.valueOf(400));
            results.put("msg", "resourcePath 包含无效路径片段");
            return;
        }

        // 依次尝试所有可能持有该资源的 ClassLoader
        byte[] bytes = readResource(resourcePath);
        if (bytes == null) {
            if (resourceTooLarge) {
                results.put("code", Integer.valueOf(413));
                results.put("msg", "资源超过 16MB 读取上限");
                return;
            }
            results.put("code", Integer.valueOf(404));
            results.put("msg", "找不到资源: " + resourcePath
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
        // 1. 当前线程 contextClassLoader
        byte[] bytes = tryLoad(Thread.currentThread().getContextClassLoader(), resourcePath);
        if (bytes != null) return bytes;

        // 2. system ClassLoader
        bytes = tryLoad(ClassLoader.getSystemClassLoader(), resourcePath);
        if (bytes != null) return bytes;

        // 3. 遍历 Tomcat WebappClassLoader
        //    用 PlatformMBeanServer + Catalina:j2eeType=WebModule,* 找到所有 StandardContext，
        //    StandardContext.getLoader().getClassLoader() 就是 webapp CL。
        try {
            HashSet webappLoaders = collectWebappClassLoaders();
            Iterator iter = webappLoaders.iterator();
            while (iter.hasNext()) {
                ClassLoader cl = (ClassLoader) iter.next();
                bytes = tryLoad(cl, resourcePath);
                if (bytes != null) return bytes;
            }
        } catch (Throwable ignored) {
            // JMX 不可用 / 不是 Tomcat 容器，直接放弃
        }
        return null;
    }

    private byte[] tryLoad(ClassLoader cl, String resourcePath) {
        if (cl == null) return null;
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
                    if (baos.size() >= MAX_RESOURCE_SIZE) {
                        resourceTooLarge = true;
                        throw new java.io.IOException("资源超过最大允许大小");
                    }
                    baos.write(single);
                    continue;
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
     * Loader.getClassLoader() 作为候选。这条路径在 puppet 注入到 Tomcat 全局 CL 时尤其关键。
     */
    private HashSet collectWebappClassLoaders() throws Throwable {
        HashSet result = new HashSet();
        Class mfClass = Class.forName("java.lang.management.ManagementFactory");
        Object mbs = mfClass.getMethod("getPlatformMBeanServer").invoke(null);
        Class onClass = Class.forName("javax.management.ObjectName");
        Object pattern = onClass.getConstructor(String.class)
                .newInstance("Catalina:j2eeType=WebModule,*");
        Method queryNames = mbs.getClass().getMethod("queryNames", onClass,
                Class.forName("javax.management.QueryExp"));
        Set names = (Set) queryNames.invoke(mbs, pattern, null);
        if (names == null || names.isEmpty()) return result;
        Method getAttribute = mbs.getClass().getMethod("getAttribute", onClass, String.class);
        Iterator iter = names.iterator();
        while (iter.hasNext()) {
            try {
                Object on = iter.next();
                Object ctx = getAttribute.invoke(mbs, on, "managedResource");
                if (ctx == null) continue;
                Method getLoader = ctx.getClass().getMethod("getLoader");
                Object loader = getLoader.invoke(ctx);
                if (loader == null) continue;
                Method getClassLoader = loader.getClass().getMethod("getClassLoader");
                Object cl = getClassLoader.invoke(loader);
                if (cl instanceof ClassLoader) {
                    result.add(cl);
                }
            } catch (Throwable ignored) {
                // 单个 webapp 失败不影响其他
            }
        }
        return result;
    }

    private String getStringParam(String key) throws java.io.UnsupportedEncodingException {
        Object value = params.get(key);
        if (value == null) return null;
        if (value instanceof String) return (String) value;
        if (value instanceof byte[]) return new String((byte[]) value, "UTF-8");
        return String.valueOf(value);
    }

    private static HashMap copyStringObjectMap(Object value) {
        HashMap copy = new HashMap();
        if (!(value instanceof Map)) return copy;
        Map source = (Map) value;
        Iterator entries = source.entrySet().iterator();
        while (entries.hasNext()) {
            Map.Entry entry = (Map.Entry) entries.next();
            if (entry.getKey() instanceof String) {
                copy.put(entry.getKey(), entry.getValue());
            }
        }
        return copy;
    }
}
