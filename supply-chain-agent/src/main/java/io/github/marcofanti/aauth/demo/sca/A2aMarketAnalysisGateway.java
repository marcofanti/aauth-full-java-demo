package io.github.marcofanti.aauth.demo.sca;

import io.github.marcofanti.aauth.demo.a2a.A2aClient;
import io.github.marcofanti.aauth.demo.a2a.A2aClientException;
import io.github.marcofanti.aauth.demo.a2a.RequestSigner;
import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** A2A-backed gateway to the market-analysis agent; the injected {@link RequestSigner} signs the hop. */
@Component
public class A2aMarketAnalysisGateway implements MarketAnalysisGateway {

    private final A2aClient client;
    private final URI endpoint;

    public A2aMarketAnalysisGateway(
            @Value("${demo.market-analysis-url:http://gateway.uma.lab:9998/}") String endpoint,
            RequestSigner outboundSigner) {
        this.client = new A2aClient(outboundSigner);
        this.endpoint = URI.create(endpoint);
    }

    @Override
    public String requestAnalysis(String requestText) throws A2aClientException {
        return client.sendText(endpoint, requestText);
    }
}
