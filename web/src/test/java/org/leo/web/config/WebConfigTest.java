package org.leo.web.config;

import org.junit.jupiter.api.Test;
import org.leo.web.interceptor.LoginInterceptor;
import org.leo.web.interceptor.BootstrapReadinessInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.handler.MappedInterceptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class WebConfigTest {

    private static class Registry extends InterceptorRegistry {
        List<Object> interceptors() { return getInterceptors(); }
    }

    @Test
    void onlyTheExactLoginStatusAndSetupPathsArePublic() {
        var registry = new Registry();
        new WebConfig(mock(LoginInterceptor.class), mock(BootstrapReadinessInterceptor.class),
                "http://localhost:3000").addInterceptors(registry);
        var readiness = (MappedInterceptor) registry.interceptors().get(0);
        assertArrayEquals(new String[]{"/platform/**", "/agent-node/**"}, readiness.getIncludePathPatterns());
        org.junit.jupiter.api.Assertions.assertNull(readiness.getExcludePathPatterns());
        var interceptor = (MappedInterceptor) registry.interceptors().get(1);
        assertArrayEquals(new String[]{"/platform/**", "/agent-node/**"}, interceptor.getIncludePathPatterns());
        assertArrayEquals(new String[]{"/platform/user/login", "/platform/user/status", "/platform/setup"},
                interceptor.getExcludePathPatterns());
    }

    @Test
    void parsesAndDeduplicatesExplicitOrigins() {
        assertArrayEquals(
                new String[]{"http://localhost:3000", "https://console.example.com"},
                WebConfig.parseAllowedOrigins(
                        " http://localhost:3000,https://console.example.com,http://localhost:3000 "));
    }

    @Test
    void rejectsCredentialedWildcardOrigin() {
        assertThrows(IllegalArgumentException.class,
                () -> WebConfig.parseAllowedOrigins("*"));
    }
}
