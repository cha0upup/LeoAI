package org.leo.core.util.request;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;

public class ClassNameGenerator {

    private static final Set<String> generatedClassNames = new HashSet<String>();

    /**
     * Component 使用独立的应用类名画像，避免把保留命名空间、Servlet 名称和
     * Java 8 lambda 后缀混在同一个 Java 6 class 名中。每个数组下标代表一个
     * 完整且一致的应用包族，同一 sessionKey 始终选择同一包族。
     */
    private static final String[] COMPONENT_PACKAGE_FAMILIES = {
            "com.vertex.platform.web",
            "org.riverstone.application.runtime",
            "net.clearwater.service.core",
            "com.blueoak.framework.support",
            "org.highland.platform.internal",
            "com.redwood.application.web"
    };
    private static final String[] COMPONENT_PACKAGE_LEAVES = {
            "context", "support", "adapter", "handler", "resolver", "model", "service", "util"
    };
    private static final String[] COMPONENT_CLASS_PREFIXES = {
            "Default", "Standard", "Local", "Simple", "Generic", "Shared", "Internal", "Base"
    };
    private static final String[] COMPONENT_CLASS_SUBJECTS = {
            "Request", "Response", "Session", "Context", "Resource", "Message", "Endpoint", "Runtime",
            "Service", "Configuration", "Application", "Operation", "Connection", "Process", "Task", "State"
    };
    private static final String[] COMPONENT_CLASS_ROLES = {
            "Handler", "Resolver", "Adapter", "Provider", "Processor", "Manager", "Controller", "Coordinator",
            "Dispatcher", "Accessor", "Registry", "Factory", "Builder", "Support", "Helper", "Listener"
    };
    private static final String[] COMPONENT_CLASS_QUALIFIERS = {
            "Context", "State", "Data", "Lifecycle", "Resource", "Message", "Property", "Configuration",
            "Session", "Request", "Response", "Task", "Event", "Service", "Runtime", "Metadata"
    };

    public static String generateServletStyleClassName() {
        Random random = GenerationRandom.current();
        String className;
        do {
            className = COMPONENT_PACKAGE_FAMILIES[random.nextInt(COMPONENT_PACKAGE_FAMILIES.length)]
                    + "." + COMPONENT_PACKAGE_LEAVES[random.nextInt(COMPONENT_PACKAGE_LEAVES.length)]
                    + "." + COMPONENT_CLASS_PREFIXES[random.nextInt(COMPONENT_CLASS_PREFIXES.length)]
                    + COMPONENT_CLASS_SUBJECTS[random.nextInt(COMPONENT_CLASS_SUBJECTS.length)]
                    + COMPONENT_CLASS_QUALIFIERS[random.nextInt(COMPONENT_CLASS_QUALIFIERS.length)]
                    + COMPONENT_CLASS_ROLES[random.nextInt(COMPONENT_CLASS_ROLES.length)];
        } while (!GenerationRandom.isSeeded() && generatedClassNames.contains(className));
        if (!GenerationRandom.isSeeded()) {
            generatedClassNames.add(className);
        }
        return className;
    }

    /**
     * 为 Java Component 生成会话稳定、组件间离散的应用风格类名。
     *
     * <p>包族只由 sessionKey 决定，因此同一节点上的所有 Component 命名风格一致；
     * 包叶与类名由 sessionKey + componentName 决定，从而在重试和服务重启后保持稳定。</p>
     */
    public static String generateComponentClassName(String sessionKey, String componentName) {
        byte[] sessionDigest = digest(normalize(sessionKey) + "|component-profile");
        byte[] componentDigest = digest(normalize(sessionKey) + "|" + normalize(componentName)
                + "|component-class");

        String packageName = COMPONENT_PACKAGE_FAMILIES[index(sessionDigest, 0,
                COMPONENT_PACKAGE_FAMILIES.length)]
                + "." + COMPONENT_PACKAGE_LEAVES[index(componentDigest, 0,
                COMPONENT_PACKAGE_LEAVES.length)];
        String simpleName = COMPONENT_CLASS_PREFIXES[index(componentDigest, 1,
                COMPONENT_CLASS_PREFIXES.length)]
                + COMPONENT_CLASS_SUBJECTS[index(componentDigest, 2,
                COMPONENT_CLASS_SUBJECTS.length)]
                + COMPONENT_CLASS_QUALIFIERS[index(componentDigest, 3,
                COMPONENT_CLASS_QUALIFIERS.length)]
                + COMPONENT_CLASS_ROLES[index(componentDigest, 4,
                COMPONENT_CLASS_ROLES.length)];
        return packageName + "." + simpleName;
    }

    /** 返回适合驱动确定性成员变体的稳定 64 位 seed。 */
    public static long stableSeed(String value) {
        byte[] digest = digest(normalize(value));
        long seed = 0L;
        for (int i = 0; i < 8; i++) {
            seed = (seed << 8) | (digest[i] & 0xffL);
        }
        return seed;
    }

