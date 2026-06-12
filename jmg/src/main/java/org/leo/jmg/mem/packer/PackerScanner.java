package org.leo.jmg.mem.packer;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 基于类路径扫描发现所有带 {@link PackerMeta} 注解的 {@link Packer} 实现。
 * <p>
 * 替代 ServiceLoader + META-INF/services 的冗余机制：
 * 新增 Packer 只需加 {@code @PackerMeta} 注解，无需手动维护 services 文件。
 */
final class PackerScanner {

    private static final String RESOURCE_PATTERN = "classpath*:org/leo/jmg/mem/packer/**/*.class";

    private PackerScanner() {
    }

    /**
     * 扫描 base package 下所有带 @PackerMeta 注解的 Packer 实现类并实例化
     */
    static List<Packer> scan() {
        List<Packer> packers = new ArrayList<>();
        ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();

        try {
            Resource[] resources = resolver.getResources(RESOURCE_PATTERN);
            ClassLoader classLoader = Thread.currentThread().getContextClassLoader();

            for (Resource resource : resources) {
                String className = resolveClassName(resource);
                if (className == null) continue;

                try {
                    Class<?> clazz = classLoader.loadClass(className);
                    if (!Packer.class.isAssignableFrom(clazz)) continue;
                    if (clazz.isInterface() || java.lang.reflect.Modifier.isAbstract(clazz.getModifiers())) continue;
                    if (clazz.getAnnotation(PackerMeta.class) == null) continue;

                    Packer instance = (Packer) clazz.getDeclaredConstructor().newInstance();
                    packers.add(instance);
                } catch (Exception e) {
                    System.err.println("[PackerScanner] 加载失败: " + className + " — " + e.getMessage());
                }
            }
        } catch (IOException e) {
            System.err.println("[PackerScanner] 类路径扫描失败: " + e.getMessage());
        }

        return packers;
    }

    /**
     * 从 Resource 推导全限定类名
     */
    private static String resolveClassName(Resource resource) {
        try {
            String uri = resource.getURI().toString();
            // 定位 base package 路径片段
            String marker = "org/leo/jmg/mem/packer/";
            int idx = uri.lastIndexOf(marker);
            if (idx < 0) return null;

            String relative = uri.substring(idx);
            if (!relative.endsWith(".class")) return null;

            // 去掉 .class 后缀，路径分隔符转点号
            return relative.substring(0, relative.length() - 6).replace('/', '.');
        } catch (IOException e) {
            return null;
        }
    }
}
