package org.leo.core.ai;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * AI 工具注册表：集中管理工具名称 → 工具类型 的映射关系，
 * 以及每个工具类型的元数据（中文标签、影响面描述、适用上下文）。
 *
 * <p>此类替代原先散落在三处的工具定义：
 * <ul>
 *   <li>{@code AgentToolInterceptor} 中的 7 个硬编码 {@code Set} 常量</li>
 *   <li>{@code UserAiPolicyService.VALID_TOOL_TYPES} 集合</li>
 *   <li>前端 {@code AiToolPolicySettings.vue} 的 {@code TOOL_TYPES} 数组</li>
 * </ul>
 *
 * <p><b>不可实例化</b>，所有方法均为静态。
 * <p><b>不是 Spring Bean</b>，直接通过类名调用静态方法，避免注入复杂度。
 */
public final class AiToolRegistry {

    // ── 上下文常量 ─────────────────────────────────────────────────────────────

    /** Puppet 节点侧上下文标识（面向目标主机操作的 AI 会话）。 */
    public static final String CTX_PUPPET   = "puppet";
    /** 平台侧上下文标识（面向平台管理操作的 AI 会话）。 */
    public static final String CTX_PLATFORM = "platform";

    // ── 工具类型元数据 ─────────────────────────────────────────────────────────

    /**
     * 工具类型元数据。
     *
     * @param key        类型 key（与会话授权和数据库 allowed_tool_types 保持一致）
     * @param label      中文标签（前端确认面板展示用）
     * @param impactDesc 操作影响面描述（中文，展示给用户用于确认授权）
     * @param contexts   适用的 AI 上下文集合（"puppet" / "platform"）
     * @param adminOnly  是否仅 admin 角色可用
     */
    public record ToolCategory(
            String key,
            String label,
            String impactDesc,
            Set<String> contexts,
            boolean adminOnly
    ) {}

    // ── 类别定义（有序，插入顺序 = 前端展示顺序） ──────────────────────────────

    private static final Map<String, ToolCategory> CATEGORY_MAP;

    static {
        Map<String, ToolCategory> m = new LinkedHashMap<>();
        m.put("command", new ToolCategory(
                "command", "命令执行",
                "将在 puppet 侧执行或管理系统命令，可能读取敏感信息、改变进程状态或产生目标主机负载。",
                Set.of(CTX_PUPPET), false));
        m.put("file_write", new ToolCategory(
                "file_write", "文件写入",
                "将创建、写入、压缩、解压、上传或下载文件，可能改变 puppet 侧或平台侧文件系统状态。",
                Set.of(CTX_PUPPET), false));
        m.put("scan", new ToolCategory(
                "scan", "网络扫描",
                "将从 puppet 侧发起网络探测或改变扫描任务状态，可能产生网络流量和告警。",
                Set.of(CTX_PUPPET), false));
        m.put("sql_write", new ToolCategory(
                "sql_write", "SQL 写入",
                "将对数据库执行写入、结构变更、权限变更或存储过程类 SQL，可能改变业务数据或数据库结构。",
                Set.of(CTX_PUPPET), false));
        m.put("script", new ToolCategory(
                "script", "脚本执行",
                "将在平台侧脚本引擎执行代码，可能消耗平台资源或访问平台侧运行时能力。",
                Set.of(CTX_PUPPET), true));
        m.put("plugin", new ToolCategory(
                "plugin", "插件调用",
                "将调用平台 Java 插件并绑定当前 puppet 会话执行，插件行为取决于其实现，可能影响目标环境。",
                Set.of(CTX_PUPPET), true));
        m.put("container", new ToolCategory(
                "container", "容器管理",
                "将卸载 Web 容器或 Spring Web 挂载组件，可能改变目标应用运行状态。",
                Set.of(CTX_PUPPET), true));
        m.put("platform_write", new ToolCategory(
                "platform_write", "平台写操作",
                "将新增、修改或删除平台侧用户、团队、Puppet、Disguise、插件或指纹配置。",
                Set.of(CTX_PLATFORM), false));
        CATEGORY_MAP = Collections.unmodifiableMap(m);
    }

    // ── 工具名 → 类型 映射 ─────────────────────────────────────────────────────

    /**
     * 工具名（规范化小写）→ 类型 key 的映射表。
     *
     * <p>注意：{@code execsql} 不在此表中——SQL 写入检测依赖 SQL 内容分析，
     * 由工具拦截逻辑在拦截时单独处理。
     */
    private static final Map<String, String> TOOL_TO_CATEGORY;

