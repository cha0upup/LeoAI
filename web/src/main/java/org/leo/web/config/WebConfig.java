package org.leo.web.config;

import org.leo.ai.agent.AiAgentFactory;
import org.leo.ai.channel.AiModelFailoverService;
import org.leo.ai.channel.DynamicModelProvider;
import org.leo.web.adapter.ai.PlatformNodeAiBridgeTools;
import org.leo.web.adapter.ai.ShellGeneratorTools;
import org.leo.web.application.ai.PlatformAiAgentRegistry;
import org.leo.web.interceptor.BootstrapReadinessInterceptor;
import org.leo.web.interceptor.LoginInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.ServletListenerRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Arrays;
import java.util.List;

@Configuration
public class WebConfig implements WebMvcConfigurer {
    private final LoginInterceptor loginInterceptor;
    private final BootstrapReadinessInterceptor readinessInterceptor;
    private final String[] allowedOrigins;

    public WebConfig(LoginInterceptor loginInterceptor, BootstrapReadinessInterceptor readinessInterceptor,
                     @Value("${leo.web.cors.allowed-origins:http://localhost:3000,http://127.0.0.1:3000}")
                     String allowedOrigins) {
        this.loginInterceptor = loginInterceptor;
        this.readinessInterceptor = readinessInterceptor;
        this.allowedOrigins = parseAllowedOrigins(allowedOrigins);
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOrigins(allowedOrigins)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders(HttpHeaders.RETRY_AFTER)
                .allowCredentials(true)
                .maxAge(3600);
    }

    static String[] parseAllowedOrigins(String configured) {
        String[] origins = Arrays.stream(configured == null ? new String[0] : configured.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .distinct()
                .toArray(String[]::new);
        if (Arrays.asList(origins).contains("*")) {
            throw new IllegalArgumentException("leo.web.cors.allowed-origins 禁止在携带凭据时使用通配符");
        }
        return origins;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(readinessInterceptor)
                .addPathPatterns("/platform/**", "/agent-node/**");
        registry.addInterceptor(loginInterceptor)
                .addPathPatterns("/platform/**", "/agent-node/**")
                .excludePathPatterns(
                        "/platform/user/login",
                        "/platform/user/status",
                        "/platform/setup"
                );
    }

    /** 注册 HTTP Session 销毁监听器，用于自动清理平台侧 AI 状态。 */
    @Bean
    public ServletListenerRegistrationBean<PlatformAiSessionListener> platformAiSessionListener() {
        return new ServletListenerRegistrationBean<>(new PlatformAiSessionListener());
    }

    /** 在组合根绑定平台工具，运行时注册表只接收 Agent 创建函数。 */
    @Bean
    public static PlatformAiAgentRegistry platformAiAgentRegistry(
            AiAgentFactory agentFactory, DynamicModelProvider modelProvider,
            AiModelFailoverService failoverService, PlatformNodeAiBridgeTools bridgeTools,
            ShellGeneratorTools shellGeneratorTools) {
        return new PlatformAiAgentRegistry(modelProvider, failoverService, runtime ->
                agentFactory.createPlatformAiAgent(runtime.streamingModel(),
                        runtime.supportsFunctionCalling(), runtime.contextWindowTokens(),
                        List.of(shellGeneratorTools, bridgeTools)));
    }
}
