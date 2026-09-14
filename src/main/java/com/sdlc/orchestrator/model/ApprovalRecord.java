package com.sdlc.orchestrator.model;

import java.util.Map;

/**
 * A human decision. All five core fields are load-bearing.
 *
 * <p>{@code artifactRef} is the one most often omitted and the most important:
 * approving "the design" means nothing unless it names <em>which</em> design.
 * Binding to a content hash means a later edit invalidates the approval rather than
 * inheriting it.
 */
public record ApprovalRecord(
        String nodeId,
        String approver,
        String decidedAt,
        Decision decision,
        String comment,

        /** Mapping of artifact key -> content hash that was approved. */
        Map<String, String> artifactRef,

        String runId
) {

    public ApprovalRecord {
        artifactRef = artifactRef == null ? Map.of() : Map.copyOf(artifactRef);
        runId = runId == null ? "" : runId;
    }

    public boolean permitsContinuation() {
        return decision == Decision.APPROVED || decision == Decision.APPROVED_WITH_CONDITIONS;
    }
}
