package io.github.marcofanti.aauth.demo.a2a;

import java.util.List;
import java.util.stream.Collectors;

/** A2A message: role is {@code "user"} for requests and {@code "agent"} for replies. */
public record A2aMessage(String kind, String role, String messageId, List<TextPart> parts) {

    public static A2aMessage userText(String messageId, String text) {
        return new A2aMessage("message", "user", messageId, List.of(TextPart.of(text)));
    }

    public static A2aMessage agentText(String messageId, String text) {
        return new A2aMessage("message", "agent", messageId, List.of(TextPart.of(text)));
    }

    /** Concatenates all text parts, newline-separated. */
    public String text() {
        if (parts == null) {
            return "";
        }
        return parts.stream()
                .filter(part -> "text".equals(part.kind()))
                .map(TextPart::text)
                .collect(Collectors.joining("\n"));
    }
}
