package com.sdlc.orchestrator.model;

/**
 * Declares that a change to {@code artifact} invalidates this node.
 *
 * <p>Not every downstream node cares about every upstream change, so staleness is the
 * <em>intersection</em> of the transitive descendant set with these declared triggers
 * — rather than blindly invalidating everything downstream.
 */
public record RePlanTrigger(String artifact, String onChange, String reason) {

    public RePlanTrigger {
        onChange = onChange == null ? "mark_stale" : onChange;
        reason = reason == null ? "" : reason;
    }
}
