package com.sdlc.orchestrator.exec;

import com.sdlc.orchestrator.model.RetryPolicy;

/**
 * The pure decision at the heart of retry -> rollback -> safe-stop, mirroring Step
 * Functions' documented {@code Retry}-then-{@code Catch} ordering:
 *
 * <pre>
 *   non-retryable error?     -&gt; skip to rollback (or safe-stop if none exists)
 *   attempts remaining?      -&gt; RETRY
 *   rollback_action defined? -&gt; ROLLBACK
 *   otherwise                -&gt; SAFE_STOP
 * </pre>
 *
 * <p>Deliberately takes only primitives/the policy — no {@code Run}, no I/O — so it
 * is unit-testable without any executor machinery. {@link GraphExecutor} owns the
 * side effects (state transitions, lineage, actually invoking a rollback handler);
 * this class only owns the decision.
 */
public final class FailureLadder {

    public enum Action { RETRY, ROLLBACK, SAFE_STOP }

    private FailureLadder() {}

    /**
     * @param attemptsSoFar attempts already made, including the one that just failed
     */
    public static Action decide(RetryPolicy policy, int attemptsSoFar, String errorType, boolean hasRollbackAction) {
        boolean retryable = policy.isRetryable(errorType);
        boolean attemptsRemain = attemptsSoFar < policy.maxAttempts();

        if (retryable && attemptsRemain) {
            return Action.RETRY;
        }
        return hasRollbackAction ? Action.ROLLBACK : Action.SAFE_STOP;
    }
}
