package io.github.marcofanti.aauth.demo.common;

import io.github.marcofanti.aauth.agent.TokenExchange;
import io.github.marcofanti.aauth.demo.a2a.A2aClient;
import io.github.marcofanti.aauth.demo.a2a.A2aClientException;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A2A client with the AAuth three-party exchange built in. Calls are signed {@code scheme=jwt}
 * with the agent token (or a previously obtained auth token). On a 401 carrying an
 * {@code AAuth-Requirement} resource token, the token is exchanged at the Person Server —
 * blocking through user consent when the resource demands it ({@code onInteraction} surfaces
 * the consent URL and code) — and the call is retried once with the resulting
 * {@code aa-auth+jwt}.
 *
 * <p>The identity is read per call (see {@link ManagedIdentity}); when it rotates, the cached
 * auth token is dropped — its {@code cnf.jwk} binds the previous ephemeral key.
 */
public final class A2aAuthClient {

    private static final Logger log = LoggerFactory.getLogger(A2aAuthClient.class);

    private final URI endpoint;
    private final Supplier<AgentBootstrap.Identity> identitySupplier;
    private volatile String authToken;
    private volatile AgentBootstrap.Identity authTokenIdentity;

    public A2aAuthClient(URI endpoint, Supplier<AgentBootstrap.Identity> identitySupplier) {
        this.endpoint = endpoint;
        this.identitySupplier = identitySupplier;
    }

    public String sendText(String text, BiConsumer<String, String> onInteraction) throws A2aClientException {
        AgentBootstrap.Identity identity = identitySupplier.get();
        String cached = authToken;
        if (cached != null && authTokenIdentity != identity) {
            log.info("Agent identity rotated; dropping cached auth token for {}", endpoint);
            authToken = null;
            cached = null;
        }
        try {
            return send(identity, text, cached != null ? cached : identity.agentToken());
        } catch (A2aClientException e) {
            String resourceToken = resourceTokenFrom(e);
            if (resourceToken == null) {
                throw e;
            }
            log.info("Resource {} requires an auth token; exchanging at the Person Server", endpoint);
            String exchanged = TokenExchange.exchangeResourceToken(
                    TokenExchange.Exchange.builder(resourceToken, identity.ephemeralKeyPair(), identity.agentToken())
                            .onInteraction(onInteraction)
                            // The Person Server's HTTP/1.1 parser mishandles the h2c upgrade
                            // the JDK client attempts by default; pin HTTP/1.1.
                            .httpClient(HttpClient.newBuilder()
                                    .version(HttpClient.Version.HTTP_1_1)
                                    .connectTimeout(Duration.ofSeconds(30))
                                    .build())
                            .build());
            authToken = exchanged;
            authTokenIdentity = identity;
            log.info("Obtained auth token for {}; retrying", endpoint);
            return send(identity, text, exchanged);
        }
    }

    private String send(AgentBootstrap.Identity identity, String text, String token) throws A2aClientException {
        return new A2aClient(AAuthClientSigner.withToken(identity.ephemeralKeyPair(), token)).sendText(endpoint, text);
    }

    private static String resourceTokenFrom(A2aClientException e) {
        if (e.statusCode() != 401) {
            return null;
        }
        Map<String, String> headers = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : e.responseHeaders().entrySet()) {
            if (!entry.getValue().isEmpty()) {
                headers.put(entry.getKey(), entry.getValue().get(0));
            }
        }
        return TokenExchange.extractResourceToken(headers);
    }
}
