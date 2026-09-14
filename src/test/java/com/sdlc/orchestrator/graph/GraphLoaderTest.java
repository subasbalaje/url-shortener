package com.sdlc.orchestrator.graph;

import com.sdlc.orchestrator.model.GateType;
import com.sdlc.orchestrator.model.GraphSpec;
import com.sdlc.orchestrator.model.NodeSpec;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GraphLoaderTest {

    private String fixture(String name) throws IOException {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("graphs/" + name)) {
            if (in == null) {
                throw new IllegalStateException("Fixture not found on classpath: graphs/" + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    // ---- valid.yaml: every mapped shape ------------------------------------------

    @Test
    void loadsGraphLevelMetadata() throws IOException {
        GraphSpec graph = GraphLoader.loadFromString(fixture("valid.yaml"));
        assertThat(graph.id()).isEqualTo("fixture-graph");
        assertThat(graph.schemaVersion()).isEqualTo("1.0");
        assertThat(graph.artifacts()).containsKeys("requirement_spec", "design_doc");
    }

    @Test
    void loadsNodeBasicFields() throws IOException {
        GraphSpec graph = GraphLoader.loadFromString(fixture("valid.yaml"));
        NodeSpec design = graph.node("design");

        assertThat(design.name()).isEqualTo("Design");
        assertThat(design.agentRole()).isEqualTo("design_agent");
        assertThat(design.dependsOn()).containsExactly("requirements");
        assertThat(design.produces()).containsExactly("design_doc");
        assertThat(design.requiresHumanApproval()).isFalse();
        assertThat(design.metricsTags()).containsExactly("stage:design", "critical_path:true");
    }

    @Test
    void loadsEntryAndExitGatesWithConditionParams() throws IOException {
        GraphSpec graph = GraphLoader.loadFromString(fixture("valid.yaml"));
        NodeSpec design = graph.node("design");

        assertThat(design.entryGate().gateType()).isEqualTo(GateType.ENTRY);
        assertThat(design.entryGate().conditions()).hasSize(2);
        assertThat(design.entryGate().conditions().get(0).type()).isEqualTo("artifact_present");
        assertThat(design.entryGate().conditions().get(0).params()).containsEntry("artifact", "requirement_spec");
        assertThat(design.entryGate().conditions().get(1).params()).containsEntry("node", "requirements")
                .containsEntry("equals", "COMPLETED");

        assertThat(design.exitGate().gateType()).isEqualTo(GateType.EXIT);
        assertThat(design.exitGate().conditions().get(0).params()).containsEntry("field", "design_doc.components");
    }

    @Test
    void loadsNonDefaultRetryPolicy() throws IOException {
        GraphSpec graph = GraphLoader.loadFromString(fixture("valid.yaml"));
        NodeSpec requirements = graph.node("requirements");

        assertThat(requirements.retryPolicy().maxAttempts()).isEqualTo(2);
        assertThat(requirements.retryPolicy().initialIntervalSeconds()).isEqualTo(1.0);
        assertThat(requirements.retryPolicy().nonRetryableErrors())
                .containsExactlyInAnyOrder("PolicyViolationError", "HumanRejectedError");
    }

    @Test
    void nodeWithoutAnExplicitRetryPolicyInheritsGraphDefaults() throws IOException {
        GraphSpec graph = GraphLoader.loadFromString(fixture("valid.yaml"));
        NodeSpec design = graph.node("design"); // valid.yaml declares no retry_policy for design

        assertThat(design.retryPolicy().maxAttempts()).isEqualTo(3);
        assertThat(design.retryPolicy().initialIntervalSeconds()).isEqualTo(2.0);
        assertThat(design.retryPolicy().nonRetryableErrors())
                .containsExactlyInAnyOrder("ValidationError", "PolicyViolationError", "HumanRejectedError");
    }

    @Test
    void loadsRollbackAction() throws IOException {
        GraphSpec graph = GraphLoader.loadFromString(fixture("valid.yaml"));
        NodeSpec requirements = graph.node("requirements");

        assertThat(requirements.rollbackAction().type()).isEqualTo("escalate_to_human");
        assertThat(requirements.rollbackAction().onRollbackFailure()).isEqualTo("safe_stop_and_page");
    }

    @Test
    void loadsRePlanTriggers() throws IOException {
        GraphSpec graph = GraphLoader.loadFromString(fixture("valid.yaml"));
        NodeSpec design = graph.node("design");

        assertThat(design.rePlanTriggers()).hasSize(1);
        assertThat(design.rePlanTriggers().get(0).artifact()).isEqualTo("requirement_spec");
        assertThat(design.rePlanTriggers().get(0).onChange()).isEqualTo("mark_stale");
    }

    // ---- invalid fixtures: loading surfaces GraphValidationException --------------

    @Test
    void cyclicYamlFailsToLoadNamingTheCycle() throws IOException {
        assertThatThrownBy(() -> GraphLoader.loadFromString(fixture("cyclic.yaml")))
                .isInstanceOf(GraphValidationException.class)
                .satisfies(ex -> assertThat(((GraphValidationException) ex).failures())
                        .anyMatch(f -> f.contains("Cycle") && f.contains("requirements") && f.contains("design")));
    }

    @Test
    void danglingDepYamlFailsToLoad() throws IOException {
        assertThatThrownBy(() -> GraphLoader.loadFromString(fixture("dangling-dep.yaml")))
                .isInstanceOf(GraphValidationException.class)
                .satisfies(ex -> assertThat(((GraphValidationException) ex).failures())
                        .anyMatch(f -> f.contains("requirements") && f.contains("depends_on")));
    }

    @Test
    void badParallelClaimYamlFailsToLoad() throws IOException {
        assertThatThrownBy(() -> GraphLoader.loadFromString(fixture("bad-parallel-claim.yaml")))
                .isInstanceOf(GraphValidationException.class)
                .satisfies(ex -> assertThat(((GraphValidationException) ex).failures())
                        .anyMatch(f -> f.contains("testing") && f.contains("documentation")));
    }

    @Test
    void policyDriftYamlFailsToLoad() throws IOException {
        assertThatThrownBy(() -> GraphLoader.loadFromString(fixture("policy-drift.yaml")))
                .isInstanceOf(GraphValidationException.class)
                .satisfies(ex -> assertThat(((GraphValidationException) ex).failures())
                        .anyMatch(f -> f.contains("apply_migration") && f.contains("requires_human_approval")));
    }

    // ---- the real deliverable graph ------------------------------------------------

    @Test
    void theRealOrchestrationGraphYamlLoadsAndValidates() throws IOException {
        // Repo-root-relative, since Maven runs with the module root as the working
        // directory. This is the one test that proves the actual deliverable file
        // (not a synthetic fixture) loads through our Java loader end to end.
        GraphSpec graph = GraphLoader.load(Path.of("docs/orchestration-graph.yaml"));

        assertThat(graph.nodes()).hasSize(7);
        assertThat(GraphAlgorithms.topologicalOrder(graph)).containsExactly(
                "requirements", "design", "apply_migration", "implementation",
                "documentation", "testing", "release_readiness");
        assertThat(GraphAlgorithms.validateParallelismClaims(graph)).isEmpty();
    }
}
