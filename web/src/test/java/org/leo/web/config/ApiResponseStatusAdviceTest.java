package org.leo.web.config;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.leo.core.util.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class ApiResponseStatusAdviceTest {

    private final ApiResponseStatusAdvice advice = new ApiResponseStatusAdvice();

    @ParameterizedTest(name = "{1} returns {2} without changing the body")
    @MethodSource("responses")
    void mirrorsEnvelopeStatusAndLeavesOtherBodiesUntouched(Object body, String path, HttpStatus expected) {
        var servletResponse = new MockHttpServletResponse();
        var response = new ServletServerHttpResponse(servletResponse);

        Object result = advice.beforeBodyWrite(body, null, null, null,
                new ServletServerHttpRequest(new MockHttpServletRequest("GET", path)), response);

        assertSame(body, result);
        assertEquals(expected.value(), servletResponse.getStatus());
    }

    private static Stream<Arguments> responses() {
        return Stream.of(
                Arguments.of(ApiResponse.conflict("资源已存在"), "/test", HttpStatus.CONFLICT),
                Arguments.of(new byte[]{1, 2, 3}, "/download", HttpStatus.OK));
    }
}
