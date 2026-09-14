package com.sdlc.orchestrator.graph;

import com.sdlc.orchestrator.model.GraphSpec;
import com.sdlc.orchestrator.model.NodeSpec;
import com.sdlc.orchestrator.model.RetryPolicy;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds the real 7-node SDLC graph shape (mirroring
 * {@code docs/orchestration-graph.yaml}) in memory, for tests that exercise the pure
 * graph algorithms without going through YAML parsing.
 */
final class GraphFixtures {

    private GraphFixtures() {}

    /** The 7-node graph, edges and {@code can_run_parallel} declarations matching
     *  docs/orchestration-graph.yaml exactly. Gate/retry/artifact details are omitted
     *  where the algorithm under test does not look at them. */
    static GraphSpec sdlcCore() {
        NodeSpec requirements = NodeSpec.builder("requirements", "Requirement understanding and normalisation", "requirements_agent")
                .produces(List.of("requirement_spec", "ambiguity_report"))
                .build();

        NodeSpec design = NodeSpec.builder("design", "Architecture and technical design", "design_agent")
                .dependsOn(List.of("requirements"))
                .produces(List.of("design_doc", "api_schema", "impact_analysis", "migration_plan"))
                .build();

        NodeSpec applyMigration = NodeSpec.builder("apply_migration", "Apply database migration", "implementation_agent")
                .dependsOn(List.of("design"))
                .produces(List.of("migration_applied"))
                .requiresHumanApproval(true)
                .rollbackAction(new com.sdlc.orchestrator.model.RollbackAction(
                        "execute_sql", "Run the reviewed rollback SQL.", Map.of(), "safe_stop_and_page"))
                .build();

        NodeSpec implementation = NodeSpec.builder("implementation", "Code implementation", "implementation_agent")
                .dependsOn(List.of("design", "apply_migration"))
                .produces(List.of("source_code"))
                .build();

        NodeSpec testing = NodeSpec.builder("testing", "Unit and integration testing", "testing_agent")
                .dependsOn(List.of("implementation"))
                .canRunParallel(List.of("documentation"))
                .produces(List.of("test_suite", "test_results"))
                .build();

        NodeSpec documentation = NodeSpec.builder("documentation", "User and developer documentation", "documentation_agent")
                .dependsOn(List.of("implementation"))
                .canRunParallel(List.of("testing"))
                .produces(List.of("documentation"))
                .build();

        NodeSpec releaseReadiness = NodeSpec.builder("release_readiness", "Release readiness assessment", "release_readiness_agent")
                .dependsOn(List.of("testing", "documentation"))
                .produces(List.of("readiness_report"))
                .requiresHumanApproval(true)
                .rollbackAction(new com.sdlc.orchestrator.model.RollbackAction(
                        "discard_artifacts", "Discard the report only.", Map.of(), "safe_stop_and_page"))
                .build();

        return new GraphSpec("sdlc-core", "Full SDLC: requirements through release readiness",
                List.of(requirements, design, applyMigration, implementation, testing, documentation, releaseReadiness),
                Map.ofEntries(
                        Map.entry("requirement_spec", Map.of()), Map.entry("ambiguity_report", Map.of()),
                        Map.entry("design_doc", Map.of()), Map.entry("api_schema", Map.of()),
                        Map.entry("impact_analysis", Map.of()), Map.entry("migration_plan", Map.of()),
                        Map.entry("migration_applied", Map.of()), Map.entry("source_code", Map.of()),
                        Map.entry("test_suite", Map.of()), Map.entry("test_results", Map.of()),
                        Map.entry("documentation", Map.of()), Map.entry("readiness_report", Map.of())),
                Map.of(), "1.0", "fixture mirroring docs/orchestration-graph.yaml");
    }

    /** {@code requirements -> design -> requirements} — a two-node cycle appended to
     *  an otherwise-valid graph, for cycle-detection tests. */
    static GraphSpec withCycleBetweenRequirementsAndDesign() {
        NodeSpec requirements = NodeSpec.builder("requirements", "Requirements", "requirements_agent")
                .dependsOn(List.of("design")) // <-- the cycle: design also depends on requirements
                .build();
        NodeSpec design = NodeSpec.builder("design", "Design", "design_agent")
                .dependsOn(List.of("requirements"))
                .build();
        NodeSpec implementation = NodeSpec.builder("implementation", "Implementation", "implementation_agent")
                .dependsOn(List.of("design"))
                .build();

        return new GraphSpec("cyclic", "cyclic fixture", List.of(requirements, design, implementation),
                Map.of(), Map.of(), "1.0", "");
    }

    /** A node with an unusually high retry budget. Retry lives in node-internal
     *  state, not the edge set, so this must still validate acyclic — the formal
     *  argument in docs/architecture.md §3.5. */
    static GraphSpec withHighRetryBudgetOnOneNode() {
        NodeSpec requirements = NodeSpec.builder("requirements", "Requirements", "requirements_agent").build();
        NodeSpec implementation = NodeSpec.builder("implementation", "Implementation", "implementation_agent")
                .dependsOn(List.of("requirements"))
                .retryPolicy(new RetryPolicy(5, 1.0, 2.0, 30.0, Set.of()))
                .build();

        return new GraphSpec("retry-fixture", "retry fixture", List.of(requirements, implementation),
                Map.of(), Map.of(), "1.0", "");
    }

    /** Two nodes that both depend on a common upstream node and declare
     *  {@code can_run_parallel} on each other, but one is ALSO wired to depend on
     *  the other — the dangerous edit DEC-0011's verification exists to catch. */
    static GraphSpec withInconsistentParallelismClaim() {
        NodeSpec implementation = NodeSpec.builder("implementation", "Implementation", "implementation_agent").build();
        NodeSpec testing = NodeSpec.builder("testing", "Testing", "testing_agent")
                .dependsOn(List.of("implementation"))
                .canRunParallel(List.of("documentation"))
                .build();
        // documentation now ALSO depends on testing — no longer in the same
        // frontier — while still claiming parallelism with it.
        NodeSpec documentation = NodeSpec.builder("documentation", "Documentation", "documentation_agent")
                .dependsOn(List.of("implementation", "testing"))
                .canRunParallel(List.of("testing"))
                .build();

        return new GraphSpec("bad-parallel-claim", "bad parallel claim fixture",
                List.of(implementation, testing, documentation), Map.of(), Map.of(), "1.0", "");
    }
}
