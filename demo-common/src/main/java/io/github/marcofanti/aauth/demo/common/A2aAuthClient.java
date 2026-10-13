package io.github.marcofanti.aauth.demo.common;

import io.github.marcofanti.aauth.agent.PersonTokenCache;
import io.github.marcofanti.aauth.agent.TokenExchange;
import io.github.marcofanti.aauth.demo.a2a.A2aClient;
import io.github.marcofanti.aauth.demo.a2a.A2aClientException;
import io.github.marcofanti.aauth.headers.AAuthHeaders;
import io.github.marcofanti.aauth.signing.Jwts;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A2A client with the AAuth draft-11 three-party flow built in. Calls are signed {@code
 * scheme=jwt} with the best token held for the resource: an auth token, else the agent token.
 * The resource's challenges drive the rest:
 *
 * <ol>
 *   <li>{@code requirement=person-token} — fetch (or reuse) a person token for this resource from
 *       the Person Server and retry presenting it (§7.1);
 *   <li>{@code requirement=auth-token} with a resource token — exchange it at the Person Server
 *       together with the token just presented ({@code presented_token}, §7.2.1), blocking through
 *       user consent when the PS defers; retry with the resulting {@code aa-auth+jwt}.
 * </ol>
 *
 * <p>{@code onInteraction} surfaces the consent URL and code when the PS defers the exchange
 * ({@code require:user}). The identity is
 * read per call (see {@link ManagedIdentity}); when it rotates, cached person and auth tokens are
 * dropped — their {@code cnf.jwk} binds the previous ephemeral key (AG-11).
 */
public final class A2aAuthClient {

    private static final Logger log = LoggerFactory.getLogger(A2aAuthClient.class);
    private static final int MAX_CHALLENGE_ROUNDS = 3;
    private static final List<String> CAPABILITIES = List.of("interaction");

    private final URI endpoint;
    private final String resource;
    private final Supplier<AgentBootstrap.Identity> identitySupplier;
    private final PersonTokenCache personTokens = new PersonTokenCache();
    private volatile String authToken;
    private volatile AgentBootstrap.Identity tokensIssuedFor;

    public A2aAuthClient(URI endpoint, Supplier<AgentBootstrap.Identity> identitySupplier) {
        this.endpoint = endpoint;
        this.resource = trimTrailingSlash(endpoint.toString());
        this.identitySupplier = identitySupplier;
    }

    public String sendText(String text, BiConsumer<String, String> onInteraction) throws A2aClientException {
        AgentBootstrap.Identity identity = identitySupplier.get();
        dropTokensIfRotated(identity);
        String presented = authToken != null ? authToken : identity.agentToken();
        for (int round = 0; ; round++) {
            try {
                return send(identity, text, presented);
            } catch (A2aClientException e) {
                AAuthHeaders.ParsedChallenge challenge = challengeFrom(e);
                if (challenge == null || round >= MAX_CHALLENGE_ROUNDS) {
                    throw e;
                }
                presented = answer(challenge, identity, presented, onInteraction, e);
            }
        }
    }

    private String answer(
            AAuthHeaders.ParsedChallenge challenge,
            AgentBootstrap.Identity identity,
            String presented,
            BiConsumer<String, String> onInteraction,
            A2aClientException cause)
            throws A2aClientException {
        if (AAuthHeaders.REQUIRE_PERSON_TOKEN.equals(challenge.requirement())) {
            authToken = null;
            log.info("Resource {} requires a person token", resource);
            return personToken(identity);
        }
        if (challenge.resourceToken() != null) {
            log.info("Resource {} requires an auth token; exchanging at the Person Server", resource);
            String exchanged = TokenExchange.exchangeResourceToken(
                    TokenExchange.Exchange.builder(
                                    challenge.resourceToken(), identity.ephemeralKeyPair(), identity.agentToken())
                            .presentedToken(presented)
                            .onInteraction(onInteraction)
                            .build());
            authToken = exchanged;
            log.info("Obtained auth token for {}; retrying", resource);
            return exchanged;
        }
        throw cause;
    }

    /**
     * First-use approval of a person token (§7.1) is not surfaced through {@code onInteraction}:
     * the demo approves it out of band (scripts/approve-person-tokens.sh), keeping the UI's
     * consent prompt for the {@code require:user} consent this demo showcases.
     */
    private String personToken(AgentBootstrap.Identity identity) {
        String cached = personTokens.get(resource, null);
        if (cached != null) {
            return cached;
        }
        String issued = TokenExchange.requestPersonToken(new TokenExchange.PersonTokenRequest(
                        personServerOf(identity.agentToken()),
                        resource,
                        identity.ephemeralKeyPair(),
                        identity.agentToken(),
                        null,
                        CAPABILITIES,
                        null,
                        null,
                        0,
                        null))
                .personToken();
        personTokens.put(resource, null, issued);
        log.info("Obtained person token for {}", resource);
        return issued;
    }

    private void dropTokensIfRotated(AgentBootstrap.Identity identity) {
        if (tokensIssuedFor != identity) {
            if (tokensIssuedFor != null) {
                log.info("Agent identity rotated; dropping cached person and auth tokens for {}", resource);
            }
            authToken = null;
            personTokens.clear();
            tokensIssuedFor = identity;
        }
    }

    private String send(AgentBootstrap.Identity identity, String text, String token) throws A2aClientException {
        return new A2aClient(AAuthClientSigner.withToken(identity.ephemeralKeyPair(), token)).sendText(endpoint, text);
    }

    /** The agent token's {@code ps} claim names the person's PS (AP-03); its issuer otherwise. */
    private static String personServerOf(String agentToken) {
        Jwts.Decoded jwt = Jwts.parse(agentToken);
        String ps = jwt.claim("ps");
        return ps != null ? ps : jwt.claim("iss");
    }

    private static AAuthHeaders.ParsedChallenge challengeFrom(A2aClientException e) {
        if (e.statusCode() != 401) {
            return null;
        }
        for (Map.Entry<String, List<String>> entry : e.responseHeaders().entrySet()) {
            if ("AAuth-Requirement".equalsIgnoreCase(entry.getKey()) && !entry.getValue().isEmpty()) {
                return AAuthHeaders.parseAAuthHeader(entry.getValue().get(0));
            }
        }
        return null;
    }

    private static String trimTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
