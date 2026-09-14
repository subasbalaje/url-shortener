package com.sdlc.orchestrator.model;

import java.util.Set;

/**
 * Bounded retry configuration, mirroring Temporal's five-field model.
 *
 * <p>{@code maxAttempts} is <em>total tries</em>, so 1 means no retry — the semantics
 * Temporal uses, chosen over "number of retries after the first" because off-by-one
 * confusion in a reliability control is expensive. {@code nonRetryableErrors} encodes
 * the transient-vs-permanent taxonomy declaratively: a validation error will not
 * become valid by waiting. The literal {@code "*"} marks every error non-retryable —
 * used by {@code apply_migration}, where blind retry of partially-applied DDL can
 * compound damage.
 */
public record RetryPolicy(
        int maxAttempts,
        double initialIntervalSeconds,
        double backoffCoefficient,
        double maxIntervalSeconds,
        Set<String> nonRetryableErrors
) {
    public static RetryPolicy defaults() {
        return new RetryPolicy(3, 2.0, 2.0, 60.0,
                Set.of("ValidationError", "PolicyViolationError", "HumanRejectedError"));
    }

    /** Delay before {@code attempt} (1-indexed), capped at {@code maxIntervalSeconds}. */
    public double backoffFor(int attempt) {
        if (attempt <= 1) {
            return 0.0;
        }
        double raw = initialIntervalSeconds * Math.pow(backoffCoefficient, attempt - 2);
        return Math.min(raw, maxIntervalSeconds);
    }

    public boolean isRetryable(String errorType) {
        return !nonRetryableErrors.contains("*") && !nonRetryableErrors.contains(errorType);
    }
}
