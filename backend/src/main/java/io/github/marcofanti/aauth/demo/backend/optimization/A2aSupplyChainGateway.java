package io.github.marcofanti.aauth.demo.backend.optimization;

import io.github.marcofanti.aauth.demo.a2a.A2aClient;
import io.github.marcofanti.aauth.demo.a2a.A2aClientException;
import io.github.marcofanti.aauth.demo.a2a.RequestSigner;
import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** A2A-backed gateway to the supply-chain agent; the injected {@link RequestSigner} signs the hop. */
@Component
public class A2aSupplyChainGateway implements SupplyChainGateway {

    private final A2aClient client;
    private final URI endpoint;

    public A2aSupplyChainGateway(
            @Value("${demo.supply-chain-url:http://gateway.uma.lab:9999/}") String endpoint,
            RequestSigner outboundSigner) {
        this.client = new A2aClient(outboundSigner);
        this.endpoint = URI.create(endpoint);
    }

    @Override
    public String optimize(String prompt) throws A2aClientException {
        return client.sendText(endpoint, prompt);
    }
}
