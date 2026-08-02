package io.github.marcofanti.aauth.demo.backend.config;

import io.github.marcofanti.aauth.demo.a2a.A2aClient;
import io.github.marcofanti.aauth.demo.a2a.RequestSigner;
import io.github.marcofanti.aauth.demo.backend.mission.MissionService;
import io.github.marcofanti.aauth.demo.backend.optimization.SupplyChainGateway;
import io.github.marcofanti.aauth.demo.common.A2aAuthClient;
import io.github.marcofanti.aauth.demo.common.AAuthClientSigner;
import io.github.marcofanti.aauth.demo.common.AgentBootstrap;
import io.github.marcofanti.aauth.demo.common.ManagedIdentity;
import io.github.marcofanti.aauth.demo.common.MissionClient;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class BackendConfig {

    /** Registers with the Person Server at startup; only in {@code jwt} mode. */
    @Bean
    @ConditionalOnProperty(name = "demo.aauth.mode", havingValue = "jwt")
    public ManagedIdentity agentIdentity(
            @Value("${demo.person-server-url:http://ps.uma.lab:8765}") String personServerUrl,
            @Value("${demo.aauth.key-dir:.aauth-demo}") String keyDirectory,
            @Value("${spring.application.name}") String agentName) {
        return ManagedIdentity.register(new AgentBootstrap.Config(
                URI.create(personServerUrl), Path.of(keyDirectory), agentName, Duration.ofMinutes(2)));
    }

    /**
     * Gateway to the supply-chain agent. In {@code jwt} mode this is the exchange-capable
     * client (identity signing, 401 → resource token → Person Server exchange, consent-aware);
     * lower modes sign pseudonymously or not at all.
     */
    @Bean
    public SupplyChainGateway supplyChainGateway(
            @Value("${demo.supply-chain-url:http://gateway.uma.lab:9999/}") String supplyChainUrl,
            @Value("${demo.aauth.mode:hwk}") String mode,
            ObjectProvider<ManagedIdentity> identity) {
        URI endpoint = URI.create(supplyChainUrl);
        if ("jwt".equals(mode)) {
            A2aAuthClient client = new A2aAuthClient(endpoint, identity.getObject()::current);
            return client::sendText;
        }
        RequestSigner signer = "hwk".equals(mode) ? AAuthClientSigner.ephemeral() : RequestSigner.none();
        A2aClient plain = new A2aClient(signer);
        return (prompt, onInteraction) -> plain.sendText(endpoint, prompt);
    }

    /** Signed client for the Person Server's mission layer; needs the agent identity. */
    @Bean
    @ConditionalOnProperty(name = "demo.aauth.mode", havingValue = "jwt")
    public MissionClient missionClient(
            @Value("${demo.person-server-url:http://ps.uma.lab:8765}") String personServerUrl,
            ManagedIdentity identity) {
        return new MissionClient(URI.create(personServerUrl), identity::current);
    }

    /** Mission orchestration (propose → per-step permission → audit); only in {@code jwt} mode. */
    @Bean
    @ConditionalOnProperty(name = "demo.aauth.mode", havingValue = "jwt")
    public MissionService missionService(
            MissionClient missionClient,
            SupplyChainGateway gateway,
            io.github.marcofanti.aauth.demo.backend.activity.ActivityService activities,
            ExecutorService optimizationExecutor) {
        return new MissionService(missionClient, gateway, activities, optimizationExecutor);
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
