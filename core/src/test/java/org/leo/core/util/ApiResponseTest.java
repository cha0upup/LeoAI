package org.leo.core.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

class ApiResponseTest {

    @Test
    void omitsAbsentDataAndPreservesTheDefaultSuccessEnvelope() {
        Map<String, Object> expected = Map.of("code", 200, "msg", "success");

        assertEquals(expected, ApiResponse.success());
        assertEquals(expected, ApiResponse.success(null));
        assertEquals(expected, ApiResponse.success(null, null));
    }

    @Test
    void retainsTheOriginalPayloadAndExplicitMessage() {
        Map<String, Object> data = Map.of("name", "测试数据");
        Map<String, Object> response = ApiResponse.success("已完成", data);

        assertEquals(Map.of("code", 200, "msg", "已完成", "data", data), response);
        assertSame(data, response.get("data"));
        assertSame(data, ApiResponse.success(data).get("data"));
    }

    @Test
    void preservesExplicitEmptyMessages() {
        assertEquals("", ApiResponse.success("", null).get("msg"));
        assertEquals("", ApiResponse.error(500, "").get("msg"));
    }

    @ParameterizedTest
    @MethodSource("errorEnvelopes")
    void preservesErrorCodesAndOmitsData(Map<String, Object> response, int code, String message) {
        assertEquals(Map.of("code", code, "msg", message), response);
    }

    static Stream<Arguments> errorEnvelopes() {
        return Stream.of(
                Arguments.of(ApiResponse.error("failed"), 500, "failed"),
                Arguments.of(ApiResponse.badRequest("invalid"), 400, "invalid"),
                Arguments.of(ApiResponse.unauthorized("login"), 401, "login"),
                Arguments.of(ApiResponse.forbidden("denied"), 403, "denied"),
                Arguments.of(ApiResponse.notFound("missing"), 404, "missing"),
                Arguments.of(ApiResponse.conflict("exists"), 409, "exists"),
                Arguments.of(ApiResponse.error(ApiResponse.CODE_TOO_MANY_REQUESTS, "busy"), 429, "busy"),
                Arguments.of(ApiResponse.error(400, null), 400, "error"));
    }

    @Test
    void createsIndependentResponseMaps() {
        Map<String, Object> first = ApiResponse.success();
        Map<String, Object> second = ApiResponse.success();
        first.put("msg", "changed");

        assertNotSame(first, second);
        assertEquals("success", second.get("msg"));
    }
}
