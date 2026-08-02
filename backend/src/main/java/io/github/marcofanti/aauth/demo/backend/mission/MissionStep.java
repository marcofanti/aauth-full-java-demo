package io.github.marcofanti.aauth.demo.backend.mission;

/**
 * One recorded step of a mission run: the action asked of the Person Server's permission
 * endpoint, what it applied to, and how it ended ({@code granted}, {@code denied},
 * {@code completed}).
 */
public record MissionStep(String action, String detail, String outcome) {}
