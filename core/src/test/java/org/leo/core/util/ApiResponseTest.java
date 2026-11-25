package org.leo.core.util;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ApiResponseTest {

    @Test
    void omitsAbsentDataAndPreservesTheDefaultSuccessEnvelope() {
        assertEquals(Map.of("code", 200, "msg", "success"), ApiResponse.success());
    }

    @Test
    void preservesAnExplicitMessageAndPayload() {
        Map<String, Object> data = Map.of("name", "测试数据");
        assertEquals(Map.of("code", 200, "msg", "已完成", "data", data), ApiResponse.success("已完成", data));
    }

    @Test
    void preservesCustomErrorCodesAndDefaultsOnlyMissingMessages() {
        assertEquals(Map.of("code", 429, "msg", "error"), ApiResponse.error(429, null));
        assertEquals(Map.of("code", 429, "msg", ""), ApiResponse.error(429, ""));
    }
}
