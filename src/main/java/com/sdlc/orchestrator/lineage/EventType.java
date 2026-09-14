package com.sdlc.orchestrator.lineage;

/**
 * The closed lineage event vocabulary.
 *
 * <p>Deliberately finite: a fixed vocabulary makes the trail queryable ("show
 * every rollback across all runs") in a way free-text logging never is.
 *
 * <p><b>Provenance note</b> (see decision log DEC-0015): this repository has no
 * git history, so the original {@code orchestrator/decision_log.py}'s exact
 * {@code EventType} member list is not recoverable. Every value here is either
 * directly evidenced by an {@code "event":"..."} literal in
 * {@code docs/example-run.md} §5, or (only {@link #APPROVAL_REJECTED}) a
 * necessary consequence of DEC-0007's documented rejection path that the worked
 * trace — being a single successful run — never had occasion to show.
 */
public enum EventType {
    RUN_STARTED,
    RUN_COMPLETED,
    RUN_SAFE_STOPPED,

    NODE_STARTED,
    NODE_COMPLETED,
    NODE_FAILED,
    NODE_SKIPPED,

    RETRY_SCHEDULED,
    ROLLBACK_COMPLETED,

    GATE_EXIT_PASSED,
    GATE_EXIT_FAILED,

    APPROVAL_REQUESTED,
    APPROVAL_GRANTED,
    APPROVAL_REJECTED,

    /** The visible proof that approval binds to content, not to a node name: a
     *  changed artifact voids the approval that covered its earlier version. */
    APPROVAL_INVALIDATED,

    DECISION_RECORDED,
    REPLAN_TRIGGERED
}
