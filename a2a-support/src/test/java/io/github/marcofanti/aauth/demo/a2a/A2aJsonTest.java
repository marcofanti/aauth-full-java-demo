package io.github.marcofanti.aauth.demo.a2a;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class A2aJsonTest {

    @Test
    void messageSendRequestHasStableWireFormat() {
        JsonRpcRequest request = JsonRpcRequest.messageSend("rpc-1", A2aMessage.userText("msg-1", "hello"));

        assertThat(A2aJson.toJson(request))
                .isEqualTo("{\"jsonrpc\":\"2.0\",\"id\":\"rpc-1\",\"method\":\"message/send\","
                        + "\"params\":{\"message\":{\"kind\":\"message\",\"role\":\"user\","
                        + "\"messageId\":\"msg-1\",\"parts\":[{\"kind\":\"text\",\"text\":\"hello\"}]}}}");
    }

    @Test
    void serializationIsByteStable() {
        JsonRpcRequest request = JsonRpcRequest.messageSend("rpc-1", A2aMessage.userText("msg-1", "hello"));

        assertThat(A2aJson.toBytes(request)).isEqualTo(A2aJson.toBytes(request));
    }

    @Test
    void successResponseOmitsNullError() {
        JsonRpcResponse response = JsonRpcResponse.success("rpc-1", A2aMessage.agentText("msg-2", "report"));

        assertThat(A2aJson.toJson(response)).doesNotContain("error").contains("\"result\"");
    }

    @Test
    void parseToleratesUnknownFields() {
        String json = "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"result\":{\"kind\":\"message\",\"role\":\"agent\","
                + "\"messageId\":\"m\",\"parts\":[{\"kind\":\"text\",\"text\":\"ok\"}],\"taskId\":\"ignored\"}}";

        JsonRpcResponse response = A2aJson.parse(json, JsonRpcResponse.class);

        assertThat(response.result().text()).isEqualTo("ok");
    }

    @Test
    void messageTextJoinsPartsAndIgnoresNonText() {
        A2aMessage message = new A2aMessage(
                "message",
                "agent",
                "m",
                java.util.List.of(TextPart.of("line1"), new TextPart("data", "skipped"), TextPart.of("line2")));

        assertThat(message.text()).isEqualTo("line1\nline2");
    }

    @Test
    void agentCardRoundTrips() {
        AgentCard card = new AgentCard(
                AgentCard.PROTOCOL_VERSION,
                "test-agent",
                "A test agent",
                "http://localhost:9999/",
                "0.1.0",
                AgentCard.TRANSPORT_JSONRPC,
                AgentCapabilities.none(),
                java.util.List.of("text"),
                java.util.List.of("text"),
                java.util.List.of(new AgentSkill("skill-1", "Skill", "Does things", java.util.List.of("tag"))));

        AgentCard parsed = A2aJson.parse(A2aJson.toJson(card), AgentCard.class);

        assertThat(parsed).isEqualTo(card);
        assertThat(parsed.capabilities().streaming()).isFalse();
    }
}
