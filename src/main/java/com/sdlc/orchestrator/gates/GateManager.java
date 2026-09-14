package com.sdlc.orchestrator.gates;

import com.sdlc.orchestrator.model.ApprovalRecord;
import com.sdlc.orchestrator.model.Artifact;
import com.sdlc.orchestrator.model.Decision;
import com.sdlc.orchestrator.model.NodeSpec;
import com.sdlc.orchestrator.model.Run;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Surfaces pending approvals and records human decisions (DEC-0007).
 *
 * <p>The pause is a FILE ON DISK plus a process that has EXITED. That is what
 * makes it provable: a reviewer can inspect {@code pending_approval.json}, kill
 * the machine, and still resume. A gate that blocks in memory is
 * indistinguishable from {@code Thread.sleep()}.
 *
 * <p>Gates sit at NODE BOUNDARIES, never mid-node, so no partial execution is ever
 * suspended and node authors owe no idempotency guarantee.
 */
public final class GateManager {

    public record ValidityResult(boolean valid, Map<String, String> mismatches) {}

    /**
     * Writes the human-facing pending-approval file: what is being approved, why,
     * exactly what payload is at stake, and how to respond. {@code artifactRef}
     * binds the eventual decision to exact content hashes — approving "the design"
     * is meaningless unless it names which design.
     */
    public void writePending(Run run, NodeSpec node, Path pendingApprovalPath) throws IOException {
        Map<String, Object> payload = new LinkedHashMap<>();
        for (String fieldPath : node.approvalPayload()) {
            payload.put(fieldPath, RunFieldResolver.resolve(run, fieldPath));
        }

        Map<String, String> artifactRef = new LinkedHashMap<>();
        for (String fieldPath : node.approvalPayload()) {
            String artifactKey = fieldPath.split("\\.")[0];
            Artifact artifact = run.artifacts().get(artifactKey);
            if (artifact != null) {
                artifactRef.put(artifactKey, artifact.contentHash());
            }
        }

        Map<String, Object> pending = new LinkedHashMap<>();
        pending.put("run_id", run.runId());
        pending.put("node_id", node.id());
        pending.put("node_name", node.name());
        pending.put("reason", node.humanApprovalReason());
        pending.put("requested_at", Instant.now().toString());
        pending.put("payload", payload);
        pending.put("artifact_ref", artifactRef);
        pending.put("respond_by", "write approval.json in this directory");
        pending.put("response_schema", Map.of(
                "approver", "str", "decision", "approved | rejected | approved_with_conditions",
                "comment", "str (required — the rationale is the audit-relevant part)"));

        if (pendingApprovalPath.getParent() != null) {
            Files.createDirectories(pendingApprovalPath.getParent());
        }
        Files.writeString(pendingApprovalPath,
                new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(pending));
    }

    /**
     * Reads a human's {@code approval.json} response and binds it to the
     * {@code artifact_ref} recorded in the pending-approval file <em>at request
     * time</em> — never re-resolved fresh from the current run, since the whole
     * point is to detect drift between "what was approved" and "what exists now".
     */
    @SuppressWarnings("unchecked")
    public ApprovalRecord recordDecision(Path pendingApprovalPath, Path approvalResponsePath, String runId) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        Map<String, Object> pending = mapper.readValue(Files.readString(pendingApprovalPath), Map.class);
        Map<String, Object> response = mapper.readValue(Files.readString(approvalResponsePath), Map.class);

        String comment = (String) response.get("comment");
        if (comment == null || comment.isBlank()) {
            throw new IllegalArgumentException("An approval response requires a non-empty comment — "
                    + "the rationale is the audit-relevant part, not the decision alone.");
        }

        return new ApprovalRecord(
                (String) pending.get("node_id"),
                (String) response.get("approver"),
                Instant.now().toString(),
                Decision.valueOf(((String) response.get("decision")).toUpperCase()),
                comment,
                (Map<String, String>) pending.getOrDefault("artifact_ref", Map.of()),
                runId);
    }

    /**
     * Check the approved artifacts have not changed since the decision.
     *
     * <p>This is what makes approval binding meaningful. Without it, a re-planned
     * artifact would silently inherit approval granted for its predecessor.
     */
    public ValidityResult verifyApprovalStillValid(ApprovalRecord record, Map<String, Artifact> currentArtifacts) {
        Map<String, String> mismatches = new LinkedHashMap<>();
        for (var entry : record.artifactRef().entrySet()) {
            Artifact current = currentArtifacts.get(entry.getKey());
            String currentHash = current == null ? null : current.contentHash();
            if (!entry.getValue().equals(currentHash)) {
                mismatches.put(entry.getKey(), entry.getValue() + " -> " + currentHash);
            }
        }
        return new ValidityResult(mismatches.isEmpty(), mismatches);
    }
}
