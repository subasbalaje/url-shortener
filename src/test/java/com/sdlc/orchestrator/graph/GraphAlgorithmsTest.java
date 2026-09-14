package com.sdlc.orchestrator.graph;

import com.sdlc.orchestrator.model.GraphSpec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GraphAlgorithmsTest {

    // ---- topologicalOrder ------------------------------------------------

    @Test
    void topologicalOrderMatchesTheDocumentedOrderForTheRealGraph() {
        // docs/example-run.md §1 and orchestrator/README.md "Verified about the
        // graph": requirements -> design -> apply_migration -> implementation ->
        // documentation -> testing -> release_readiness.
        List<String> order = GraphAlgorithms.topologicalOrder(GraphFixtures.sdlcCore());

        assertThat(order).containsExactly(
                "requirements", "design", "apply_migration", "implementation",
                "documentation", "testing", "release_readiness");
    }

    @Test
    void topologicalOrderRespectsEveryEdge() {
        GraphSpec graph = GraphFixtures.sdlcCore();
        List<String> order = GraphAlgorithms.topologicalOrder(graph);

        for (var node : graph.nodes()) {
            int nodeIndex = order.indexOf(node.id());
            for (String dep : node.dependsOn()) {
                assertThat(order.indexOf(dep))
                        .as("%s (dependency of %s) must come before it", dep, node.id())
                        .isLessThan(nodeIndex);
            }
        }
    }

    @Test
    void cycleIsDetectedAndNamesTheOffendingNodes() {
        assertThatThrownBy(() -> GraphAlgorithms.topologicalOrder(GraphFixtures.withCycleBetweenRequirementsAndDesign()))
                .isInstanceOf(GraphValidationException.class)
                .satisfies(ex -> {
                    GraphValidationException gve = (GraphValidationException) ex;
                    assertThat(gve.failures()).hasSize(1);
                    assertThat(gve.failures().get(0)).contains("requirements").contains("design");
                });
    }

    @Test
    void aHighRetryBudgetOnOneNodeDoesNotMakeTheGraphCyclic() {
        // The central structural argument (docs/architecture.md §3.5): retry is a
        // self-loop in the node state machine, invisible to the edge-set algorithm.
        List<String> order = GraphAlgorithms.topologicalOrder(GraphFixtures.withHighRetryBudgetOnOneNode());
        assertThat(order).containsExactly("requirements", "implementation");
    }

    // ---- parallelFrontiers -------------------------------------------------

    @Test
    void frontiersGroupTestingAndDocumentationInTheSameWave() {
        List<Set<String>> frontiers = GraphAlgorithms.parallelFrontiers(GraphFixtures.sdlcCore());

        assertThat(frontiers).containsExactly(
                Set.of("requirements"),
                Set.of("design"),
                Set.of("apply_migration"),
                Set.of("implementation"),
                Set.of("testing", "documentation"),
                Set.of("release_readiness"));
    }

    // ---- validateParallelismClaims -----------------------------------------

    @Test
    void parallelismClaimIsConsistentForTheRealGraph() {
        assertThat(GraphAlgorithms.validateParallelismClaims(GraphFixtures.sdlcCore())).isEmpty();
    }

    @Test
    void parallelismClaimIsRejectedWhenOneClaimedNodeDependsOnTheOther() {
        // The dangerous edit DEC-0011 exists to catch: a dependency was added
        // between two nodes still flagged as parallel.
        List<String> mismatches = GraphAlgorithms.validateParallelismClaims(GraphFixtures.withInconsistentParallelismClaim());

        assertThat(mismatches).isNotEmpty();
        assertThat(mismatches.get(0)).contains("testing").contains("documentation");
    }

    // ---- criticalPath -------------------------------------------------------

    @Test
    void criticalPathIsTheLongestWeightedPathThroughTheJoin() {
        GraphSpec graph = GraphFixtures.sdlcCore();
        Map<String, Double> durations = Map.of(
                "requirements", 14.2,
                "design", 31.8,
                "apply_migration", 0.0,
                "implementation", 68.4,
                "testing", 42.1,
                "documentation", 28.7,
                "release_readiness", 9.1);

        GraphAlgorithms.CriticalPath criticalPath = GraphAlgorithms.criticalPath(graph, durations);

        // testing (42.1) is longer than documentation (28.7), so the critical path
        // runs through testing, not documentation.
        assertThat(criticalPath.nodeIds()).containsExactly(
                "requirements", "design", "apply_migration", "implementation", "testing", "release_readiness");
        assertThat(criticalPath.totalDurationSeconds()).isEqualTo(14.2 + 31.8 + 0.0 + 68.4 + 42.1 + 9.1);
    }

    @Test
    void criticalPathDefaultsMissingDurationsToZero() {
        GraphSpec graph = GraphFixtures.sdlcCore();
        GraphAlgorithms.CriticalPath criticalPath = GraphAlgorithms.criticalPath(graph, Map.of());
        assertThat(criticalPath.totalDurationSeconds()).isEqualTo(0.0);
        assertThat(criticalPath.nodeIds()).hasSize(6); // one path from root to release_readiness
    }
}
