package com.sdlc.orchestrator.model;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class RetryPolicyTest {

    @Test
    void firstAttemptHasNoBackoff() {
        RetryPolicy policy = RetryPolicy.defaults();
        assertThat(policy.backoffFor(1)).isEqualTo(0.0);
    }

    @Test
    void backoffDoublesEachAttemptPerTheDocumentedSchedule() {
        // Documented in docs/architecture.md and CLAUDE-CODE-PROMPT.md: attempt 2 ->
        // 2.0s, attempt 3 -> 4.0s, attempt 4 -> 8.0s, with defaults (initial=2.0,
        // coefficient=2.0).
        RetryPolicy policy = RetryPolicy.defaults();
        assertThat(policy.backoffFor(2)).isCloseTo(2.0, within(1e-9));
        assertThat(policy.backoffFor(3)).isCloseTo(4.0, within(1e-9));
        assertThat(policy.backoffFor(4)).isCloseTo(8.0, within(1e-9));
    }

    @Test
    void backoffIsCappedAtMaxIntervalSeconds() {
        RetryPolicy policy = new RetryPolicy(10, 2.0, 2.0, 10.0, Set.of());
        // Uncapped attempt 5 would be 2 * 2^3 = 16s; capped to 10s.
        assertThat(policy.backoffFor(5)).isEqualTo(10.0);
    }

    @Test
    void maxAttemptsOfOneMeansNoRetry() {
        // "1 means no retry" — Temporal's semantics, chosen deliberately over
        // "retries after the first" (see RetryPolicy javadoc).
        RetryPolicy policy = new RetryPolicy(1, 0, 1.0, 0, Set.of("*"));
        assertThat(policy.maxAttempts()).isEqualTo(1);
    }

    @Test
    void unlistedErrorIsRetryable() {
        RetryPolicy policy = RetryPolicy.defaults();
        assertThat(policy.isRetryable("GateFailure")).isTrue();
    }

    @Test
    void listedErrorIsNotRetryable() {
        RetryPolicy policy = RetryPolicy.defaults();
        assertThat(policy.isRetryable("ValidationError")).isFalse();
        assertThat(policy.isRetryable("PolicyViolationError")).isFalse();
        assertThat(policy.isRetryable("HumanRejectedError")).isFalse();
    }

    @Test
    void wildcardMarksEveryErrorNonRetryable() {
        // Used by apply_migration: blind retry of partially-applied DDL can
        // compound damage, so every error type is non-retryable.
        RetryPolicy policy = new RetryPolicy(1, 0, 1.0, 0, Set.of("*"));
        assertThat(policy.isRetryable("AnythingAtAll")).isFalse();
    }

    @Test
    void defaultsMatchTheDocumentedGraphDefaults() {
        RetryPolicy policy = RetryPolicy.defaults();
        assertThat(policy.maxAttempts()).isEqualTo(3);
        assertThat(policy.initialIntervalSeconds()).isEqualTo(2.0);
        assertThat(policy.backoffCoefficient()).isEqualTo(2.0);
        assertThat(policy.maxIntervalSeconds()).isEqualTo(60.0);
        assertThat(policy.nonRetryableErrors())
                .containsExactlyInAnyOrder("ValidationError", "PolicyViolationError", "HumanRejectedError");
    }
}