    static {
        Map<String, String> m = new HashMap<>();

        // command — 高影响命令（写入 / 执行 / 停止）
        // 注意：只读侦察工具（collectjavaprocessargs / searchtext / getenvvars /
        //       getnetworkinfo / getprocesslist / getlisteningports / getcurrentuserinfo）
        //       不注册到此类别，由拦截器直接放行，无需用户确认。
        for (String t : new String[]{
                "creat", "write", "stop",
                "execonce", "startcommand", "stopcommand", "execwithtimeout"
        }) m.put(t, "command");

        // file_write — 文件系统变更
        for (String t : new String[]{
                "creatfile", "fileuploadchunk",
                "compressfile", "decompressfile",
                "startuploadtask", "startdownloadtask",
                "editfile", "creatdir", "copyfile", "movefile", "deletefile"
        }) m.put(t, "file_write");

        // scan — 网络扫描
        for (String t : new String[]{
                "startscanport", "pausescanport",
                "resumescanport", "stopscanport", "scanreachablehost"
        }) m.put(t, "scan");

        // container — Catalina 卸载操作（仅 admin）
        for (String t : new String[]{
                "unloadfilter", "unloadservlet", "unloadvalve",
                "unloadlistener", "unloadcontroller", "unloadinterceptor"
        }) m.put(t, "container");

        // script — 平台脚本引擎（仅 admin）
        m.put("execscript", "script");

        // plugin — Java 插件调用（仅 admin）
        m.put("invokejavaplugin", "plugin");

        // platform_write — 平台侧写操作
        for (String t : new String[]{
                "adduser",     "updateuser",     "deleteuser",
                "addteam",     "updateteam",     "deleteteam",
                "addpuppet",   "updatepuppet",   "deletepuppet",
                "adddisguise", "updatedisguise", "deletedisguise",
                "addplugin",   "updateplugin",   "deleteplugin",
                "savefingerprint", "deletefingerprint"
        }) m.put(t, "platform_write");

        TOOL_TO_CATEGORY = Collections.unmodifiableMap(m);
    }

    private AiToolRegistry() {}

    // ── 查询方法 ───────────────────────────────────────────────────────────────

    /**
     * 根据规范化工具名（小写）查找所属类别 key。
     *
     * <p>不包含 {@code execsql}，SQL 写入检测由调用方在内容层处理。
     *
     * @param normalizedToolName 工具名（小写，已 trim）
     * @return 类别 key，未匹配到任何类别时返回 {@code null}
     */
    public static String getCategoryKey(String normalizedToolName) {
        if (normalizedToolName == null) return null;
        return TOOL_TO_CATEGORY.get(normalizedToolName);
    }

    /**
     * 根据类别 key 获取完整的 {@link ToolCategory} 元数据。
     *
     * @param key 类别 key（如 "command"、"file_write"）
     * @return 类别元数据，未找到时返回 {@code null}
     */
    public static ToolCategory getCategory(String key) {
        return CATEGORY_MAP.get(key);
    }

    /**
     * 获取所有类别 key 的集合（不可变）。
     * 用于替代 {@code UserAiPolicyService.VALID_TOOL_TYPES}。
     */
    public static Set<String> allCategoryKeys() {
        return CATEGORY_MAP.keySet();
    }

    /**
     * 获取指定上下文下所有类别 key 的集合（不可变）。
     *
     * @param context 上下文标识：{@link #CTX_PUPPET} 或 {@link #CTX_PLATFORM}
     */
    public static Set<String> categoryKeysForContext(String context) {
        if (context == null) return Collections.emptySet();
        Set<String> result = new HashSet<>();
        for (ToolCategory cat : CATEGORY_MAP.values()) {
            if (cat.contexts().contains(context)) {
                result.add(cat.key());
            }
        }
        return Collections.unmodifiableSet(result);
    }

    /**
     * 获取指定上下文下所有 {@link ToolCategory}（按插入顺序，不可变）。
     *
     * @param context 上下文标识：{@link #CTX_PUPPET} 或 {@link #CTX_PLATFORM}
     */
    public static Map<String, ToolCategory> categoriesForContext(String context) {
        if (context == null) return Collections.emptyMap();
        Map<String, ToolCategory> result = new LinkedHashMap<>();
        for (Map.Entry<String, ToolCategory> entry : CATEGORY_MAP.entrySet()) {
            if (entry.getValue().contexts().contains(context)) {
                result.put(entry.getKey(), entry.getValue());
            }
        }
        return Collections.unmodifiableMap(result);
    }

    /**
     * 获取指定类别的中文标签，类别不存在时返回 key 本身。
     */
    public static String getLabel(String key) {
        ToolCategory cat = CATEGORY_MAP.get(key);
        return cat != null ? cat.label() : key;
    }

    /**
     * 获取指定类别的影响面描述，类别不存在时返回 {@code null}。
     */
    public static String getImpactDesc(String key) {
        ToolCategory cat = CATEGORY_MAP.get(key);
        return cat != null ? cat.impactDesc() : null;
    }
}
