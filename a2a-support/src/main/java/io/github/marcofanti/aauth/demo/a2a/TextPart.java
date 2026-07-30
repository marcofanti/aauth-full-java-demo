package io.github.marcofanti.aauth.demo.a2a;

/** A2A text part; {@code kind} is always {@code "text"} in this demo. */
public record TextPart(String kind, String text) {

    public static TextPart of(String text) {
        return new TextPart("text", text);
    }
}
