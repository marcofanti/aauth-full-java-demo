package io.github.marcofanti.aauth.demo.maa;

import io.github.marcofanti.aauth.demo.common.AAuthInboundVerifier;
import io.github.marcofanti.aauth.signing.keys.KeyPairs;
import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MaaConfig {

    /** Inbound AAuth verification; absent when {@code demo.aauth.mode=off}. */
    @Bean
    @ConditionalOnProperty(name = "demo.aauth.mode", havingValue = "hwk", matchIfMissing = true)
    public AAuthInboundVerifier inboundVerifier(
            @Value("${demo.agent-url:http://gateway.uma.lab:9998/}") String agentUrl) {
        return new AAuthInboundVerifier(URI.create(agentUrl), KeyPairs.generateEd25519(), "maa-rsk-1");
    }
}
