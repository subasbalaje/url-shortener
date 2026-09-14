package com.sdlc.orchestrator.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NodeRuntimeTest {

    @Test
    void startsPendingWithNoAttempts() {
        NodeRuntime runtime = new NodeRuntime("implementation");
        assertThat(runtime.state()).isEqualTo(NodeState.PENDING);
        assertThat(runtime.attempt()).isEqualTo(0);
        assertThat(runtime.attempts()).isEmpty();
        assertThat(runtime.firstFailedAt()).isNull();
        assertThat(runtime.recoveredAt()).isNull();
    }

    @Test
    void firstEntryToFailedStartsTheMttrClock() {
        NodeRuntime runtime = new NodeRuntime("implementation");
        runtime.recordTransition(NodeState.RUNNING, "2026-09-13T07:42:00Z");
        runtime.recordTransition(NodeState.FAILED, "2026-09-13T07:42:19Z");

        assertThat(runtime.firstFailedAt()).isEqualTo("2026-09-13T07:42:19Z");
        assertThat(runtime.state()).isEqualTo(NodeState.FAILED);
    }

    @Test
    void secondFailureDoesNotResetTheMttrClock() {
        // MTTR measures the whole failure episode (first failure -> recovery), not
        // the last attempt.
        NodeRuntime runtime = new NodeRuntime("implementation");
        runtime.recordTransition(NodeState.FAILED, "2026-09-13T07:42:19Z");
        runtime.recordTransition(NodeState.RETRYING, "2026-09-13T07:42:19Z");
        runtime.recordTransition(NodeState.RUNNING, "2026-09-13T07:42:21Z");
        runtime.recordTransition(NodeState.FAILED, "2026-09-13T07:42:25Z");

        assertThat(runtime.firstFailedAt()).isEqualTo("2026-09-13T07:42:19Z");
    }

    @Test
    void completingAfterAFailureStopsTheMttrClock() {
        NodeRuntime runtime = new NodeRuntime("implementation");
        runtime.recordTransition(NodeState.FAILED, "2026-09-13T07:42:19Z");
        runtime.recordTransition(NodeState.RETRYING, "2026-09-13T07:42:19Z");
        runtime.recordTransition(NodeState.RUNNING, "2026-09-13T07:42:21Z");
        runtime.recordTransition(NodeState.COMPLETED, "2026-09-13T07:42:43Z");

        assertThat(runtime.recoveredAt()).isEqualTo("2026-09-13T07:42:43Z");
    }

    @Test
    void rollingBackAfterAFailureStopsTheMttrClock() {
        // docs/example-run.md §7: three failed attempts, then rollback completes --
        // recovery via compensation, not retry. mttr_seconds=99.0 there only
        // reconciles if ROLLED_BACK stops the clock same as COMPLETED does.
        NodeRuntime runtime = new NodeRuntime("implementation");
        runtime.recordTransition(NodeState.FAILED, "2026-09-13T07:42:19Z");
        runtime.recordTransition(NodeState.RETRYING, "2026-09-13T07:42:19Z");
        runtime.recordTransition(NodeState.FAILED, "2026-09-13T07:42:41Z");
        runtime.recordTransition(NodeState.ROLLED_BACK, "2026-09-13T07:43:58Z");

        assertThat(runtime.recoveredAt()).isEqualTo("2026-09-13T07:43:58Z");
    }

    @Test
    void completingWithoutAPriorFailureLeavesRecoveredAtNull() {
        // recoveredAt is the MTTR clock STOP; a node that never failed has no
        // recovery episode, so this must stay null, not be set to the completion
        // time.
        NodeRuntime runtime = new NodeRuntime("requirements");
        runtime.recordTransition(NodeState.RUNNING, "2026-09-13T07:41:02Z");
        runtime.recordTransition(NodeState.COMPLETED, "2026-09-13T07:41:16Z");

        assertThat(runtime.firstFailedAt()).isNull();
        assertThat(runtime.recoveredAt()).isNull();
    }

    @Test
    void recoveredAtIsSetOnlyOnce() {
        NodeRuntime runtime = new NodeRuntime("testing");
        runtime.recordTransition(NodeState.FAILED, "t1");
        runtime.recordTransition(NodeState.COMPLETED, "t2");
        // A later, unrelated re-entry to COMPLETED (e.g. after a re-plan cycle) must
        // not overwrite the original recovery timestamp.
        runtime.recordTransition(NodeState.STALE, "t3");
        runtime.recordTransition(NodeState.COMPLETED, "t4");

        assertThat(runtime.recoveredAt()).isEqualTo("t2");
    }

    @Test
    void retryCountTracksEveryEntryToRetrying() {
        NodeRuntime runtime = new NodeRuntime("implementation");
        runtime.recordTransition(NodeState.FAILED, "t1");
        runtime.recordTransition(NodeState.RETRYING, "t1");
        runtime.recordTransition(NodeState.FAILED, "t2");
        runtime.recordTransition(NodeState.RETRYING, "t2");
        runtime.recordTransition(NodeState.COMPLETED, "t3");

        assertThat(runtime.retryCount()).isEqualTo(2);
    }

    @Test
    void attemptRecordCapturesOneExecutionAttempt() {
        AttemptRecord attempt = new AttemptRecord(1, "2026-09-13T07:41:02Z");
        assertThat(attempt.attempt()).isEqualTo(1);
        assertThat(attempt.startedAt()).isEqualTo("2026-09-13T07:41:02Z");
        assertThat(attempt.outcome()).isEqualTo("running");
        assertThat(attempt.endedAt()).isNull();
    }

    @Test
    void attemptRecordCanBeMarkedComplete() {
        AttemptRecord attempt = new AttemptRecord(1, "2026-09-13T07:41:02Z");
        attempt.complete("2026-09-13T07:41:16Z", "success", 14.2);

        assertThat(attempt.endedAt()).isEqualTo("2026-09-13T07:41:16Z");
        assertThat(attempt.outcome()).isEqualTo("success");
        assertThat(attempt.durationSeconds()).isEqualTo(14.2);
    }

    @Test
    void attemptRecordCanBeMarkedFailed() {
        AttemptRecord attempt = new AttemptRecord(1, "2026-09-13T07:42:00Z");
        attempt.fail("2026-09-13T07:42:19Z", "GateFailure", "contract mismatch", 19.0);

        assertThat(attempt.outcome()).isEqualTo("failure");
        assertThat(attempt.errorType()).isEqualTo("GateFailure");
        assertThat(attempt.errorMessage()).isEqualTo("contract mismatch");
    }
}
