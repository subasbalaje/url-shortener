package com.sdlc.orchestrator.lineage;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

import java.util.Map;

/**
 * One append-only audit-trail record. Every entry carries the {@code run_id} /
 * {@code node_id} / {@code attempt} correlation triple where applicable — the W3C
 * Trace Context <em>shape</em> (trace/span/attempt), not HTTP propagation, since the
 * orchestrator is single-process (docs/architecture.md §5.1).
 *
 * <p>Field names serialise to {@code snake_case} to match every worked example in
 * {@code docs/example-run.md} §5 exactly — this file is meant to be {@code cat}'able
 * evidence for a reviewer (DEC-0005), and matching the documented shape means a
 * reviewer comparing this repo's actual output against the spec sees the same
 * field names, not a re-styled equivalent.
 */
public record LineageEntry(
        EventType event,
        String runId,
        String timestamp,
        String nodeId,
        Integer attempt,
        String actor,
        String fromState,
        String toState,
        String decision,
        String rationale,
        Map<String, String> artifactRefs,
        Map<String, Object> details
) {
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    public LineageEntry {
        if (artifactRefs != null) {
            artifactRefs = Map.copyOf(artifactRefs);
        }
        if (details != null) {
            details = Map.copyOf(details);
        }
    }

    public String toJsonLine() {
        try {
            return MAPPER.writeValueAsString(this);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("LineageEntry is not JSON-serialisable", e);
        }
    }

    public static Builder builder(EventType event, String runId, String timestamp) {
        return new Builder(event, runId, timestamp);
    }

    public static final class Builder {
        private final EventType event;
        private final String runId;
        private final String timestamp;
        private String nodeId;
        private Integer attempt;
        private String actor;
        private String fromState;
        private String toState;
        private String decision;
        private String rationale;
        private Map<String, String> artifactRefs;
        private Map<String, Object> details;

        private Builder(EventType event, String runId, String timestamp) {
            this.event = event;
            this.runId = runId;
            this.timestamp = timestamp;
        }

        public Builder nodeId(String v) { this.nodeId = v; return this; }
        public Builder attempt(Integer v) { this.attempt = v; return this; }
        public Builder actor(String v) { this.actor = v; return this; }
        public Builder fromState(String v) { this.fromState = v; return this; }
        public Builder toState(String v) { this.toState = v; return this; }
        public Builder decision(String v) { this.decision = v; return this; }
        public Builder rationale(String v) { this.rationale = v; return this; }
        public Builder artifactRefs(Map<String, String> v) { this.artifactRefs = v; return this; }
        public Builder details(Map<String, Object> v) { this.details = v; return this; }

        public LineageEntry build() {
            return new LineageEntry(event, runId, timestamp, nodeId, attempt, actor,
                    fromState, toState, decision, rationale, artifactRefs, details);
        }
    }
}
