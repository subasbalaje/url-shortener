package com.sdlc.orchestrator.exec;

import com.sdlc.orchestrator.model.RetryPolicy;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class FailureLadderTest {

    @Test
    void nonRetryableErrorSkipsStraightToRollbackWhenOneExists() {
        RetryPolicy policy = RetryPolicy.defaults(); // ValidationError is non-retryable
        assertThat(FailureLadder.decide(policy, 1, "ValidationError", true)).isEqualTo(FailureLadder.Action.ROLLBACK);
    }

    @Test
    void nonRetryableErrorWithNoRollbackGoesStraightToSafeStop() {
        RetryPolicy policy = RetryPolicy.defaults();
        assertThat(FailureLadder.decide(policy, 1, "ValidationError", false)).isEqualTo(FailureLadder.Action.SAFE_STOP);
    }

    @Test
    void wildcardNonRetryablePolicyAlwaysSkipsRetry() {
        // apply_migration's policy: every error type is non-retryable.
        RetryPolicy policy = new RetryPolicy(1, 0, 1.0, 0, Set.of("*"));
        assertThat(FailureLadder.decide(policy, 1, "AnythingAtAll", true)).isEqualTo(FailureLadder.Action.ROLLBACK);
    }

    @Test
    void retryableErrorWithAttemptsRemainingRetries() {
        RetryPolicy policy = RetryPolicy.defaults(); // maxAttempts=3
        assertThat(FailureLadder.decide(policy, 1, "GateFailure", true)).isEqualTo(FailureLadder.Action.RETRY);
        assertThat(FailureLadder.decide(policy, 2, "GateFailure", true)).isEqualTo(FailureLadder.Action.RETRY);
    }

    @Test
    void retryableErrorWithAttemptsExhaustedRollsBack() {
        RetryPolicy policy = RetryPolicy.defaults(); // maxAttempts=3
        assertThat(FailureLadder.decide(policy, 3, "GateFailure", true)).isEqualTo(FailureLadder.Action.ROLLBACK);
    }

    @Test
    void retryableErrorExhaustedWithNoRollbackSafeStops() {
        RetryPolicy policy = RetryPolicy.defaults();
        assertThat(FailureLadder.decide(policy, 3, "GateFailure", false)).isEqualTo(FailureLadder.Action.SAFE_STOP);
    }
}
