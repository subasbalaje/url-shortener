package com.sdlc.orchestrator.metrics;

import com.sdlc.orchestrator.model.Decision;
import com.sdlc.orchestrator.model.NodeRuntime;
import com.sdlc.orchestrator.model.NodeState;
import com.sdlc.orchestrator.model.Run;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Computes {@link RunMetrics} from a terminal (or safe-stopped) {@link Run}.
 * Called on both the success and failure paths (Argo's exit-handler pattern) — a
 * run that fails must still produce metrics, since observability that only works
 * when things go well is absent exactly when it is needed.
 *
 * <h2>{@code nodesAttempted} — the one worth reading carefully</h2>
 * {@code nodesAttempted = nodesTotal - nodesUpstreamFailed}. This deliberately
 * counts {@code SKIPPED} nodes as "attempted": {@code docs/orchestration-graph.yaml}'s
 * own {@code policy.metrics.success_rate.note} says only
 * <i>"upstream_failed is counted separately, never as a node's own failure"</i> — it
 * does not say SKIPPED is excluded too. The worked failure-variant fixture in
 * {@code docs/example-run.md} §7 (2 completed, 1 failed, 3 upstream-failed, 1
 * skipped) only reproduces its own stated result of {@code success_rate = 0.5} under
 * this reading (denominator 4 = 2+1+1); excluding SKIPPED as well would give 2/3 ≈
 * 0.667, which is not what that section documents. See decision log DEC-0016 for
 * the full reconciliation — {@code docs/example-run.md} §4's greenfield illustrative
 * number (6, not 7) turns out to be the inconsistent one once you hold both
 * examples to one formula, and is corrected against a real run in step 13.
 */
public final class MetricsCollector {

    private MetricsCollector() {}

    public static RunMetrics compute(Run run, int nodesTotal, Double criticalPathSeconds) {
        int completed = 0, skipped = 0, failed = 0, upstreamFailed = 0;
        int totalRetryAttempts = 0, rollbacksExecuted = 0, staleReplanned = 0;
        int failureEpisodes = 0;
        double sumRecoverySeconds = 0;
        double timeRunning = 0, timeBlocked = 0, timeRetrying = 0;
        Map<String, NodeMetrics> nodeMetrics = new LinkedHashMap<>();

        for (NodeRuntime node : run.nodes().values()) {
            switch (node.state()) {
                case COMPLETED -> completed++;
                case SKIPPED -> skipped++;
                case FAILED, ROLLED_BACK -> failed++;
                case UPSTREAM_FAILED -> upstreamFailed++;
                default -> { /* still active/pending: not counted in any terminal bucket */ }
            }
            totalRetryAttempts += node.retryCount();
            if (node.rolledBack()) {
                rollbacksExecuted++;
            }
            staleReplanned += node.replanCount();
            timeRunning += node.timeRunningSeconds();
            timeBlocked += node.timeBlockedSeconds();
            timeRetrying += node.timeRetryingSeconds();

            Double recoverySeconds = null;
            if (node.firstFailedAt() != null) {
                failureEpisodes++;
                if (node.recoveredAt() != null) {
                    recoverySeconds = secondsBetween(node.firstFailedAt(), node.recoveredAt());
                    sumRecoverySeconds += recoverySeconds;
                }
            }

            nodeMetrics.put(node.nodeId(), new NodeMetrics(
                    node.nodeId(), node.state().name(), node.attempts().size(), node.retryCount(),
                    node.timeRunningSeconds(), node.timeBlockedSeconds(), node.timeRetryingSeconds(),
                    node.timeRunningSeconds() + node.timeBlockedSeconds() + node.timeRetryingSeconds(),
                    node.firstFailedAt(), node.recoveredAt(), recoverySeconds));
        }

        int nodesAttempted = nodesTotal - upstreamFailed;
        double successRate = nodesAttempted == 0 ? 0.0 : (double) completed / nodesAttempted;
        double retryFrequency = nodesAttempted == 0 ? 0.0 : (double) totalRetryAttempts / nodesAttempted;
        double rollbackFrequency = nodesAttempted == 0 ? 0.0 : (double) rollbacksExecuted / nodesAttempted;
        Double mttrSeconds = failureEpisodes == 0 ? null : sumRecoverySeconds / failureEpisodes;

        Double endToEndSeconds = run.endedAt() == null ? null : secondsBetween(run.startedAt(), run.endedAt());
        Double parallelEfficiency = (criticalPathSeconds != null && endToEndSeconds != null && endToEndSeconds > 0)
                ? criticalPathSeconds / endToEndSeconds
                : null;

        int approvalsGranted = 0, approvalsRejected = 0;
        for (var approval : run.approvals()) {
            if (approval.decision() == Decision.REJECTED) {
                approvalsRejected++;
            } else {
                approvalsGranted++;
            }
        }

        return new RunMetrics(
                run.runId(), run.graphId(), run.mode(), run.state().name(), run.startedAt(), run.endedAt(),
                nodesTotal, nodesAttempted, completed, skipped, failed, upstreamFailed, staleReplanned,
                successRate, retryFrequency, rollbackFrequency, totalRetryAttempts, rollbacksExecuted,
                failureEpisodes, mttrSeconds,
                endToEndSeconds, timeRunning, timeBlocked, timeRetrying,
                List.of(), criticalPathSeconds, parallelEfficiency,
                run.approvals().size(), approvalsGranted, approvalsRejected, run.approvalsInvalidatedByReplan(),
                run.replanCycles(),
                Map.copyOf(nodeMetrics), run.safeStopReason());
    }

    private static double secondsBetween(String startIso, String endIso) {
        return Duration.between(Instant.parse(startIso), Instant.parse(endIso)).toMillis() / 1000.0;
    }
}
