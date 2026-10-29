package org.leo.web.interceptor;

import org.junit.jupiter.api.Test;
import org.leo.service.user.SystemInitializationService;
import org.leo.web.config.ApiResponseStatusAdvice;
import org.leo.web.config.GlobalExceptionHandler;
import org.leo.web.controller.platform.SystemSetupController;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class BootstrapReadinessInterceptorTest {
    @Test
    void setupIsClosedUntilAllStartupWorkHasSucceededAndDuringShutdown() throws Exception {
        var availability = mock(ApplicationAvailability.class);
        var initialization = mock(SystemInitializationService.class);
        var readiness = new BootstrapReadinessInterceptor(availability);
        var mvc = MockMvcBuilders.standaloneSetup(new SystemSetupController(initialization))
                .addInterceptors(readiness)
                .setControllerAdvice(new GlobalExceptionHandler(), new ApiResponseStatusAdvice()).build();
        when(availability.getReadinessState()).thenReturn(ReadinessState.REFUSING_TRAFFIC);
        var body = "{\"password\":\"chosen-fixture-password\",\"confirmPassword\":\"chosen-fixture-password\"}";
        mvc.perform(post("/platform/setup").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "2"))
                .andExpect(header().string("Cache-Control", "no-store"));
        verifyNoInteractions(initialization);
        when(availability.getReadinessState()).thenReturn(ReadinessState.ACCEPTING_TRAFFIC);
        mvc.perform(post("/platform/setup").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"));
        verify(initialization).initializeAdmin("chosen-fixture-password", "chosen-fixture-password");
        clearInvocations(initialization);
        when(availability.getReadinessState()).thenReturn(ReadinessState.REFUSING_TRAFFIC);
        mvc.perform(post("/platform/setup").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isServiceUnavailable());
        verifyNoInteractions(initialization);
    }

    @Test
    void preflightDoesNotRequireApplicationReadiness() {
        var request = new MockHttpServletRequest("OPTIONS", "/platform/setup");
        assertTrue(new BootstrapReadinessInterceptor(mock(ApplicationAvailability.class))
                .preHandle(request, new MockHttpServletResponse(), new Object()));
    }
}
