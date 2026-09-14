package com.sdlc.orchestrator.gates;

import com.sdlc.orchestrator.model.Artifact;
import com.sdlc.orchestrator.model.NodeRuntime;
import com.sdlc.orchestrator.model.NodeSpec;
import com.sdlc.orchestrator.model.NodeState;
import com.sdlc.orchestrator.model.Run;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GenericCheckersTest {

    private CheckerRegistry registry;
    private NodeSpec node;

    @BeforeEach
    void setUp() {
        registry = new CheckerRegistry();
        GenericCheckers.registerDefaults(registry);
        node = NodeSpec.builder("design", "Design", "design_agent").build();
    }

    // ---- input_present ------------------------------------------------------

    @Test
    void inputPresentPassesWhenRawRequestIsSet() {
        Run run = new Run("r-1", "g", "greenfield", "Build a shortener");
        CheckResult result = registry.get("input_present").check(run, node, Map.of("field", "raw_request"));
        assertThat(result.passed()).isTrue();
    }

    @Test
    void inputPresentFailsWhenRawRequestIsBlank() {
        Run run = new Run("r-1", "g", "greenfield", "   ");
        CheckResult result = registry.get("input_present").check(run, node, Map.of("field", "raw_request"));
        assertThat(result.passed()).isFalse();
        assertThat(result.message()).isNotBlank();
    }

    // ---- artifact_present -----------------------------------------------------

    @Test
    void artifactPresentPassesWhenArtifactExists() {
        Run run = new Run("r-1", "g", "greenfield", "req");
        run.artifacts().put("requirement_spec", new Artifact("requirement_spec", "requirements", Map.of("k", "v"), "", "", 1));

        CheckResult result = registry.get("artifact_present").check(run, node, Map.of("artifact", "requirement_spec"));
        assertThat(result.passed()).isTrue();
    }

    @Test
    void artifactPresentFailsWhenArtifactMissing() {
        Run run = new Run("r-1", "g", "greenfield", "req");
        CheckResult result = registry.get("artifact_present").check(run, node, Map.of("artifact", "requirement_spec"));
        assertThat(result.passed()).isFalse();
    }

    // ---- field_non_empty ------------------------------------------------------

    @Test
    void fieldNonEmptyPassesForANonEmptyList() {
        Run run = new Run("r-1", "g", "greenfield", "req");
        run.artifacts().put("requirement_spec", new Artifact("requirement_spec", "requirements",
                Map.of("acceptance_criteria", List.of("AC1")), "", "", 1));

        CheckResult result = registry.get("field_non_empty")
                .check(run, node, Map.of("field", "requirement_spec.acceptance_criteria"));
        assertThat(result.passed()).isTrue();
    }

    @Test
    void fieldNonEmptyFailsForAnEmptyList() {
        Run run = new Run("r-1", "g", "greenfield", "req");
        run.artifacts().put("requirement_spec", new Artifact("requirement_spec", "requirements",
                Map.of("acceptance_criteria", List.of()), "", "", 1));

        CheckResult result = registry.get("field_non_empty")
                .check(run, node, Map.of("field", "requirement_spec.acceptance_criteria"));
        assertThat(result.passed()).isFalse();
    }

    @Test
    void fieldNonEmptyFailsForAMissingField() {
        Run run = new Run("r-1", "g", "greenfield", "req");
        CheckResult result = registry.get("field_non_empty")
                .check(run, node, Map.of("field", "requirement_spec.acceptance_criteria"));
        assertThat(result.passed()).isFalse();
    }

    @Test
    void fieldNonEmptyPassesWhenEmptyButAllowedByCondition() {
        // requirement_spec.assumptions may be empty IF ambiguity_report.ambiguities
        // is also empty (docs/orchestration-graph.yaml, requirements exit gate).
        Run run = new Run("r-1", "g", "greenfield", "req");
        run.artifacts().put("requirement_spec", new Artifact("requirement_spec", "requirements",
                Map.of("assumptions", List.of()), "", "", 1));
        run.artifacts().put("ambiguity_report", new Artifact("ambiguity_report", "requirements",
                Map.of("ambiguities", List.of()), "", "", 1));

        CheckResult result = registry.get("field_non_empty").check(run, node, Map.of(
                "field", "requirement_spec.assumptions",
                "allow_empty_if", "ambiguity_report.ambiguities == []"));
        assertThat(result.passed()).isTrue();
    }

    @Test
    void fieldNonEmptyFailsWhenEmptyAndConditionDoesNotHold() {
        Run run = new Run("r-1", "g", "greenfield", "req");
        run.artifacts().put("requirement_spec", new Artifact("requirement_spec", "requirements",
                Map.of("assumptions", List.of()), "", "", 1));
        run.artifacts().put("ambiguity_report", new Artifact("ambiguity_report", "requirements",
                Map.of("ambiguities", List.of(Map.of("id", "AMB1"))), "", "", 1));

        CheckResult result = registry.get("field_non_empty").check(run, node, Map.of(
                "field", "requirement_spec.assumptions",
                "allow_empty_if", "ambiguity_report.ambiguities == []"));
        assertThat(result.passed()).isFalse();
    }

    // ---- upstream_state ---------------------------------------------------------

    @Test
    void upstreamStatePassesWhenTheNodeIsInTheExpectedState() {
        Run run = new Run("r-1", "g", "greenfield", "req");
        NodeRuntime requirements = new NodeRuntime("requirements");
        requirements.recordTransition(NodeState.COMPLETED, "t1");
        run.nodes().put("requirements", requirements);

        CheckResult result = registry.get("upstream_state")
                .check(run, node, Map.of("node", "requirements", "equals", "COMPLETED"));
        assertThat(result.passed()).isTrue();
    }

    @Test
    void upstreamStateFailsWhenTheNodeIsInADifferentState() {
        Run run = new Run("r-1", "g", "greenfield", "req");
        run.nodes().put("requirements", new NodeRuntime("requirements")); // still PENDING

        CheckResult result = registry.get("upstream_state")
                .check(run, node, Map.of("node", "requirements", "equals", "COMPLETED"));
        assertThat(result.passed()).isFalse();
    }

    @Test
    void upstreamStateFailsWhenTheNodeHasNotRunAtAll() {
        Run run = new Run("r-1", "g", "greenfield", "req");
        CheckResult result = registry.get("upstream_state")
                .check(run, node, Map.of("node", "requirements", "equals", "COMPLETED"));
        assertThat(result.passed()).isFalse();
    }

    // ---- upstream_state_in --------------------------------------------------------

    @Test
    void upstreamStateInPassesForAnyListedState() {
        Run run = new Run("r-1", "g", "greenfield", "req");
        NodeRuntime applyMigration = new NodeRuntime("apply_migration");
        applyMigration.recordTransition(NodeState.SKIPPED, "t1");
        run.nodes().put("apply_migration", applyMigration);

        CheckResult result = registry.get("upstream_state_in")
                .check(run, node, Map.of("node", "apply_migration", "any_of", List.of("COMPLETED", "SKIPPED")));
        assertThat(result.passed()).isTrue();
    }

    @Test
    void upstreamStateInFailsWhenStateIsNotListed() {
        Run run = new Run("r-1", "g", "greenfield", "req");
        NodeRuntime applyMigration = new NodeRuntime("apply_migration");
        applyMigration.recordTransition(NodeState.FAILED, "t1");
        run.nodes().put("apply_migration", applyMigration);

        CheckResult result = registry.get("upstream_state_in")
                .check(run, node, Map.of("node", "apply_migration", "any_of", List.of("COMPLETED", "SKIPPED")));
        assertThat(result.passed()).isFalse();
    }

    // ---- conditional_required -------------------------------------------------------

    @Test
    void conditionalRequiredPassesWhenGuardIsFalse() {
        // Greenfield: the brownfield-only impact_analysis requirement does not apply.
        Run run = new Run("r-1", "g", "greenfield", "req");
        CheckResult result = registry.get("conditional_required").check(run, node, Map.of(
                "when", "run_context.mode == 'brownfield'",
                "field", "impact_analysis.impacted_modules"));
        assertThat(result.passed()).isTrue();
    }

    @Test
    void conditionalRequiredFailsWhenGuardIsTrueAndFieldIsMissing() {
        Run run = new Run("r-1", "g", "brownfield", "req");
        CheckResult result = registry.get("conditional_required").check(run, node, Map.of(
                "when", "run_context.mode == 'brownfield'",
                "field", "impact_analysis.impacted_modules"));
        assertThat(result.passed()).isFalse();
    }

    @Test
    void conditionalRequiredPassesWhenGuardIsTrueAndFieldIsPresent() {
        Run run = new Run("r-1", "g", "brownfield", "req");
        run.artifacts().put("impact_analysis", new Artifact("impact_analysis", "design",
                Map.of("impacted_modules", List.of("src/models.py")), "", "", 1));

        CheckResult result = registry.get("conditional_required").check(run, node, Map.of(
                "when", "run_context.mode == 'brownfield'",
                "field", "impact_analysis.impacted_modules"));
        assertThat(result.passed()).isTrue();
    }

    // ---- fail-closed on the registry itself ------------------------------------------

    @Test
    void anUnregisteredTypeStillThrowsThroughTheDefaultsRegistry() {
        assertThatThrownBy(() -> registry.get("something_made_up"))
                .isInstanceOf(UnknownCheckerException.class);
    }
}
