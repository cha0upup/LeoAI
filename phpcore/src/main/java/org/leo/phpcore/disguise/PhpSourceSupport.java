package org.leo.phpcore.disguise;

import org.leo.core.entity.Disguise;

/** Shared PHP source fragments used by PayloadCodec generation and traffic validation. */
public final class PhpSourceSupport {

    private PhpSourceSupport() {
    }

    public static String wireHelpers() {
        // PayloadCodec owns structured-value serialization; traffic functions only wrap its bytes.
        return """
                function leo_binary($value) { return ['$leoBinary' => base64_encode($value)]; }
                function leo_wire_encode($value) {
                    if (is_array($value)) {
                        $result = [];
                        foreach ($value as $key => $item) { $result[$key] = leo_wire_encode($item); }
                        return $result;
                    }
                    return $value;
                }
                function leo_wire_decode($value) {
                    if (is_array($value)) {
                        if (count($value) === 1 && array_key_exists('$leoBinary', $value)) {
                            $decoded = base64_decode((string)$value['$leoBinary'], true);
                            if ($decoded === false) { throw new RuntimeException('Invalid binary field'); }
                            return $decoded;
                        }
                        $result = [];
                        foreach ($value as $key => $item) { $result[$key] = leo_wire_decode($item); }
                        return $result;
                    }
                    return $value;
                }
                """;
    }

    public static String requestDecodeFunction(Disguise disguise) {
        requirePhp(disguise);
        return "function leo_traffic_decode($body) {\n"
                + disguise.getPhpTrafficDecodeBody() + "\n}\n";
    }

    public static String responseEncodeFunction(Disguise disguise) {
        requirePhp(disguise);
        return "function leo_traffic_encode($payload) {\n"
                + disguise.getPhpTrafficEncodeBody() + "\n}\n";
    }

    public static void requirePhp(Disguise disguise) {
        if (disguise == null || !disguise.supportsRuntime("php")) {
            throw new IllegalArgumentException("所选伪装缺少完整 PHP traffic 编解码与平台 Java 实现");
        }
        rejectPhpTags(disguise.getPhpTrafficEncodeBody());
        rejectPhpTags(disguise.getPhpTrafficDecodeBody());
    }

    private static void rejectPhpTags(String source) {
        if (source != null && (source.contains("<?php") || source.contains("?>"))) {
            throw new IllegalArgumentException("PHP 伪装字段只能填写函数体，不能包含 PHP 标签");
        }
    }
}
