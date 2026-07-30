package io.github.marcofanti.aauth.demo.maa;

import io.github.marcofanti.aauth.demo.a2a.A2aJson;
import io.github.marcofanti.aauth.demo.a2a.A2aMessage;
import io.github.marcofanti.aauth.demo.a2a.AgentCapabilities;
import io.github.marcofanti.aauth.demo.a2a.AgentCard;
import io.github.marcofanti.aauth.demo.a2a.AgentSkill;
import io.github.marcofanti.aauth.demo.a2a.JsonRpcError;
import io.github.marcofanti.aauth.demo.a2a.JsonRpcRequest;
import io.github.marcofanti.aauth.demo.a2a.JsonRpcResponse;
import io.github.marcofanti.aauth.demo.common.AAuthInboundVerifier;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * A2A JSON-RPC endpoint. The body is handled as raw bytes end to end so the verified bytes are
 * exactly the wire bytes. Inbound AAuth verification runs when a verifier bean is present
 * ({@code demo.aauth.mode=hwk}); the agent card stays public either way.
 */
@RestController
public class A2aEndpoint {

    private final MarketAnalyzer analyzer;
    private final AgentCard card;
    private final AAuthInboundVerifier verifier;

    public A2aEndpoint(
            MarketAnalyzer analyzer,
            @Value("${demo.agent-url:http://gateway.uma.lab:9998/}") String agentUrl,
            ObjectProvider<AAuthInboundVerifier> verifierProvider) {
        this.analyzer = analyzer;
        this.card = buildCard(agentUrl);
        this.verifier = verifierProvider.getIfAvailable();
    }

    @PostMapping(value = "/", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> handleJsonRpc(@RequestBody byte[] body, @RequestHeader Map<String, String> headers) {
        if (verifier != null) {
            AAuthInboundVerifier.Verification verification = verifier.verify("POST", "/", headers, body);
            if (!verification.valid()) {
                var challenge = verifier.challenge();
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .header(challenge.headerName(), challenge.headerValue())
                        .body(A2aJson.toJson(Map.of("error", "invalid_signature", "detail", verification.error())));
            }
        }
        return ResponseEntity.ok(dispatch(new String(body, StandardCharsets.UTF_8)));
    }

    private String dispatch(String body) {
        JsonRpcRequest request;
        try {
            request = A2aJson.parse(body, JsonRpcRequest.class);
        } catch (UncheckedIOException e) {
            return A2aJson.toJson(
                    JsonRpcResponse.failure(null, JsonRpcError.INVALID_REQUEST, "Malformed JSON-RPC request"));
        }
        if (!JsonRpcRequest.MESSAGE_SEND.equals(request.method())) {
            return A2aJson.toJson(JsonRpcResponse.failure(
                    request.id(), JsonRpcError.METHOD_NOT_FOUND, "Unsupported method: " + request.method()));
        }
        String text = request.params() == null || request.params().message() == null
                ? ""
                : request.params().message().text();
        String report = analyzer.analyze(text);
        return A2aJson.toJson(JsonRpcResponse.success(
                request.id(), A2aMessage.agentText(UUID.randomUUID().toString(), report)));
    }

    @GetMapping(value = AgentCard.WELL_KNOWN_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    public String agentCard() {
        return A2aJson.toJson(card);
    }

    private static AgentCard buildCard(String agentUrl) {
        return new AgentCard(
                AgentCard.PROTOCOL_VERSION,
                "Market Analysis Agent",
                "Provides laptop demand analysis, market trend forecasts and demand-pattern modeling",
                agentUrl,
                "0.1.0",
                AgentCard.TRANSPORT_JSONRPC,
                AgentCapabilities.none(),
                List.of("text"),
                List.of("text"),
                List.of(
                        new AgentSkill(
                                "inventory-demand-analysis",
                                "Inventory Demand Analysis",
                                "Projects laptop demand from hiring plans and fleet refresh cycles",
                                List.of("market-analysis", "inventory", "demand")),
                        new AgentSkill(
                                "market-trend-forecasting",
                                "Market Trend Forecasting",
                                "Forecasts headcount growth and laptop pricing trends",
                                List.of("market-analysis", "trends", "forecasting"))));
    }
}
