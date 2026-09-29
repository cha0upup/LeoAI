package org.leo.core.disguise;

import org.leo.core.entity.Disguise;

import java.util.Map;
import java.util.Set;

/** Current metadata versions for traffic profiles. */
public final class DisguiseProtocol {
    public static final int SCHEMA_VERSION = 3;
    public static final int PROTOCOL_VERSION = 3;
    private static final Set<String> RUNTIMES = Set.of("java", "php");

    private DisguiseProtocol() {
    }

    public static void requireCurrentMetadata(Map<?, ?> definition) {
        if (definition == null
                || !Integer.valueOf(SCHEMA_VERSION).equals(definition.get("schemaVersion"))
                || !Integer.valueOf(PROTOCOL_VERSION).equals(definition.get("protocolVersion"))) {
            throw new IllegalArgumentException("仅支持当前版本的disguise协议");
        }
        requireRuntimes(definition.get("supportedRuntimes"));
    }

    public static void requireCurrent(Disguise disguise) {
        if (disguise == null || !Integer.valueOf(SCHEMA_VERSION).equals(disguise.getSchemaVersion())
                || !Integer.valueOf(PROTOCOL_VERSION).equals(disguise.getProtocolVersion())) {
            throw new IllegalArgumentException("仅支持当前版本的disguise协议");
        }
        requireRuntimes(disguise.getSupportedRuntimes());
        for (String runtime : disguise.getSupportedRuntimes()) {
            if (!disguise.supportsRuntime(runtime)) {
                throw new IllegalArgumentException("disguise缺少" + runtime + "运行时实现");
            }
        }
    }

    private static void requireRuntimes(Object value) {
        if (!(value instanceof Iterable<?> runtimes)) {
            throw new IllegalArgumentException("supportedRuntimes必须是非空运行时列表");
        }
        boolean hasRuntime = false;
        for (Object runtime : runtimes) {
            if (!(runtime instanceof String name) || !RUNTIMES.contains(name)) {
                throw new IllegalArgumentException("不支持的disguise运行时: " + runtime);
            }
            hasRuntime = true;
        }
        if (!hasRuntime) {
            throw new IllegalArgumentException("supportedRuntimes必须是非空运行时列表");
        }
    }
}
