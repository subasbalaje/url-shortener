package com.sdlc.orchestrator.metrics;

import com.sdlc.orchestrator.model.ApprovalRecord;
import com.sdlc.orchestrator.model.Decision;
import com.sdlc.orchestrator.model.NodeRuntime;
import com.sdlc.orchestrator.model.NodeState;
import com.sdlc.orchestrator.model.Run;
import com.sdlc.orchestrator.model.RunState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class MetricsCollectorTest {

    /** Builds the docs/example-run.md §7 fixture: 2 completed, 1 failed
     *  (rolled back), 3 upstream-failed, 1 skipped, out of 7 total nodes. */
    private Run failureVariantRun() {
        Run run = new Run("r-1", "sdlc-core", "greenfield", "req");
        run.setState(RunState.SAFE_STOPPED);
        run.setSafeStopReason("implementation exhausted retry budget (3 attempts); rollback succeeded");

        addNode(run, "requirements", NodeState.COMPLETED, 1, null, null);
        addNode(run, "design", NodeState.COMPLETED, 1, null, null);
        addNode(run, "apply_migration", NodeState.SKIPPED, 0, null, null);

        NodeRuntime implementation = new NodeRuntime("implementation");
        implementation.recordTransition(NodeState.RUNNING, "t0");
        implementation.recordTransition(NodeState.FAILED, "2026-09-13T07:42:19Z");
        implementation.recordTransition(NodeState.RETRYING, "2026-09-13T07:42:19Z");
        implementation.recordTransition(NodeState.RUNNING, "2026-09-13T07:42:21Z");
        implementation.recordTransition(NodeState.FAILED, "2026-09-13T07:42:41Z");
        implementation.recordTransition(NodeState.RETRYING, "2026-09-13T07:42:41Z");
        implementation.recordTransition(NodeState.RUNNING, "2026-09-13T07:43:00Z");
        implementation.recordTransition(NodeState.FAILED, "2026-09-13T07:43:10Z");
        implementation.recordTransition(NodeState.ROLLED_BACK, "2026-09-13T07:43:58Z");
        implementation.markRolledBack();
        run.nodes().put("implementation", implementation);

        addNode(run, "testing", NodeState.UPSTREAM_FAILED, 0, null, null);
        addNode(run, "documentation", NodeState.UPSTREAM_FAILED, 0, null, null);
        addNode(run, "release_readiness", NodeState.UPSTREAM_FAILED, 0, null, null);

        return run;
    }

    private void addNode(Run run, String id, NodeState finalState, int attempts, String firstFailedAt, String recoveredAt) {
        NodeRuntime runtime = new NodeRuntime(id);
        runtime.recordTransition(finalState, "t");
        run.nodes().put(id, runtime);
    }

    @Test
    void successRateExcludesOnlyUpstreamFailedFromTheDenominator() {
        // docs/example-run.md §7's own stated fixture and formula, per its
        // explanatory text: "the three UPSTREAM_FAILED nodes are excluded from
        // nodes_attempted" -- no separate exclusion of SKIPPED is stated, and only
        // this reading reproduces the documented result of 0.5 (not 2/3).
        // See decision log DEC-0016 for the full reconciliation of this fixture
        // against the graph YAML's own success_rate note.
        Run run = failureVariantRun();
        RunMetrics metrics = MetricsCollector.compute(run, 7, null);

        assertThat(metrics.nodesTotal()).isEqualTo(7);
        assertThat(metrics.nodesCompleted()).isEqualTo(2);
        assertThat(metrics.nodesFailed()).isEqualTo(1);
        assertThat(metrics.nodesUpstreamFailed()).isEqualTo(3);
        assertThat(metrics.nodesSkipped()).isEqualTo(1);
        assertThat(metrics.nodesAttempted()).isEqualTo(4);
        assertThat(metrics.successRate()).isCloseTo(0.5, within(1e-9));
    }

    @Test
    void mttrIsNullWhenThereWereNoFailures() {
        Run run = new Run("r-1", "g", "greenfield", "req");
        addNode(run, "requirements", NodeState.COMPLETED, 1, null, null);
        RunMetrics metrics = MetricsCollector.compute(run, 1, null);

        assertThat(metrics.mttrSeconds()).isNull();
    }

    @Test
    void mttrIsTheMeanFirstFailureToRecoveryAcrossFailureEpisodes() {
        Run run = failureVariantRun(); // one episode: 07:42:19Z -> 07:43:58Z = 99s
        RunMetrics metrics = MetricsCollector.compute(run, 7, null);

        assertThat(metrics.failureEpisodes()).isEqualTo(1);
        assertThat(metrics.mttrSeconds()).isCloseTo(99.0, within(1e-6));
    }

    @Test
    void retryAndRollbackFrequencyAreRatesOverNodesAttempted() {
        Run run = failureVariantRun(); // attempted=4; 2 retries (attempts 2 and 3
                                        // beyond the first); 1 rollback executed
        RunMetrics metrics = MetricsCollector.compute(run, 7, null);

        assertThat(metrics.totalRetryAttempts()).isEqualTo(2);
        assertThat(metrics.retryFrequency()).isCloseTo(2.0 / 4, within(1e-9));
        assertThat(metrics.rollbacksExecuted()).isEqualTo(1);
        assertThat(metrics.rollbackFrequency()).isCloseTo(1.0 / 4, within(1e-9));
    }

    @Test
    void latencyBreakdownSeparatesGateWaitFromRunTime() {
        Run run = new Run("r-1", "g", "greenfield", "req");
        NodeRuntime node = new NodeRuntime("release_readiness");
        node.addTimeRunningSeconds(9.1);
        node.addTimeBlockedSeconds(38.5);
        run.nodes().put("release_readiness", node);

        RunMetrics metrics = MetricsCollector.compute(run, 1, null);

        assertThat(metrics.timeRunningSeconds()).isCloseTo(9.1, within(1e-9));
        assertThat(metrics.timeBlockedOnGateSeconds()).isCloseTo(38.5, within(1e-9));
    }

    @Test
    void parallelEfficiencyIsCriticalPathOverWallClock() {
        Run run = new Run("r-1", "g", "greenfield", "req");
        run.setEndedAt(addSeconds(run.startedAt(), 204.3));
        RunMetrics metrics = MetricsCollector.compute(run, 1, 165.6);

        assertThat(metrics.parallelEfficiency()).isCloseTo(165.6 / 204.3, within(1e-6));
    }

    @Test
    void humanGatesAndApprovalOutcomesAreCountedFromApprovalRecords() {
        Run run = new Run("r-1", "g", "greenfield", "req");
        run.approvals().add(new ApprovalRecord("release_readiness", "mukesh", "t",
                Decision.APPROVED, "ok", Map.of(), "r-1"));

        RunMetrics metrics = MetricsCollector.compute(run, 1, null);

        assertThat(metrics.humanGatesEncountered()).isEqualTo(1);
        assertThat(metrics.approvalsGranted()).isEqualTo(1);
        assertThat(metrics.approvalsRejected()).isEqualTo(0);
    }

    @Test
    void safeStopReasonIsCarriedThrough() {
        Run run = failureVariantRun();
        RunMetrics metrics = MetricsCollector.compute(run, 7, null);
        assertThat(metrics.safeStopReason()).contains("exhausted retry budget");
    }

    private static String addSeconds(String isoTimestamp, double seconds) {
        return java.time.Instant.parse(isoTimestamp).plusMillis((long) (seconds * 1000)).toString();
    }

    // ---- CumulativeMetrics: percentiles withheld below 10 runs ----------------------

    @Test
    void cumulativeMetricsWithholdsPercentilesBelowTenRuns() {
        CumulativeMetrics cumulative = new CumulativeMetrics();
        for (int i = 0; i < 6; i++) {
            cumulative.record(100.0 + i);
        }
        assertThat(cumulative.p50()).isNull();
        assertThat(cumulative.max()).isNull();
    }

    @Test
    void cumulativeMetricsReportsPercentilesAtTenRuns() {
        CumulativeMetrics cumulative = new CumulativeMetrics();
        for (int i = 1; i <= 10; i++) {
            cumulative.record(i * 10.0); // 10..100
        }
        assertThat(cumulative.p50()).isNotNull();
        assertThat(cumulative.max()).isEqualTo(100.0);
    }
}
