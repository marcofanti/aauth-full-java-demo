package io.github.marcofanti.aauth.demo.backend.optimization;

import java.util.Locale;

/**
 * Lifecycle of an optimization request. INTERACTION_REQUIRED, APPROVAL_PENDING and AUTHORIZING
 * are reached only once the AAuth consent flow lands (demo phase 6); the UI already understands
 * them via {@link #wireName()}.
 */
public enum OptimizationStatus {
    PENDING,
    INTERACTION_REQUIRED,
    APPROVAL_PENDING,
    AUTHORIZING,
    RUNNING,
    COMPLETED,
    FAILED;

    /** Lowercase form used on the REST wire, e.g. {@code interaction_required}. */
    public String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
