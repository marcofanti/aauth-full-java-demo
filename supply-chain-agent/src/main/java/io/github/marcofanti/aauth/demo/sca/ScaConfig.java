package io.github.marcofanti.aauth.demo.sca;

import io.github.marcofanti.aauth.demo.a2a.RequestSigner;
import io.github.marcofanti.aauth.demo.common.AAuthClientSigner;
import io.github.marcofanti.aauth.demo.common.AAuthInboundVerifier;
import io.github.marcofanti.aauth.signing.keys.KeyPairs;
import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ScaConfig {

    /** Inbound AAuth verification; absent when {@code demo.aauth.mode=off}. */
    @Bean
    @ConditionalOnProperty(name = "demo.aauth.mode", havingValue = "hwk", matchIfMissing = true)
    public AAuthInboundVerifier inboundVerifier(
            @Value("${demo.agent-url:http://gateway.uma.lab:9999/}") String agentUrl) {
        return new AAuthInboundVerifier(URI.create(agentUrl), KeyPairs.generateEd25519(), "sca-rsk-1");
    }

    /** Signs this agent's outbound A2A calls (to the market-analysis agent). */
    @Bean
    public RequestSigner outboundSigner(@Value("${demo.aauth.mode:hwk}") String mode) {
        return "hwk".equals(mode) ? AAuthClientSigner.ephemeral() : RequestSigner.none();
    }
}
