package io.github.marcofanti.aauth.demo.a2a;

import java.util.List;

/** A2A agent skill descriptor (metadata only; routing is keyword-based in this demo). */
public record AgentSkill(String id, String name, String description, List<String> tags) {}
