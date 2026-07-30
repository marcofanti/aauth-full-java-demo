package io.github.marcofanti.aauth.demo.backend.optimization;

import io.github.marcofanti.aauth.demo.a2a.A2aClientException;

/** Call to the supply-chain agent; abstracted so tests can stub the hop. */
@FunctionalInterface
public interface SupplyChainGateway {

    String optimize(String prompt) throws A2aClientException;
}
