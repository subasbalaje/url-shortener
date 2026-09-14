package com.sdlc.orchestrator.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Vocabulary tests for the small enums — catches an accidental rename or drop,
 *  since other classes (Run, Gate, ApprovalRecord) depend on these names by string
 *  contract in persisted JSON. */
class ModelEnumsTest {

    @Test
    void runStateHasTheSixLifecycleValues() {
        assertThat(RunState.values()).containsExactlyInAnyOrder(
                RunState.INITIALISED, RunState.RUNNING, RunState.AWAITING_APPROVAL,
                RunState.COMPLETED, RunState.SAFE_STOPPED, RunState.FAILED);
    }

    @Test
    void gateTypeHasEntryExitAndHumanApproval() {
        assertThat(GateType.values()).containsExactlyInAnyOrder(
                GateType.ENTRY, GateType.EXIT, GateType.HUMAN_APPROVAL);
    }

    @Test
    void decisionHasApprovedRejectedAndApprovedWithConditions() {
        assertThat(Decision.values()).containsExactlyInAnyOrder(
                Decision.APPROVED, Decision.REJECTED, Decision.APPROVED_WITH_CONDITIONS);
    }
}
