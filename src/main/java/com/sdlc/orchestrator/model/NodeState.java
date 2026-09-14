package com.sdlc.orchestrator.model;

/**
 * Lifecycle states of a single node.
 *
 * <p>{@link #BLOCKED_ON_GATE} is a first-class state, not a boolean flag — mirroring
 * Airflow's {@code awaiting_input}. {@link #UPSTREAM_FAILED} is tracked separately from
 * {@link #FAILED} so one root cause is counted once in the success-rate metric.
 * {@link #COMPLETED} means the agent succeeded <em>and</em> the exit gate passed.
 *
 * <p>{@link #STALE} is the only backwards transition in the machine: a node may move
 * {@code COMPLETED -> STALE} when re-planning invalidates its artifact.
 */
public enum NodeState {
    /** Dependencies not yet satisfied. The initial state of every node. */
    PENDING,

    /** All dependencies COMPLETED (or SKIPPED); eligible for dispatch. */
    READY,

    /** The stage agent is executing. */
    RUNNING,

    /**
     * Awaiting a human decision. Run state is persisted and the process may exit
     * entirely (DEC-0007).
     */
    BLOCKED_ON_GATE,

    /**
     * An attempt failed. Retry eligibility has not yet been evaluated; the executor
     * decides next whether this becomes RETRYING or ROLLED_BACK.
     */
    FAILED,

    /**
     * Failed with attempts remaining; backoff in progress. This is the self-loop in
     * the node state machine — NOT an edge in the DAG.
     */
    RETRYING,

    /** Retries exhausted and the compensating action has been executed. */
    ROLLED_BACK,

    /** Agent succeeded <b>and</b> the exit gate passed. */
    COMPLETED,

    /**
     * A conditional node whose {@code condition} evaluated false. Treated as
     * <em>resolved</em> for join purposes — a skipped node does not block its
     * dependents.
     */
    SKIPPED,

    /**
     * A dependency failed terminally, so this node can never become ready. Tracked
     * separately from FAILED so one root cause is not counted as several node
     * failures in the success-rate metric.
     */
    UPSTREAM_FAILED,

    /**
     * Was COMPLETED, but an upstream artifact it depends on has changed. Must
     * re-run. Its prior lineage is preserved.
     */
    STALE;

    /** States satisfying a dependent's join condition. SKIPPED counts: a conditional
     *  node that legitimately did not apply must not block its dependents. */
    public boolean isTerminalSuccess() {
        return this == COMPLETED || this == SKIPPED;
    }

    /** States from which this node will not proceed without intervention. */
    public boolean isTerminalFailure() {
        return this == ROLLED_BACK || this == UPSTREAM_FAILED;
    }

    /** States where the run is still doing or awaiting work on this node. */
    public boolean isActive() {
        return this == RUNNING || this == RETRYING || this == BLOCKED_ON_GATE;
    }
}
