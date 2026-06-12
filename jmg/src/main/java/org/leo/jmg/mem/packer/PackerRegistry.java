package org.leo.jmg.mem.packer;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Packer 注册中心，基于类路径扫描自动发现所有带 {@link PackerMeta} 注解的 {@link Packer} 实现。
 * <p>
 * 支持：
 * <ul>
 *   <li>按名称（忽略大小写）获取 Packer 实例</li>
 *   <li>判断某 Packer 是否要求 AbstractTranslet</li>
 *   <li>按分组层级结构输出（供前端展示）</li>
 * </ul>
 *
 * @author LeoSpring
 */
public final class PackerRegistry {

    private PackerRegistry() {
    }

    /** name(小写) -> Packer 实例 */
    private static final Map<String, Packer> REGISTRY = new ConcurrentHashMap<>();
    /** name(小写) -> 元数据 */
    private static final Map<String, PackerMeta> META = new ConcurrentHashMap<>();
    /** 保留原始注册顺序的有序列表 */
    private static final List<PackerMeta> ORDERED = new ArrayList<>();

    static {
        loadFromClasspathScan();
    }

    /**
     * 通过类路径扫描自动发现所有带 @PackerMeta 注解的 Packer 实现。
     */
    private static void loadFromClasspathScan() {
        for (Packer packer : PackerScanner.scan()) {
            register(packer);
        }
    }

    /**
     * 手动注册一个 Packer（用于测试或编程式注册）
     */
    public static void register(Packer packer) {
        PackerMeta meta = packer.getClass().getAnnotation(PackerMeta.class);
        if (meta == null) {
            return;
        }
        String key = meta.name().toLowerCase();
        REGISTRY.put(key, packer);
        META.put(key, meta);
        synchronized (ORDERED) {
            ORDERED.add(meta);
        }
    }

    /**
     * 按名称获取 Packer 实例（忽略大小写）
     *
     * @param name packerType 名称
     * @return Packer 实例，未找到返回 null
     */
    public static Packer get(String name) {
        if (name == null || name.trim().isEmpty()) {
            return null;
        }
        return REGISTRY.get(name.trim().toLowerCase());
    }

    /**
     * 按名称获取 Packer 实例（忽略大小写），未找到时抛出明确异常。
     * <p>
     * 供其他 Packer 在委托调用时使用，避免依赖缺失时产生 NPE。
     *
     * @param name packerType 名称
     * @return Packer 实例（非 null）
     * @throws IllegalStateException 若该名称未注册
     */
    public static Packer getOrThrow(String name) {
        Packer packer = get(name);
        if (packer == null) {
            throw new IllegalStateException(
                    "依赖 Packer [" + name + "] 未注册，请检查类路径或 @PackerMeta 注解");
        }
        return packer;
    }

    /**
     * 按名称获取元数据
     */
    public static PackerMeta getMeta(String name) {
        if (name == null || name.trim().isEmpty()) {
            return null;
        }
        return META.get(name.trim().toLowerCase());
    }

    /**
     * 判断某 packerType 是否要求注入器继承 AbstractTranslet
     */
    public static boolean requiresAbstractTranslet(String name) {
        PackerMeta meta = getMeta(name);
        return meta != null && meta.requiresAbstractTranslet();
    }

    /**
     * 获取所有已注册的 Packer 名称列表
     */
    public static List<String> getAllNames() {
        synchronized (ORDERED) {
            return ORDERED.stream()
                    .map(PackerMeta::name)
                    .collect(Collectors.toList());
        }
    }

    /**
     * 按分组层级输出，供前端展示。
     * <p>
     * 返回结构：
     * <ul>
     *   <li>{@code groups}：List&lt;Map&gt;，每项含 groupName + packers 列表</li>
     *   <li>{@code ungrouped}：无分组的 packer 名称列表</li>
     * </ul>
     */
    public static Map<String, Object> getHierarchy() {
        LinkedHashMap<String, List<String>> grouped = new LinkedHashMap<>();
        List<String> ungrouped = new ArrayList<>();

        // 按 order 排序
        List<PackerMeta> sorted;
        synchronized (ORDERED) {
            sorted = new ArrayList<>(ORDERED);
        }
        sorted.sort(Comparator.comparingInt(PackerMeta::order));

        for (PackerMeta meta : sorted) {
            String group = meta.group();
            if (group == null || group.isEmpty()) {
                ungrouped.add(meta.name());
            } else {
                grouped.computeIfAbsent(group, k -> new ArrayList<>()).add(meta.name());
            }
        }

        List<Map<String, Object>> groups = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : grouped.entrySet()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("groupName", e.getKey());
            row.put("packers", e.getValue());
            groups.add(row);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("groups", groups);
        result.put("ungrouped", ungrouped);
        return result;
    }

    /**
     * 获取指定 Packer 支持的混淆步骤 ID 列表（按注解声明顺序）。
     * <p>
     * 空列表表示该 Packer 未声明混淆步骤支持。
     */
    public static List<String> getSupportedObfuscationSteps(String name) {
        PackerMeta meta = getMeta(name);
        if (meta == null || meta.obfuscationSteps().length == 0) {
            return Collections.emptyList();
        }
        return Arrays.asList(meta.obfuscationSteps());
    }

    /**
     * 获取所有已注册 Packer 的混淆步骤声明映射，供 /supported-types 接口使用。
     * <p>
     * 返回：packer 名称 -> 支持的步骤 ID 列表（空列表表示不支持混淆层配置）。
     */
    public static Map<String, List<String>> getPackerObfuscationStepsMap() {
        List<PackerMeta> sorted;
        synchronized (ORDERED) {
            sorted = new ArrayList<>(ORDERED);
        }
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (PackerMeta meta : sorted) {
            result.put(meta.name(),
                meta.obfuscationSteps().length == 0
                    ? Collections.emptyList()
                    : Arrays.asList(meta.obfuscationSteps()));
        }
        return result;
    }

    /**
     * 判断注册表中是否包含指定名称的 Packer
     */
    public static boolean contains(String name) {
        if (name == null || name.trim().isEmpty()) {
            return false;
        }
        return REGISTRY.containsKey(name.trim().toLowerCase());
    }
}
