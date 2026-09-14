package com.sdlc.orchestrator.model;

import java.util.List;
import java.util.Map;

/**
 * Static definition of one graph node. Immutable for the run's duration.
 *
 * <p>A builder is used rather than a long canonical-constructor call because most of
 * these fields are optional and have graph-wide defaults (see
 * {@code docs/orchestration-graph.yaml}'s {@code defaults} block) — a positional
 * constructor with this many optional parameters is exactly the kind of thing that
 * silently swaps two arguments during a refactor.
 */
public record NodeSpec(
        String id,
        String name,
        String agentRole,
        List<String> dependsOn,

        /**
         * Declared expectation, CHECKED against the computed frontier — never used to
         * schedule (DEC-0011). Redundant configuration that drives behaviour is how a
         * graph edit silently introduces a race; redundant configuration that is
         * verified catches that edit instead.
         */
        List<String> canRunParallel,

        /** Artifact keys this node writes. Makes staleness computable. */
        List<String> produces,

        Gate entryGate,
        Gate exitGate,

        boolean requiresHumanApproval,
        String humanApprovalReason,

        /**
         * Artifact paths shown to the approver. A human asked to approve something
         * they cannot see is not providing oversight.
         */
        List<String> approvalPayload,

        /** Optional guard; if it evaluates false the node becomes SKIPPED. */
        Map<String, Object> condition,

        RetryPolicy retryPolicy,
        RollbackAction rollbackAction,
        List<RePlanTrigger> rePlanTriggers,

        Map<String, Object> joinPolicy,

        /**
         * Feedback edge (e.g. testing -> implementation). Bounded by
         * {@code maxFeedbackLoops} so build-test churn cannot run forever.
         */
        Map<String, Object> onPersistentFailure,

        double timeoutSeconds,
        List<String> metricsTags
) {

    public NodeSpec {
        dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
        canRunParallel = canRunParallel == null ? List.of() : List.copyOf(canRunParallel);
        produces = produces == null ? List.of() : List.copyOf(produces);
        approvalPayload = approvalPayload == null ? List.of() : List.copyOf(approvalPayload);
        condition = condition == null ? Map.of() : Map.copyOf(condition);
        retryPolicy = retryPolicy == null ? RetryPolicy.defaults() : retryPolicy;
        rePlanTriggers = rePlanTriggers == null ? List.of() : List.copyOf(rePlanTriggers);
        joinPolicy = joinPolicy == null ? Map.of() : Map.copyOf(joinPolicy);
        onPersistentFailure = onPersistentFailure == null ? Map.of() : Map.copyOf(onPersistentFailure);
        metricsTags = metricsTags == null ? List.of() : List.copyOf(metricsTags);
    }

    public boolean isHighImpact() {
        return requiresHumanApproval;
    }

    public static Builder builder(String id, String name, String agentRole) {
        return new Builder(id, name, agentRole);
    }

    /** Builder with graph-wide defaults pre-filled (mirrors
     *  {@code docs/orchestration-graph.yaml}'s {@code graph.defaults} block). */
    public static final class Builder {
        private final String id;
        private final String name;
        private final String agentRole;
        private List<String> dependsOn = List.of();
        private List<String> canRunParallel = List.of();
        private List<String> produces = List.of();
        private Gate entryGate;
        private Gate exitGate;
        private boolean requiresHumanApproval = false;
        private String humanApprovalReason;
        private List<String> approvalPayload = List.of();
        private Map<String, Object> condition = Map.of();
        private RetryPolicy retryPolicy = RetryPolicy.defaults();
        private RollbackAction rollbackAction;
        private List<RePlanTrigger> rePlanTriggers = List.of();
        private Map<String, Object> joinPolicy = Map.of();
        private Map<String, Object> onPersistentFailure = Map.of();
        private double timeoutSeconds = 600.0;
        private List<String> metricsTags = List.of();

        private Builder(String id, String name, String agentRole) {
            this.id = id;
            this.name = name;
            this.agentRole = agentRole;
        }

        public Builder dependsOn(List<String> v) { this.dependsOn = v; return this; }
        public Builder canRunParallel(List<String> v) { this.canRunParallel = v; return this; }
        public Builder produces(List<String> v) { this.produces = v; return this; }
        public Builder entryGate(Gate v) { this.entryGate = v; return this; }
        public Builder exitGate(Gate v) { this.exitGate = v; return this; }
        public Builder requiresHumanApproval(boolean v) { this.requiresHumanApproval = v; return this; }
        public Builder humanApprovalReason(String v) { this.humanApprovalReason = v; return this; }
        public Builder approvalPayload(List<String> v) { this.approvalPayload = v; return this; }
        public Builder condition(Map<String, Object> v) { this.condition = v; return this; }
        public Builder retryPolicy(RetryPolicy v) { this.retryPolicy = v; return this; }
        public Builder rollbackAction(RollbackAction v) { this.rollbackAction = v; return this; }
        public Builder rePlanTriggers(List<RePlanTrigger> v) { this.rePlanTriggers = v; return this; }
        public Builder joinPolicy(Map<String, Object> v) { this.joinPolicy = v; return this; }
        public Builder onPersistentFailure(Map<String, Object> v) { this.onPersistentFailure = v; return this; }
        public Builder timeoutSeconds(double v) { this.timeoutSeconds = v; return this; }
        public Builder metricsTags(List<String> v) { this.metricsTags = v; return this; }

        public NodeSpec build() {
            return new NodeSpec(id, name, agentRole, dependsOn, canRunParallel, produces,
                    entryGate, exitGate, requiresHumanApproval, humanApprovalReason, approvalPayload,
                    condition, retryPolicy, rollbackAction, rePlanTriggers, joinPolicy,
                    onPersistentFailure, timeoutSeconds, metricsTags);
        }
    }
}
