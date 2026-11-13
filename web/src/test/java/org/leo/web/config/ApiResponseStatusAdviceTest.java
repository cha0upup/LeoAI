package org.leo.web.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.leo.core.util.ApiResponse;
import org.leo.web.application.agent.AgentCacheService;
import org.leo.web.application.agent.AgentNodeLifecycleService;
import org.leo.web.application.agent.ReconSummaryService;
import org.leo.web.application.agent.SessionReportService;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class ApiResponseStatusAdviceTest {

    private final ApiResponseStatusAdvice advice = new ApiResponseStatusAdvice();

    @ParameterizedTest
    @MethodSource("applicationResults")
    void applicationResultsPreserveTheExistingJsonEnvelopeAndFields(Object result, String expected) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        var json = mapper.readTree(mapper.writeValueAsBytes(ApiResponse.success(result)));
        assertEquals(200, json.get("code").asInt());
        assertEquals("success", json.get("msg").asText());
        assertEquals(mapper.readTree(expected), json.get("data"));
    }

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

    private static Stream<Arguments> applicationResults() {
        return Stream.of(
                Arguments.of(new AgentCacheService.Status(false, null),
                        "{\"hasCache\":false,\"saveTime\":null}"),
                Arguments.of(new AgentNodeLifecycleService.SessionInfo("s", null, true, List.of("file")),
                        "{\"sessionId\":\"s\",\"projectId\":null,\"cacheMode\":true,\"capabilities\":[\"file\"]}"),
                Arguments.of(new ReconSummaryService.Summary("s", null, false),
                        "{\"sessionId\":\"s\",\"reconSummary\":null,\"hasReconSummary\":false}"),
                Arguments.of(new ReconSummaryService.SummaryUpdate("s", "summary", "updated"),
                        "{\"sessionId\":\"s\",\"reconSummary\":\"summary\",\"msg\":\"updated\"}"),
                Arguments.of(new ReconSummaryService.Digest("s", "digest", true, false, 100, 4096),
                        "{\"sessionId\":\"s\",\"digest\":\"digest\",\"dirty\":true,\"fresh\":false,\"summaryLength\":100,\"threshold\":4096}"),
                Arguments.of(new ReconSummaryService.GeneratedDigest("s", null, "skipped"),
                        "{\"sessionId\":\"s\",\"digest\":null,\"msg\":\"skipped\"}"),
                Arguments.of(new ReconSummaryService.AutoAppendState("s", false, "disabled"),
                        "{\"sessionId\":\"s\",\"autoAppendRecon\":false,\"msg\":\"disabled\"}"),
                Arguments.of(new SessionReportService.Report("s", "report", "report.md"),
                        "{\"sessionId\":\"s\",\"report\":\"report\",\"filename\":\"report.md\"}"));
    }
}
