package io.github.marcofanti.aauth.demo.a2a;

/** JSON-RPC 2.0 request envelope for the single A2A method this demo uses. */
public record JsonRpcRequest(String jsonrpc, String id, String method, MessageSendParams params) {

    public static final String MESSAGE_SEND = "message/send";

    public static JsonRpcRequest messageSend(String id, A2aMessage message) {
        return new JsonRpcRequest("2.0", id, MESSAGE_SEND, new MessageSendParams(message));
    }
}
