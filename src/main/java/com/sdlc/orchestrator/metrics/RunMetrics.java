package com.sdlc.orchestrator.metrics;

import java.util.List;
import java.util.Map;

/**
 * The four required metrics (docs/architecture.md §5.3), computed once per
 * terminal run. See {@link MetricsCollector} for the definitions and the
 * reasoning behind each field's nullability.
 */
public record RunMetrics(
        String runId,
        String graphId,
        String mode,
        String finalState,
        String startedAt,
        String endedAt,

        int nodesTotal,
        int nodesAttempted,
        int nodesCompleted,
        int nodesSkipped,
        int nodesFailed,
        int nodesUpstreamFailed,
        int nodesStaleReplanned,

        double successRate,
        double retryFrequency,
        double rollbackFrequency,
        int totalRetryAttempts,
        int rollbacksExecuted,

        int failureEpisodes,
        /** null (not 0.0) when there were no failures -- zero would falsely imply
         *  instant recovery from failures that never happened. */
        Double mttrSeconds,

        Double endToEndSeconds,
        double timeRunningSeconds,
        double timeBlockedOnGateSeconds,
        double timeRetryingSeconds,

        List<String> criticalPath,
        Double criticalPathSeconds,
        Double parallelEfficiency,

        int humanGatesEncountered,
        int approvalsGranted,
        int approvalsRejected,
        int approvalsInvalidatedByReplan,
        int replanCycles,

        Map<String, NodeMetrics> nodes,
        String safeStopReason
) {}
