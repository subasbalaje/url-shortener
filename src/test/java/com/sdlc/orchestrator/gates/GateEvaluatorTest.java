package com.sdlc.orchestrator.gates;

import com.sdlc.orchestrator.model.Artifact;
import com.sdlc.orchestrator.model.Gate;
import com.sdlc.orchestrator.model.GateCondition;
import com.sdlc.orchestrator.model.GateType;
import com.sdlc.orchestrator.model.NodeSpec;
import com.sdlc.orchestrator.model.Run;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GateEvaluatorTest {

    private final CheckerRegistry registry = GateCheckers.newFullyRegisteredRegistry();
    private final NodeSpec node = NodeSpec.builder("design", "Design", "design_agent").build();

    @Test
    void passesWhenAllConditionsPass() {
        Run run = new Run("r", "g", "greenfield", "req");
        run.artifacts().put("requirement_spec", new Artifact("requirement_spec", "requirements", Map.of("k", "v"), "", "", 1));

        Gate gate = new Gate(GateType.ENTRY, List.of(
                new GateCondition("artifact_present", "No requirement.", Map.of("artifact", "requirement_spec"))
        ), "");

        GateEvaluator.Result result = GateEvaluator.evaluate(run, node, gate, registry);
        assertThat(result.passed()).isTrue();
        assertThat(result.failures()).isEmpty();
    }

    @Test
    void aNullGateTriviallyPasses() {
        // Not every node declares an entry gate; absence is not failure.
        Run run = new Run("r", "g", "greenfield", "req");
        GateEvaluator.Result result = GateEvaluator.evaluate(run, node, null, registry);
        assertThat(result.passed()).isTrue();
    }

    @Test
    void usesTheDeclaredErrorMessageOnFailure() {
        Run run = new Run("r", "g", "greenfield", "req"); // no requirement_spec artifact
        Gate gate = new Gate(GateType.ENTRY, List.of(
                new GateCondition("artifact_present", "Cannot design without a normalised requirement.",
                        Map.of("artifact", "requirement_spec"))
        ), "");

        GateEvaluator.Result result = GateEvaluator.evaluate(run, node, gate, registry);
        assertThat(result.passed()).isFalse();
        assertThat(result.failures()).containsExactly("Cannot design without a normalised requirement.");
    }

    @Test
    void evaluatesEveryConditionRatherThanShortCircuiting() {
        // A retrying agent must get a complete list of what's wrong in one pass.
        Run run = new Run("r", "g", "greenfield", "req"); // neither artifact present
        Gate gate = new Gate(GateType.ENTRY, List.of(
                new GateCondition("artifact_present", "Missing design_doc.", Map.of("artifact", "design_doc")),
                new GateCondition("artifact_present", "Missing api_schema.", Map.of("artifact", "api_schema"))
        ), "");

        GateEvaluator.Result result = GateEvaluator.evaluate(run, node, gate, registry);
        assertThat(result.passed()).isFalse();
        assertThat(result.failures()).containsExactlyInAnyOrder("Missing design_doc.", "Missing api_schema.");
    }

    @Test
    void anUnregisteredConditionTypePropagatesRatherThanPassingVacuously() {
        Run run = new Run("r", "g", "greenfield", "req");
        Gate gate = new Gate(GateType.ENTRY, List.of(
                new GateCondition("no_such_checker_type", "err", Map.of())
        ), "");

        assertThatThrownBy(() -> GateEvaluator.evaluate(run, node, gate, registry))
                .isInstanceOf(UnknownCheckerException.class);
    }
}
