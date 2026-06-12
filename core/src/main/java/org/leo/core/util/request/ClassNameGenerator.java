package org.leo.core.util.request;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

public class ClassNameGenerator {

    private static final String[] PACKAGE_PART_1 = {"javax", "org", "com", "net", "sun", "edu", "info", "cn"};
    private static final String[] PACKAGE_PART_2 = {"access", "file","https","servlet", "google", "http", "resources", "utils", "sql", "websocket", "jsp", "modeler", "f1le", "codec", "apache", "net", "springframework", "i0", "pools"};
    private static final String[] PACKAGE_PART_3 = {"javax", "json","list","jmx","file", "number", "fasterxml", "servlet", "api", "http", "descriptor", "server", "services","jsp", "tomcat", "log", "modeler", "buf", "binary", "ibatis", "beans", "junit", "jdbc", "pool", "jmx", "stream", "xml"};
    private static final String[] BASE_CLASS_NAMES = {"CurrencyStyleFormatter", "Path", "CSSUtil", "JsUtil", "JsonUtil", "MathUtil", "JsonParseException", "Status", "Utils", "HttpUtils", "Cookies", "Cloneables", "Serializables", "Decoders", "CaptureLogs", "BaseModelMBeans", "FeatureInfos", "Registrys", "MbeansDescriptorsIntrospectionSources", "Asn2Parser", "ConfigFileLoaders", "GenericNamingResourcesFactorys", "JSONUtil"};
    private static final String[] LAMBDA_NAMES = {"$$Lambda$1", "$$Lambda$2", "$$Lambda$3", "$$Lambda$4", "$$Lambda$5", "$$Lambda$6", "$$Lambda$7", "$$Lambda$8", "$$Lambda$9", "$$Lambda$10"};
    private static final Set<String> generatedClassNames = new HashSet<String>();

    public static String generateServletStyleClassName() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        String className;
        do {
            className = String.format("%s.%s.%s.%s.%s",
                    PACKAGE_PART_1[random.nextInt(PACKAGE_PART_1.length)],
                    PACKAGE_PART_2[random.nextInt(PACKAGE_PART_2.length)],
                    PACKAGE_PART_3[random.nextInt(PACKAGE_PART_3.length)],
                    BASE_CLASS_NAMES[random.nextInt(BASE_CLASS_NAMES.length)],
                    LAMBDA_NAMES[random.nextInt(LAMBDA_NAMES.length)]);
        } while (generatedClassNames.contains(className)); // 检查是否已存在
        generatedClassNames.add(className); // 添加到已生成集合
        return className;
    }


    private static final String[] METHOD_PREFIXES = {
        "get", "set", "is", "has", "do", "on", "run", "handle",
        "process", "check", "load", "update", "init", "build",
        "create", "parse", "read", "write", "flush", "reset",
        "apply", "invoke", "execute", "refresh", "resolve",
        "convert", "format", "validate", "prepare", "notify"
    };
    private static final String[] METHOD_SUFFIXES = {
        "Data", "Info", "Config", "State", "Result", "Value",
        "Status", "Context", "Request", "Response", "Param",
        "Cache", "Buffer", "Entry", "Record", "Item", "Node",
        "Token", "Session", "Message", "Event", "Task",
        "Flag", "Index", "Type", "Mode", "Level", "Stage"
    };

    public static String randomMethodName() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        String prefix = METHOD_PREFIXES[random.nextInt(METHOD_PREFIXES.length)];
        String suffix = METHOD_SUFFIXES[random.nextInt(METHOD_SUFFIXES.length)];
        // 小概率加第二段，让名字更自然：getTokenValue / processRequestData
        if (random.nextInt(3) == 0) {
            String mid = METHOD_SUFFIXES[random.nextInt(METHOD_SUFFIXES.length)];
            return prefix + mid + suffix;
        }
        return prefix + suffix;
    }

    /** 生成一个不在 used 集合中的方法名，并将其加入 used */
    public static String randomMethodName(Set<String> used) {
        String name;
        do { name = randomMethodName(); } while (!used.add(name));
        return name;
    }

    // 字段名第一段：名词修饰语
    private static final String[] FIELD_FIRST = {
        "request", "response", "session", "config", "cache", "buffer",
        "token", "context", "state", "data", "param", "result",
        "handler", "service", "module", "loader", "parser", "builder",
        "filter", "channel", "client", "server", "instance", "thread",
        "task", "queue", "pool", "log", "audit", "trace", "metric",
        "plugin", "proxy", "delegate", "adapter", "registry", "store"
    };
    // 字段名第二段：名词
    private static final String[] FIELD_SECOND = {
        "Map", "Cache", "Store", "Registry", "Pool", "Queue",
        "Handler", "Manager", "Loader", "Factory", "Builder",
        "Filter", "Buffer", "Table", "Index", "List", "Set",
        "Key", "Id", "Name", "Type", "Mode", "Flag",
        "Info", "Data", "Context", "State", "Config",
        "Holder", "Provider", "Wrapper", "Helper", "Util"
    };
    // 可直接作字段名的单词（不拼接）
    private static final String[] FIELD_SINGLE = {
        "params", "results", "context", "session", "config",
        "cache", "registry", "handler", "manager", "store",
        "pool", "queue", "holder", "provider", "wrapper",
        "delegate", "loader", "factory", "buffer", "table",
        "service", "router", "resolver", "dispatcher", "tracker"
    };

    /**
     * 生成像正常业务字段的随机名，如 requestMap、sessionCache、params、resultHolder
     */
    public static String randomFieldName() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        // 25% 概率直接用单词
        if (random.nextInt(4) == 0) {
            return FIELD_SINGLE[random.nextInt(FIELD_SINGLE.length)];
        }
        // 其余拼接两段
        String first = FIELD_FIRST[random.nextInt(FIELD_FIRST.length)];
        String second = FIELD_SECOND[random.nextInt(FIELD_SECOND.length)];
        return first + second;
    }

    /** 生成一个不在 used 集合中的字段名，并将其加入 used */
    public static String randomFieldName(Set<String> used) {
        String name;
        do { name = randomFieldName(); } while (!used.add(name));
        return name;
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
        ThreadLocalRandom random = ThreadLocalRandom.current();
        String name;
        do {
            name = SIMPLE_CLASS_PREFIXES[random.nextInt(SIMPLE_CLASS_PREFIXES.length)]
                 + SIMPLE_CLASS_SUFFIXES[random.nextInt(SIMPLE_CLASS_SUFFIXES.length)];
        } while (!used.add(name));
        return name;
    }

    /**
     * 测试方法 - 生成类名示例
     * 注意：此方法仅用于测试，生产环境应使用日志框架
     */
    public static void main(String[] args) {
        // 测试生成 10 个不同的类名
        for (int i = 0; i < 10; i++) {
            // 使用System.out仅用于测试目的
            System.out.println(generateServletStyleClassName());
        }
    }
}
