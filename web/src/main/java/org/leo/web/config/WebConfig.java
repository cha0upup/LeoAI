package org.leo.web.config;

import org.leo.web.interceptor.LoginInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.servlet.ServletListenerRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {
    @Autowired
    private LoginInterceptor loginInterceptor;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(loginInterceptor)
                .addPathPatterns("/platform/**", "/puppet-node/**")
                .excludePathPatterns(
                        "/platform/user/login",
                        "/platform/user/status"
                );
    }

    /** 注册 HTTP Session 销毁监听器，用于自动清理平台侧 AI 状态。 */
    @Bean
    public ServletListenerRegistrationBean<PlatformAiSessionListener> platformAiSessionListener() {
        return new ServletListenerRegistrationBean<>(new PlatformAiSessionListener());
    }
}
