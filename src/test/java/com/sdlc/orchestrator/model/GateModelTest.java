package com.sdlc.orchestrator.model;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Construction/defaults tests for the small gate-related data records. These carry
 *  no logic of their own, but a field being silently dropped or renamed would break
 *  every caller (GraphLoader, GateEvaluator) without a compiler error if the record
 *  weren't pinned down here. */
class GateModelTest {

    @Test
    void gateConditionCarriesTypeErrorAndParams() {
        GateCondition condition = new GateCondition(
                "field_non_empty", "No acceptance criteria.", Map.of("field", "requirement_spec.acceptance_criteria"));

        assertThat(condition.type()).isEqualTo("field_non_empty");
        assertThat(condition.error()).isEqualTo("No acceptance criteria.");
        assertThat(condition.params()).containsEntry("field", "requirement_spec.acceptance_criteria");
    }

    @Test
    void gateConditionParamsDefaultToEmptyMap() {
        GateCondition condition = new GateCondition("input_present", "Missing input.");
        assertThat(condition.params()).isEmpty();
    }

    @Test
    void gateGroupsConditionsUnderAGateType() {
        GateCondition c1 = new GateCondition("artifact_present", "err1");
        GateCondition c2 = new GateCondition("field_non_empty", "err2");
        Gate gate = new Gate(GateType.EXIT, List.of(c1, c2), "Design must be actionable.");

        assertThat(gate.gateType()).isEqualTo(GateType.EXIT);
        assertThat(gate.conditions()).containsExactly(c1, c2);
        assertThat(gate.description()).isEqualTo("Design must be actionable.");
    }

    @Test
    void gateConditionsDefaultToEmptyList() {
        Gate gate = new Gate(GateType.ENTRY, List.of(), "");
        assertThat(gate.conditions()).isEmpty();
    }

    @Test
    void rollbackActionDefaultsOnRollbackFailureToSafeStopAndPage() {
        RollbackAction action = new RollbackAction("discard_artifacts", "Discard the report.", Map.of(), "safe_stop_and_page");
        assertThat(action.type()).isEqualTo("discard_artifacts");
        assertThat(action.onRollbackFailure()).isEqualTo("safe_stop_and_page");
    }

    @Test
    void rePlanTriggerCarriesArtifactAndReason() {
        RePlanTrigger trigger = new RePlanTrigger("requirement_spec", "mark_stale",
                "Design is derived from the requirement; a changed requirement invalidates it.");

        assertThat(trigger.artifact()).isEqualTo("requirement_spec");
        assertThat(trigger.onChange()).isEqualTo("mark_stale");
        assertThat(trigger.reason()).contains("invalidates it");
    }
}
