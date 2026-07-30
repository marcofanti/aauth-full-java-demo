package io.github.marcofanti.aauth.demo.a2a;

/** Params object for the A2A {@code message/send} JSON-RPC method. */
public record MessageSendParams(A2aMessage message) {}
