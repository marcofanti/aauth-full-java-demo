package io.github.marcofanti.aauth.demo.backend.mission;

import java.util.Locale;

/** Lifecycle of a mission run. */
public enum MissionStatus {
    PENDING,
    AWAITING_APPROVAL,
    RUNNING,
    INTERACTION_REQUIRED,
    COMPLETED,
    FAILED;

    /** Lowercase form used on the REST wire, e.g. {@code awaiting_approval}. */
    public String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
