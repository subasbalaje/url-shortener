package com.sdlc.orchestrator.model;

/** Lifecycle of an entire run. */
public enum RunState {
    INITIALISED,
    RUNNING,

    /** Persisted and exited at a gate; resumable via {@code GraphExecutor.resume()}. */
    AWAITING_APPROVAL,

    COMPLETED,

    /** Halted deliberately after unrecoverable failure, state fully persisted. */
    SAFE_STOPPED,

    FAILED
}
