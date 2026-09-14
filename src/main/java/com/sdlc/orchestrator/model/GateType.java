package com.sdlc.orchestrator.model;

public enum GateType {
    /** Precondition on <em>starting</em> a node: are my inputs present and valid? */
    ENTRY,

    /**
     * Validation predicate on a node's <em>output</em>: does it meet the bar? This is
     * the critic layer expressed as data.
     */
    EXIT,

    /**
     * Policy control requiring a recorded human decision. Orthogonal to entry/exit,
     * which are correctness controls.
     */
    HUMAN_APPROVAL
}
