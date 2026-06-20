package org.leo.core.component;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
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
 * <p>关键设计点（相对历史版本）：
 * <ol>
 *   <li>{@code run()} 用 {@code catch (Throwable)} 兜底，确保 results 永远会被回写，
 *       避免 puppet 返回空响应导致 web 端报「响应解码结果为空」。</li>
 *   <li>不止依赖 contextClassLoader / systemClassLoader：当 puppet 注入位置在 Tomcat
 *       common/shared loader 时，看不到任何 webapp 里的类（filter / servlet 在
 *       WebappClassLoader 里）。这里增加 <b>Tomcat WebappClassLoader 兜底</b>：
 *       通过 PlatformMBeanServer 查 Catalina:j2eeType=WebModule,* 找到每个 webapp 的
 *       ClassLoader 再依次尝试。</li>
 *   <li>响应里同时塞 {@code bytecode}（byte[]）和 {@code data}，兼容旧调用方两种 key。</li>
 * </ol>
 *
 * <p>兼容 Java 1.5+，避免使用 lambda、try-with-resources、新集合 API。
 *
 * @author LeoSpring
 * @version 2.2
 */
public class ResourceComponent implements Runnable {

    private HashMap params;
    private HashMap results;

    public void run() {
        InvocationHandler h = (InvocationHandler) Thread.currentThread().getContextClassLoader();
        try {
            params = (HashMap) h.invoke(null, null, null);
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
        // 无论成功失败都必须回写 results，否则 web 端 decode 出空 map 报「响应解码结果为空」
        try {
            h.invoke(null, null, new Object[]{results});
        } catch (Throwable ignored) {
        }
    }

    public void invoke() throws Exception {
        if (!params.containsKey("resourcePath")) {
            results.put("code", Integer.valueOf(400));
            results.put("msg", "resourcePath 不能为空");
            return;
        }
        String resourcePath = (String) params.get("resourcePath");
        if (resourcePath == null || resourcePath.trim().length() == 0) {
            results.put("code", Integer.valueOf(400));
            results.put("msg", "resourcePath 不能为空");
            return;
        }
        resourcePath = resourcePath.trim();

        // 依次尝试所有可能持有该资源的 ClassLoader
        byte[] bytes = readResource(resourcePath);
        if (bytes == null) {
            results.put("code", Integer.valueOf(404));
            results.put("msg", "找不到资源: " + resourcePath
                    + "（已尝试 contextClassLoader / systemClassLoader / 所有 Tomcat WebappClassLoader）");
            return;
        }
        results.put("code", Integer.valueOf(200));
        // 兼容历史 controller：ClassBytecodeController 读 "bytecode"，部分 AI 工具读 "data"
        results.put("bytecode", bytes);
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

        // 3. Tomcat WebappClassLoader 兜底：遍历所有 webapp 的 ClassLoader
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
            byte[] buffer = new byte[4096];
            int n;
            while ((n = in.read(buffer)) > 0) {
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
}
