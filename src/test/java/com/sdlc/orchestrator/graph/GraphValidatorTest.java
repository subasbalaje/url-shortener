package com.sdlc.orchestrator.graph;

import com.sdlc.orchestrator.model.GraphSpec;
import com.sdlc.orchestrator.model.NodeSpec;
import com.sdlc.orchestrator.model.RePlanTrigger;
import com.sdlc.orchestrator.model.RollbackAction;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GraphValidatorTest {

    // ---- 1. schema_version -------------------------------------------------

    @Test
    void rejectsWrongSchemaVersion() {
        GraphSpec graph = new GraphSpec("g", "g", List.of(NodeSpec.builder("a", "A", "agent").build()),
                Map.of(), Map.of(), "0.9", "");

        assertThatThrownBy(() -> GraphValidator.validate(graph))
                .isInstanceOf(GraphValidationException.class)
                .satisfies(ex -> assertThat(((GraphValidationException) ex).failures())
                        .anyMatch(f -> f.contains("schema_version")));
    }

    // ---- 2. unique node ids --------------------------------------------------

    @Test
    void rejectsDuplicateNodeIds() {
        NodeSpec a1 = NodeSpec.builder("a", "First A", "agent").build();
        NodeSpec a2 = NodeSpec.builder("a", "Second A", "agent").build();
        GraphSpec graph = new GraphSpec("g", "g", List.of(a1, a2), Map.of(), Map.of(), "1.0", "");

        assertThatThrownBy(() -> GraphValidator.validate(graph))
                .isInstanceOf(GraphValidationException.class)
                .satisfies(ex -> assertThat(((GraphValidationException) ex).failures())
                        .anyMatch(f -> f.contains("Duplicate") && f.contains("'a'")));
    }

    // ---- 3. dangling depends_on ------------------------------------------------

    @Test
    void rejectsDanglingDependency() {
        NodeSpec a = NodeSpec.builder("a", "A", "agent").dependsOn(List.of("no-such-node")).build();
        GraphSpec graph = new GraphSpec("g", "g", List.of(a), Map.of(), Map.of(), "1.0", "");

        assertThatThrownBy(() -> GraphValidator.validate(graph))
                .isInstanceOf(GraphValidationException.class)
                .satisfies(ex -> assertThat(((GraphValidationException) ex).failures())
                        .anyMatch(f -> f.contains("no-such-node") && f.contains("depends_on")));
    }

    // ---- 4. acyclic ------------------------------------------------------------

    @Test
    void rejectsACycleNamingTheNodes() {
        assertThatThrownBy(() -> GraphValidator.validate(GraphFixtures.withCycleBetweenRequirementsAndDesign()))
                .isInstanceOf(GraphValidationException.class)
                .satisfies(ex -> assertThat(((GraphValidationException) ex).failures())
                        .anyMatch(f -> f.contains("Cycle") && f.contains("requirements") && f.contains("design")));
    }

    // ---- 5. produces must be registered ------------------------------------------

    @Test
    void rejectsAnUnregisteredProducedArtifact() {
        NodeSpec a = NodeSpec.builder("a", "A", "agent").produces(List.of("not_registered")).build();
        GraphSpec graph = new GraphSpec("g", "g", List.of(a), Map.of(), Map.of(), "1.0", "");

        assertThatThrownBy(() -> GraphValidator.validate(graph))
                .isInstanceOf(GraphValidationException.class)
                .satisfies(ex -> assertThat(((GraphValidationException) ex).failures())
                        .anyMatch(f -> f.contains("not_registered")));
    }

    // ---- 6. re_plan_triggers.artifact must be registered -------------------------

    @Test
    void rejectsAnUnregisteredRePlanTriggerArtifact() {
        NodeSpec a = NodeSpec.builder("a", "A", "agent")
                .rePlanTriggers(List.of(new RePlanTrigger("ghost_artifact", "mark_stale", "")))
                .build();
        GraphSpec graph = new GraphSpec("g", "g", List.of(a), Map.of(), Map.of(), "1.0", "");

        assertThatThrownBy(() -> GraphValidator.validate(graph))
                .isInstanceOf(GraphValidationException.class)
                .satisfies(ex -> assertThat(((GraphValidationException) ex).failures())
                        .anyMatch(f -> f.contains("ghost_artifact")));
    }

    // ---- 7. can_run_parallel must agree with the computed frontier ---------------

    @Test
    void rejectsAnInconsistentParallelismClaim() {
        assertThatThrownBy(() -> GraphValidator.validate(GraphFixtures.withInconsistentParallelismClaim()))
                .isInstanceOf(GraphValidationException.class)
                .satisfies(ex -> assertThat(((GraphValidationException) ex).failures())
                        .anyMatch(f -> f.contains("can_run_parallel") || f.contains("execution wave")));
    }

    // ---- 8. requires_human_approval must agree with structural classification -----

    @Test
    void rejectsASchemaChangingNodeThatDoesNotRequireApproval() {
        // Mirrors architecture.md §7 risk 9: apply_migration declaring false.
        NodeSpec applyMigration = NodeSpec.builder("apply_migration", "Apply Migration", "implementation_agent")
                .produces(List.of("migration_applied"))
                .rollbackAction(new RollbackAction("execute_sql", "", Map.of(), "safe_stop_and_page"))
                .requiresHumanApproval(false) // <-- the drift
                .build();
        GraphSpec graph = new GraphSpec("g", "g", List.of(applyMigration),
                Map.of("migration_applied", Map.of()), Map.of(), "1.0", "");

        assertThatThrownBy(() -> GraphValidator.validate(graph))
                .isInstanceOf(GraphValidationException.class)
                .satisfies(ex -> assertThat(((GraphValidationException) ex).failures())
                        .anyMatch(f -> f.contains("apply_migration") && f.contains("requires_human_approval")));
    }

    @Test
    void rejectsAReleaseReadinessNodeThatDoesNotRequireApproval() {
        NodeSpec releaseReadiness = NodeSpec.builder("release_readiness", "Release Readiness", "release_readiness_agent")
                .requiresHumanApproval(false) // <-- the drift
                .build();
        GraphSpec graph = new GraphSpec("g", "g", List.of(releaseReadiness), Map.of(), Map.of(), "1.0", "");

        assertThatThrownBy(() -> GraphValidator.validate(graph))
                .isInstanceOf(GraphValidationException.class)
                .satisfies(ex -> assertThat(((GraphValidationException) ex).failures())
                        .anyMatch(f -> f.contains("release_readiness") && f.contains("requires_human_approval")));
    }

    @Test
    void acceptsANonHighImpactNodeThatDoesNotRequireApproval() {
        NodeSpec design = NodeSpec.builder("design", "Design", "design_agent").requiresHumanApproval(false).build();
        GraphSpec graph = new GraphSpec("g", "g", List.of(design), Map.of(), Map.of(), "1.0", "");

        assertThatCode(() -> GraphValidator.validate(graph)).doesNotThrowAnyException();
    }

    // ---- 9. schema-changing nodes must declare a rollback_action -----------------

    @Test
    void rejectsASchemaChangingNodeWithNoRollbackAction() {
        NodeSpec applyMigration = NodeSpec.builder("apply_migration", "Apply Migration", "implementation_agent")
                .produces(List.of("migration_applied"))
                .requiresHumanApproval(true)
                .build(); // no rollbackAction
        GraphSpec graph = new GraphSpec("g", "g", List.of(applyMigration),
                Map.of("migration_applied", Map.of()), Map.of(), "1.0", "");

        assertThatThrownBy(() -> GraphValidator.validate(graph))
                .isInstanceOf(GraphValidationException.class)
                .satisfies(ex -> assertThat(((GraphValidationException) ex).failures())
                        .anyMatch(f -> f.contains("apply_migration") && f.contains("rollback_action")));
    }

    // ---- fail-closed: every failure reported at once ------------------------------

    @Test
    void collectsAllFailuresInOnePass() {
        NodeSpec bad = NodeSpec.builder("a", "A", "agent")
                .dependsOn(List.of("missing"))
                .produces(List.of("not_registered"))
                .build();
        GraphSpec graph = new GraphSpec("g", "g", List.of(bad), Map.of(), Map.of(), "0.9", "");

        assertThatThrownBy(() -> GraphValidator.validate(graph))
                .isInstanceOf(GraphValidationException.class)
                .satisfies(ex -> {
                    List<String> failures = ((GraphValidationException) ex).failures();
                    // schema_version wrong, dangling dependency, AND unregistered
                    // artifact must all be reported together, not just the first.
                    assertThat(failures).hasSizeGreaterThanOrEqualTo(3);
                });
    }

    // ---- the real graph shape validates clean --------------------------------------

    @Test
    void theRealSdlcGraphShapeValidatesWithoutError() {
        assertThatCode(() -> GraphValidator.validate(GraphFixtures.sdlcCore())).doesNotThrowAnyException();
    }
}
