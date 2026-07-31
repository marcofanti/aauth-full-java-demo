package io.github.marcofanti.aauth.demo.sca;

import io.github.marcofanti.aauth.demo.a2a.A2aClient;
import io.github.marcofanti.aauth.demo.a2a.RequestSigner;
import io.github.marcofanti.aauth.demo.common.A2aAuthClient;
import io.github.marcofanti.aauth.demo.common.AAuthClientSigner;
import io.github.marcofanti.aauth.demo.common.AAuthInboundVerifier;
import io.github.marcofanti.aauth.demo.common.AgentBootstrap;
import io.github.marcofanti.aauth.demo.common.ManagedIdentity;
import io.github.marcofanti.aauth.demo.common.StableKeys;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ScaConfig {

    private static final Logger log = LoggerFactory.getLogger(ScaConfig.class);

    private static final Set<String> IDENTITY_MODES = Set.of("jwt", "auth-token", "consent");

    /** Registers with the Person Server at startup in every identity-carrying mode. */
    @Bean
    @ConditionalOnExpression("'${demo.aauth.mode:hwk}'.matches('jwt|auth-token|consent')")
    public ManagedIdentity agentIdentity(
            @Value("${demo.person-server-url:http://ps.uma.lab:8765}") String personServerUrl,
            @Value("${demo.aauth.key-dir:.aauth-demo}") String keyDirectory,
            @Value("${spring.application.name}") String agentName) {
        return ManagedIdentity.register(new AgentBootstrap.Config(
                URI.create(personServerUrl), Path.of(keyDirectory), agentName, Duration.ofMinutes(2)));
    }

    /**
     * Gateway to the market-analysis agent. In identity modes it can run the three-party
     * exchange; a consent demand on this internal hop has no UI channel, so it is logged for
     * approval via the Person Server UI or {@code /person} API.
     */
    @Bean
    public MarketAnalysisGateway marketAnalysisGateway(
            @Value("${demo.market-analysis-url:http://gateway.uma.lab:9998/}") String marketAnalysisUrl,
            @Value("${demo.aauth.mode:hwk}") String mode,
            ObjectProvider<ManagedIdentity> identity) {
        URI endpoint = URI.create(marketAnalysisUrl);
        if (IDENTITY_MODES.contains(mode)) {
            A2aAuthClient client = new A2aAuthClient(endpoint, identity.getObject()::current);
            return text -> client.sendText(
                    text,
                    (url, code) ->
                            log.info("Market-analysis hop needs consent: approve at {} with code {}", url, code));
        }
        RequestSigner signer = "hwk".equals(mode) ? AAuthClientSigner.ephemeral() : RequestSigner.none();
        A2aClient plain = new A2aClient(signer);
        return text -> plain.sendText(endpoint, text);
    }

    /** Inbound AAuth verification; absent when {@code demo.aauth.mode=off}. */
    @Bean
    @ConditionalOnExpression("!'${demo.aauth.mode:hwk}'.equals('off')")
    public AAuthInboundVerifier inboundVerifier(
            @Value("${demo.agent-url:http://gateway.uma.lab:9999/}") String agentUrl,
            @Value("${demo.aauth.mode:hwk}") String mode,
            @Value("${demo.aauth.scope:supply-chain:optimize}") String scope,
            @Value("${demo.person-server-url:http://ps.uma.lab:8765}") String personServerUrl,
            @Value("${demo.aauth.key-dir:.aauth-demo}") String keyDirectory,
            @Value("${spring.application.name}") String serviceName) {
        return AAuthInboundVerifier.forMode(
                URI.create(agentUrl),
                mode,
                scope,
                personServerUrl,
                StableKeys.loadOrCreate(Path.of(keyDirectory), serviceName + "-resource"),
                "sca-rsk-1");
    }
}
