package io.github.marcofanti.aauth.demo.sca;

import io.github.marcofanti.aauth.demo.a2a.A2aClientException;

/** Downstream call to the market-analysis agent; abstracted so tests can stub the hop. */
@FunctionalInterface
public interface MarketAnalysisGateway {

    String requestAnalysis(String requestText) throws A2aClientException;
}