    private static String normalize(String value) {
        return value == null || value.trim().isEmpty() ? "bootstrap" : value.trim();
    }

    private static int index(byte[] digest, int offset, int length) {
        return (digest[offset] & 0xff) % length;
    }

    private static byte[] digest(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }


    private static final String[] METHOD_PREFIXES = {
        "get", "set", "is", "has", "perform", "on", "run", "handle",
        "process", "check", "load", "update", "init", "build",
        "create", "parse", "read", "write", "flush", "reset",
        "apply", "invoke", "execute", "refresh", "resolve",
        "convert", "format", "validate", "prepare", "dispatch"
    };
    /** 生成一个不在 used 集合中的方法名，并将其加入 used */
    public static String randomMethodName(Set<String> used) {
        Random random = GenerationRandom.current();
        for (int i = 0; i < METHOD_PREFIXES.length * 2; i++) {
            String name = METHOD_PREFIXES[random.nextInt(METHOD_PREFIXES.length)];
            if (used.add(name)) return name;
        }
        while (true) {
            String name = METHOD_PREFIXES[random.nextInt(METHOD_PREFIXES.length)]
                    + Integer.toString(random.nextInt(1296), 36);
            if (used.add(name)) return name;
        }
    }

    // 紧凑的业务风格字段名，优先降低成员名常量池开销。
    // 排除了 JSP 隐式对象名（session, config 等），避免在 JSP scriptlet
    // 中声明的局部变量与隐式对象冲突导致编译/运行异常。
    private static final String[] FIELD_SINGLE = {
        "params", "results", "context", "payload", "cache",
        "registry", "handler", "manager", "store", "stream",
        "pool", "queue", "holder", "provider", "wrapper",
        "delegate", "loader", "factory", "buffer", "table",
        "service", "router", "resolver", "dispatcher", "tracker"
    };

    /**
     * JSP scriptlet 隐式对象名和 Java 关键字，不得用作随机字段名。
     * 作为安全网：即使 FIELD_SINGLE 被修改引入这些名称，也不会被选中。
     */
    private static final Set<String> RESERVED_FIELD_NAMES = java.util.Collections.unmodifiableSet(
            new HashSet<>(java.util.Arrays.asList(
                    "session", "config", "request", "response", "application",
                    "pageContext", "out", "page", "exception",
                    "class", "new", "int", "long", "byte", "char", "boolean",
                    "if", "else", "for", "while", "do", "switch", "case",
                    "default", "try", "catch", "finally", "throw", "throws",
                    "return", "break", "continue", "void", "static", "final",
                    "abstract", "private", "public", "protected", "import",
                    "package", "interface", "extends", "implements", "this",
                    "super", "null", "true", "false", "instanceof"
            )));

    /** 生成一个不在 used 集合中的字段名，并将其加入 used */
    public static String randomFieldName(Set<String> used) {
        Random random = GenerationRandom.current();
        for (int i = 0; i < FIELD_SINGLE.length * 2; i++) {
            String name = FIELD_SINGLE[random.nextInt(FIELD_SINGLE.length)];
            if (!RESERVED_FIELD_NAMES.contains(name) && used.add(name)) return name;
        }
        while (true) {
            String name = FIELD_SINGLE[random.nextInt(FIELD_SINGLE.length)]
                    + Integer.toString(random.nextInt(1296), 36);
            if (used.add(name)) return name;
        }
    }

    // PascalCase 内部类名（用于 JSP 模板中的内部 ClassLoader 子类）
    private static final String[] SIMPLE_CLASS_PREFIXES = {
        "Base", "Abstract", "Generic", "Simple", "Default", "Common", "Basic",
        "Core", "Root", "Local", "Inner", "Dynamic", "Custom", "Shared", "Lazy"
    };
    private static final String[] SIMPLE_CLASS_SUFFIXES = {
        "Loader", "Helper", "Util", "Factory", "Builder", "Parser",
        "Handler", "Resolver", "Manager", "Provider", "Adapter", "Wrapper",
        "Processor", "Executor", "Worker", "Agent", "Delegate", "Accessor"
    };

    /**
     * 生成像内部工具类的随机 PascalCase 名，如 DefaultLoader、CoreHelper。
     * 用于 JSP 模板内部类名随机化，避免 ClassDefiner 固定特征。
     */
    public static String randomSimpleClassName(Set<String> used) {
        Random random = GenerationRandom.current();
        String name;
        do {
            name = SIMPLE_CLASS_PREFIXES[random.nextInt(SIMPLE_CLASS_PREFIXES.length)]
                 + SIMPLE_CLASS_SUFFIXES[random.nextInt(SIMPLE_CLASS_SUFFIXES.length)];
        } while (!used.add(name));
        return name;
    }

}
