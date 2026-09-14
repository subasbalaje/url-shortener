package com.sdlc.orchestrator.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Mutable per-node state.
 *
 * <p>{@code attempt} living here — rather than as a graph edge — is the concrete
 * expression of "bounded retry is a self-loop in the state machine, not a cycle in
 * the DAG" (DEC-0011-adjacent reasoning in {@code docs/architecture.md} §3.5). The
 * DAG never learns that retries exist.
 */
public final class NodeRuntime {

    private final String nodeId;
    private NodeState state = NodeState.PENDING;
    private int attempt = 0;
    private final List<AttemptRecord> attempts = new ArrayList<>();

    /** MTTR clock start. Set on the FIRST entry to FAILED and deliberately not reset
     *  by subsequent attempts — time-to-recovery measures the whole failure episode,
     *  not the last try. */
    private String firstFailedAt;

    /** MTTR clock stop: COMPLETED via retry, or rollback completed. Set only once,
     *  on the first COMPLETED that follows a recorded failure. */
    private String recoveredAt;

    private int retryCount = 0;
    private boolean rolledBack = false;
    private final List<String> gateFailures = new ArrayList<>();
    private int replanCount = 0;
    private int feedbackLoops = 0;

    private double timeRunningSeconds = 0.0;
    private double timeBlockedSeconds = 0.0;
    private double timeRetryingSeconds = 0.0;

    public NodeRuntime(String nodeId) {
        this.nodeId = nodeId;
    }

    /**
     * Apply a state transition and maintain the MTTR clocks.
     *
     * <p>TODO(impl): validate the transition against a legal-transition table and
     * throw on an illegal move. An executor bug that silently corrupts state is far
     * worse than one that crashes, because the audit trail would record the
     * corrupted result as fact.
     */
    public void recordTransition(NodeState newState, String at) {
        if (newState == NodeState.RETRYING) {
            retryCount++;
        }
        if (newState == NodeState.FAILED && firstFailedAt == null) {
            firstFailedAt = at;
        }
        // MTTR clock stop: COMPLETED via retry, OR rollback completing (docs/
        // example-run.md §7 recovers this way — no retry succeeded, compensation
        // did). Both are "the incident is over", just via different paths.
        if ((newState == NodeState.COMPLETED || newState == NodeState.ROLLED_BACK)
                && firstFailedAt != null && recoveredAt == null) {
            recoveredAt = at;
        }
        this.state = newState;
    }

    public String nodeId() { return nodeId; }
    public NodeState state() { return state; }
    public int attempt() { return attempt; }
    public void incrementAttempt() { attempt++; }
    public List<AttemptRecord> attempts() { return attempts; }
    public String firstFailedAt() { return firstFailedAt; }
    public String recoveredAt() { return recoveredAt; }
    public int retryCount() { return retryCount; }
    public boolean rolledBack() { return rolledBack; }
    public void markRolledBack() { this.rolledBack = true; }
    public List<String> gateFailures() { return gateFailures; }
    public int replanCount() { return replanCount; }
    public void incrementReplanCount() { replanCount++; }
    public int feedbackLoops() { return feedbackLoops; }
    public void incrementFeedbackLoops() { feedbackLoops++; }
    public double timeRunningSeconds() { return timeRunningSeconds; }
    public void addTimeRunningSeconds(double s) { timeRunningSeconds += s; }
    public double timeBlockedSeconds() { return timeBlockedSeconds; }
    public void addTimeBlockedSeconds(double s) { timeBlockedSeconds += s; }
    public double timeRetryingSeconds() { return timeRetryingSeconds; }
    public void addTimeRetryingSeconds(double s) { timeRetryingSeconds += s; }

    /** Directly set the current state, bypassing {@link #recordTransition}'s MTTR
     *  side effects. For restoring persisted state ({@link #fromMap}) only — normal
     *  execution must go through {@code recordTransition} so the clocks stay correct. */
    public void restoreState(NodeState state) { this.state = state; }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("node_id", nodeId);
        map.put("state", state.name());
        map.put("attempt", attempt);
        map.put("attempts", attempts.stream().map(AttemptRecord::toMap).toList());
        map.put("first_failed_at", firstFailedAt);
        map.put("recovered_at", recoveredAt);
        map.put("retry_count", retryCount);
        map.put("rolled_back", rolledBack);
        map.put("gate_failures", gateFailures);
        map.put("replan_count", replanCount);
        map.put("feedback_loops", feedbackLoops);
        map.put("time_running_seconds", timeRunningSeconds);
        map.put("time_blocked_seconds", timeBlockedSeconds);
        map.put("time_retrying_seconds", timeRetryingSeconds);
        return map;
    }

    @SuppressWarnings("unchecked")
    public static NodeRuntime fromMap(Map<String, Object> map) {
        NodeRuntime runtime = new NodeRuntime((String) map.get("node_id"));
        runtime.state = NodeState.valueOf((String) map.get("state"));
        runtime.attempt = ((Number) map.getOrDefault("attempt", 0)).intValue();
        for (Object rawAttempt : (List<Object>) map.getOrDefault("attempts", List.of())) {
            runtime.attempts.add(AttemptRecord.fromMap((Map<String, Object>) rawAttempt));
        }
        runtime.firstFailedAt = (String) map.get("first_failed_at");
        runtime.recoveredAt = (String) map.get("recovered_at");
        runtime.retryCount = ((Number) map.getOrDefault("retry_count", 0)).intValue();
        runtime.rolledBack = Boolean.TRUE.equals(map.get("rolled_back"));
        for (Object f : (List<Object>) map.getOrDefault("gate_failures", List.of())) {
            runtime.gateFailures.add(String.valueOf(f));
        }
        runtime.replanCount = ((Number) map.getOrDefault("replan_count", 0)).intValue();
        runtime.feedbackLoops = ((Number) map.getOrDefault("feedback_loops", 0)).intValue();
        runtime.timeRunningSeconds = ((Number) map.getOrDefault("time_running_seconds", 0.0)).doubleValue();
        runtime.timeBlockedSeconds = ((Number) map.getOrDefault("time_blocked_seconds", 0.0)).doubleValue();
        runtime.timeRetryingSeconds = ((Number) map.getOrDefault("time_retrying_seconds", 0.0)).doubleValue();
        return runtime;
    }
}
