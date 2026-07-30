package io.github.marcofanti.aauth.demo.backend.config;

import io.github.marcofanti.aauth.demo.a2a.RequestSigner;
import io.github.marcofanti.aauth.demo.common.AAuthClientSigner;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class BackendConfig {

    /** Signs the backend's outbound A2A calls (to the supply-chain agent). */
    @Bean
    public RequestSigner outboundSigner(@Value("${demo.aauth.mode:hwk}") String mode) {
        return "hwk".equals(mode) ? AAuthClientSigner.ephemeral() : RequestSigner.none();
    }

    /** Background executor for optimization runs; each run may block for minutes on user consent. */
    @Bean(destroyMethod = "shutdown")
    public ExecutorService optimizationExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    @Bean
    public WebMvcConfigurer corsConfigurer(
            @Value("${demo.cors-allowed-origins:http://portal.uma.lab:3050}") String allowedOrigins) {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/**")
                        .allowedOrigins(allowedOrigins.split(","))
                        .allowedMethods("GET", "POST", "DELETE", "OPTIONS");
            }
        };
    }
}
