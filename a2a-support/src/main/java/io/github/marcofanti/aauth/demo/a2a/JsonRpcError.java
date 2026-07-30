package io.github.marcofanti.aauth.demo.a2a;

/** JSON-RPC 2.0 error object. */
public record JsonRpcError(int code, String message) {

    public static final int INVALID_REQUEST = -32600;
    public static final int METHOD_NOT_FOUND = -32601;
    public static final int INTERNAL_ERROR = -32603;
}
