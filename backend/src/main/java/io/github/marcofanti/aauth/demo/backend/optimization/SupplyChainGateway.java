package io.github.marcofanti.aauth.demo.backend.optimization;

import io.github.marcofanti.aauth.demo.a2a.A2aClientException;
import java.util.function.BiConsumer;

/**
 * Call to the supply-chain agent; abstracted so tests can stub the hop. When the resource
 * demands user consent, {@code onInteraction} receives the consent URL and code while the
 * call blocks on Person Server polling.
 */
@FunctionalInterface
public interface SupplyChainGateway {

    String optimize(String prompt, BiConsumer<String, String> onInteraction) throws A2aClientException;
}
