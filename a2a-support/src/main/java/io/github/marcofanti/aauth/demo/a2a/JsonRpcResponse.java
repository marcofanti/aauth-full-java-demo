package io.github.marcofanti.aauth.demo.a2a;

/** JSON-RPC 2.0 response envelope; exactly one of {@code result} and {@code error} is set. */
public record JsonRpcResponse(String jsonrpc, String id, A2aMessage result, JsonRpcError error) {

    public static JsonRpcResponse success(String id, A2aMessage result) {
        return new JsonRpcResponse("2.0", id, result, null);
    }

    public static JsonRpcResponse failure(String id, int code, String message) {
        return new JsonRpcResponse("2.0", id, null, new JsonRpcError(code, message));
    }
}
