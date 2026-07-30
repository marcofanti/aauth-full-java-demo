package io.github.marcofanti.aauth.demo.a2a;

/** A2A agent capabilities; this demo is non-streaming throughout. */
public record AgentCapabilities(boolean streaming, boolean pushNotifications, boolean stateTransitionHistory) {

    public static AgentCapabilities none() {
        return new AgentCapabilities(false, false, false);
    }
}
