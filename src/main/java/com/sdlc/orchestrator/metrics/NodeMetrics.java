package com.sdlc.orchestrator.metrics;

/** Per-node slice of a run's metrics. */
public record NodeMetrics(
        String nodeId,
        String finalState,
        int attempts,
        int retries,
        double timeRunningSeconds,
        double timeBlockedSeconds,
        double timeRetryingSeconds,
        double totalSeconds,
        String firstFailedAt,
        String recoveredAt,
        Double recoverySeconds
) {}
