package io.github.marcofanti.aauth.demo.a2a;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * Deterministic JSON codec for A2A payloads.
 *
 * <p>The bytes produced here are the wire bytes and, once AAuth signing lands, the signed bytes.
 * Callers must send these exact bytes and never let another serializer re-encode the payload,
 * otherwise the RFC 9421 signature base will not match what is on the wire.
 */
public final class A2aJson {

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .serializationInclusion(JsonInclude.Include.NON_NULL)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private A2aJson() {}

    public static byte[] toBytes(Object value) {
        try {
            return MAPPER.writeValueAsBytes(value);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Failed to serialize " + value.getClass().getName(), e);
        }
    }

    public static String toJson(Object value) {
        return new String(toBytes(value), StandardCharsets.UTF_8);
    }

    public static <T> T parse(String json, Class<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to parse JSON as " + type.getName(), e);
        }
    }
}
