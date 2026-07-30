package io.github.marcofanti.aauth.demo.a2a;

import java.util.List;

/** A2A agent card, served at {@code /.well-known/agent-card.json}. */
public record AgentCard(
        String protocolVersion,
        String name,
        String description,
        String url,
        String version,
        String preferredTransport,
        AgentCapabilities capabilities,
        List<String> defaultInputModes,
        List<String> defaultOutputModes,
        List<AgentSkill> skills) {

    public static final String PROTOCOL_VERSION = "0.3.0";
    public static final String TRANSPORT_JSONRPC = "JSONRPC";
    public static final String WELL_KNOWN_PATH = "/.well-known/agent-card.json";
}
