package org.leo.core.util.request;

import java.util.Random;

/**
 * User-Agent生成器工具类
 * 生成随机的HTTP User-Agent头，用于模拟真实的浏览器行为
 * 
 * @author LeoSpring
 * @version 2.0
 */
public class UserAgentGenerator {

    // 浏览器名称常量
    private static final String BROWSER_CHROME = "Chrome";
    private static final String BROWSER_FIREFOX = "Firefox";
    private static final String BROWSER_EDGE = "Edge";
    private static final String BROWSER_SAFARI = "Safari";
    private static final String BROWSER_OPERA = "Opera";
    private static final String BROWSER_TRIDENT = "Trident";
    private static final String BROWSER_PRESTO = "Presto";
    
    // User-Agent模板常量
    private static final String TEMPLATE_CHROME = "Mozilla/5.0 (%s; %s) %s Chrome/%s Safari/537.36";
    private static final String TEMPLATE_FIREFOX = "Mozilla/5.0 (%s; %s; rv:%s) %s Firefox/%s";
    private static final String TEMPLATE_EDGE = "Mozilla/5.0 (%s; %s) %s Edg/%s";
    private static final String TEMPLATE_SAFARI = "Mozilla/5.0 (%s; %s) %s Version/%s Safari/537.36";
    private static final String TEMPLATE_OPERA = "Mozilla/5.0 (%s; %s) %s OPR/%s";
    private static final String TEMPLATE_TRIDENT = "Mozilla/5.0 (%s; %s; Trident/%s; AS; .NET CLR 4.0.30319) %s";
    private static final String TEMPLATE_PRESTO = "Mozilla/5.0 (%s; %s; Presto/%s) %s";
    private static final String TEMPLATE_DEFAULT = "Mozilla/5.0 (%s; %s) %s/%s";
    
    // 版本号范围常量
    private static final int MAX_VERSION = 100;
    private static final int MIN_VERSION = 1;
    
    // 浏览器名称
    private static final String[] BROWSERS = {
            "Chrome", "Firefox", "Edge", "Safari", "Opera", "Trident", "Presto"
    };

    // 操作系统名称
    private static final String[] OS = {
            "Windows NT 10.0; Win64; x64", "Macintosh; Intel Mac OS X 10_15_7", "Android 11; Mobile", "iPhone; CPU iPhone OS 14_7_1 like Mac OS X",
            "Windows NT 6.1; WOW64",  "Linux; Android 9; SM-G960U",
    };

    // 设备型号
    private static final String[] DEVICES = {
            "Pixel 4 XL", "iPhone 12", "Samsung Galaxy S20", "MacBook Pro", "Dell XPS 13", "iPad Air", "Surface Pro", "Nexus 5X"
    };

    // 浏览器引擎
    private static final String[] ENGINES = {
            "AppleWebKit/537.36 (KHTML, like Gecko)", "Gecko/20100101", "WebKit/537.36", "Presto/2.12.388", "Trident/7.0"
    };

    private static final Random RANDOM = new Random();

    /**
     * 生成随机User-Agent字符串
     * 
     * @return 随机生成的User-Agent字符串
     */
    public static String generateRandomUserAgent() {
        String browser = BROWSERS[RANDOM.nextInt(BROWSERS.length)];
        String os = OS[RANDOM.nextInt(OS.length)];
        String device = DEVICES[RANDOM.nextInt(DEVICES.length)];
        String engine = ENGINES[RANDOM.nextInt(ENGINES.length)];
        String browserVersion = generateRandomVersion();

        // 根据浏览器类型和版本生成 User-Agent
        String userAgent;
        switch (browser) {
            case BROWSER_CHROME:
                userAgent = String.format(TEMPLATE_CHROME, os, device, engine, browserVersion);
                break;
            case BROWSER_FIREFOX:
                userAgent = String.format(TEMPLATE_FIREFOX, os, device, browserVersion, engine, browserVersion);
                break;
            case BROWSER_EDGE:
                userAgent = String.format(TEMPLATE_EDGE, os, device, engine, browserVersion);
                break;
            case BROWSER_SAFARI:
                userAgent = String.format(TEMPLATE_SAFARI, os, device, engine, browserVersion);
                break;
            case BROWSER_OPERA:
                userAgent = String.format(TEMPLATE_OPERA, os, device, engine, browserVersion);
                break;
            case BROWSER_TRIDENT:
                userAgent = String.format(TEMPLATE_TRIDENT, os, device, browserVersion, engine);
                break;
            case BROWSER_PRESTO:
                userAgent = String.format(TEMPLATE_PRESTO, os, device, browserVersion, engine);
                break;
            default:
                userAgent = String.format(TEMPLATE_DEFAULT, os, device, engine, browserVersion);
                break;
        }

        return userAgent;
    }

    /**
     * 生成随机版本号
     */
    private static String generateRandomVersion() {
        // 生成随机版本号
        int major = RANDOM.nextInt(MAX_VERSION) + MIN_VERSION;
        int minor = RANDOM.nextInt(MAX_VERSION);
        int patch = RANDOM.nextInt(MAX_VERSION);
        return major + "." + minor + "." + patch;
    }

}