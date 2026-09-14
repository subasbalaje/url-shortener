package com.sdlc.orchestrator.gates;

import com.sdlc.orchestrator.graph.GraphLoader;
import com.sdlc.orchestrator.model.Artifact;
import com.sdlc.orchestrator.model.GraphSpec;
import com.sdlc.orchestrator.model.NodeRuntime;
import com.sdlc.orchestrator.model.NodeState;
import com.sdlc.orchestrator.model.Run;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Evaluates real gates from docs/orchestration-graph.yaml end to end: load the
 *  graph, build a Run in the shape the design node's entry gate expects, and
 *  confirm GateEvaluator both blocks the under-specified case and passes the
 *  satisfied one. */
class RealGraphGateEvaluationTest {

    private final CheckerRegistry registry = GateCheckers.newFullyRegisteredRegistry();

    @Test
    void designEntryGateFailsWithNoRequirementSpecAndNoUpstreamCompletion() throws IOException {
        GraphSpec graph = GraphLoader.load(Path.of("docs/orchestration-graph.yaml"));
        Run run = new Run("r", graph.id(), "greenfield", "Build a shortener");

        GateEvaluator.Result result = GateEvaluator.evaluate(run, graph.node("design"), graph.node("design").entryGate(), registry);

        assertThat(result.passed()).isFalse();
        assertThat(result.failures()).hasSize(2); // both entry conditions fail independently
    }

    @Test
    void designEntryGatePassesOnceRequirementsHaveCompleted() throws IOException {
        GraphSpec graph = GraphLoader.load(Path.of("docs/orchestration-graph.yaml"));
        Run run = new Run("r", graph.id(), "greenfield", "Build a shortener");
        run.artifacts().put("requirement_spec", new Artifact("requirement_spec", "requirements",
                Map.of("acceptance_criteria", List.of(Map.of("id", "AC1"))), "", "", 1));
        NodeRuntime requirements = new NodeRuntime("requirements");
        requirements.recordTransition(NodeState.COMPLETED, "t1");
        run.nodes().put("requirements", requirements);

        GateEvaluator.Result result = GateEvaluator.evaluate(run, graph.node("design"), graph.node("design").entryGate(), registry);

        assertThat(result.passed()).isTrue();
    }

    @Test
    void requirementsEntryGateFailsOnBlankRawRequest() throws IOException {
        GraphSpec graph = GraphLoader.load(Path.of("docs/orchestration-graph.yaml"));
        Run run = new Run("r", graph.id(), "greenfield", "   ");

        GateEvaluator.Result result = GateEvaluator.evaluate(
                run, graph.node("requirements"), graph.node("requirements").entryGate(), registry);

        assertThat(result.passed()).isFalse();
        assertThat(result.failures()).containsExactly("No requirement supplied; nothing to interpret.");
    }
}
