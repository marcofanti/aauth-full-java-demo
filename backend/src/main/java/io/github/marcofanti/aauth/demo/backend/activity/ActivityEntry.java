package io.github.marcofanti.aauth.demo.backend.activity;

import java.time.Instant;

/** One line in the activity feed shown by the UI. */
public record ActivityEntry(Instant timestamp, String agent, String message) {}
