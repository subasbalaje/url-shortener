package com.sdlc.orchestrator.model;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A small linear-with-fanout fixture, shaped like the real SDLC graph but with
 *  fewer nodes, used to test the pure graph-navigation helpers on GraphSpec. Full
 *  topological/parallelism algorithms are GraphAlgorithms' concern (tested
 *  separately against the real graph shape). */
class GraphSpecTest {

    private GraphSpec fixture() {
        NodeSpec requirements = NodeSpec.builder("requirements", "Requirements", "requirements_agent")
                .produces(List.of("requirement_spec"))
                .build();
        NodeSpec design = NodeSpec.builder("design", "Design", "design_agent")
                .dependsOn(List.of("requirements"))
                .produces(List.of("design_doc"))
                .build();
        NodeSpec testing = NodeSpec.builder("testing", "Testing", "testing_agent")
                .dependsOn(List.of("design"))
                .produces(List.of("test_results"))
                .build();
        NodeSpec docs = NodeSpec.builder("documentation", "Docs", "documentation_agent")
                .dependsOn(List.of("design"))
                .produces(List.of("documentation"))
                .build();

        return new GraphSpec("sdlc-core", "fixture", List.of(requirements, design, testing, docs),
                Map.of("requirement_spec", Map.of(), "design_doc", Map.of(),
                        "test_results", Map.of(), "documentation", Map.of()),
                Map.of(), "1.0", "fixture graph");
    }

    @Test
    void nodeLooksUpById() {
        assertThat(fixture().node("design").id()).isEqualTo("design");
    }

    @Test
    void nodeThrowsForUnknownId() {
        assertThatThrownBy(() -> fixture().node("no-such-node"))
                .isInstanceOf(java.util.NoSuchElementException.class)
                .hasMessageContaining("no-such-node");
    }

    @Test
    void nodeIdsReturnsAllNodesInDeclarationOrder() {
        assertThat(fixture().nodeIds()).containsExactly("requirements", "design", "testing", "documentation");
    }

    @Test
    void dependentsOfReturnsDirectSuccessorsOnly() {
        // design has two direct dependents (testing, documentation); requirements has
        // one (design).
        assertThat(fixture().dependentsOf("design")).containsExactlyInAnyOrder("testing", "documentation");
        assertThat(fixture().dependentsOf("requirements")).containsExactly("design");
        assertThat(fixture().dependentsOf("testing")).isEmpty();
    }

    @Test
    void transitiveDependentsOfReturnsAllDescendants() {
        assertThat(fixture().transitiveDependentsOf("requirements"))
                .containsExactlyInAnyOrder("design", "testing", "documentation");
        assertThat(fixture().transitiveDependentsOf("testing")).isEmpty();
    }

    @Test
    void producerOfFindsTheDeclaringNode() {
        assertThat(fixture().producerOf("design_doc")).isEqualTo("design");
        assertThat(fixture().producerOf("no_such_artifact")).isNull();
    }
}
