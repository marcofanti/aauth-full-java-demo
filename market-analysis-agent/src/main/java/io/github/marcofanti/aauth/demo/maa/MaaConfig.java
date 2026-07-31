package io.github.marcofanti.aauth.demo.maa;

import io.github.marcofanti.aauth.demo.common.AAuthInboundVerifier;
import io.github.marcofanti.aauth.demo.common.StableKeys;
import java.net.URI;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * This agent is a pure resource: it verifies inbound requests but makes no outbound A2A calls,
 * so it does not register with the Person Server.
 */
@Configuration
public class MaaConfig {

    /** Inbound AAuth verification; absent when {@code demo.aauth.mode=off}. */
    @Bean
    @ConditionalOnExpression("'${demo.aauth.mode:hwk}'.matches('hwk|jwt|auth-token|consent')")
    public AAuthInboundVerifier inboundVerifier(
            @Value("${demo.agent-url:http://gateway.uma.lab:9998/}") String agentUrl,
            @Value("${demo.aauth.mode:hwk}") String mode,
            @Value("${demo.aauth.scope:market-analysis:analyze}") String scope,
            @Value("${demo.person-server-url:http://ps.uma.lab:8765}") String personServerUrl,
            @Value("${demo.aauth.key-dir:.aauth-demo}") String keyDirectory,
            @Value("${spring.application.name}") String serviceName) {
        return AAuthInboundVerifier.forMode(
                URI.create(agentUrl),
                mode,
                scope,
                personServerUrl,
                StableKeys.loadOrCreate(Path.of(keyDirectory), serviceName + "-resource"),
                "maa-rsk-1");
    }
}
