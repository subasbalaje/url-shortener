package com.sdlc.orchestrator.graph;

import com.sdlc.orchestrator.gates.PolicyEngine;
import com.sdlc.orchestrator.model.GraphSpec;
import com.sdlc.orchestrator.model.NodeSpec;
import com.sdlc.orchestrator.model.RePlanTrigger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Validates a parsed {@link GraphSpec} against the nine checks documented in
 * {@code orchestrator/README.md}'s build order. Fail-closed: every failure is
 * collected and reported together, never just the first — fixing a graph one error
 * per run is needless friction, and because the graph is the governance artifact, a
 * malformed one must not be partially honoured.
 */
public final class GraphValidator {

    /** Schema version this validator understands. A graph declaring anything else is
     *  rejected rather than guessed at. */
    public static final String SCHEMA_VERSION = "1.0";

    private GraphValidator() {}

    public static void validate(GraphSpec graph) {
        List<String> failures = new ArrayList<>();

        checkSchemaVersion(graph, failures);
        boolean hasDuplicateIds = checkUniqueNodeIds(graph, failures);
        checkDependenciesResolve(graph, failures);
        if (!hasDuplicateIds) {
            // Duplicate ids corrupt the in-degree map Kahn's algorithm builds
            // (two distinct nodes collapse onto one key), producing a confusing
            // derived "cycle" report about the same root cause already named above.
            checkAcyclic(graph, failures);
        }
        checkProducesRegistered(graph, failures);
        checkRePlanTriggersRegistered(graph, failures);
        if (!hasDuplicateIds) {
            checkParallelismClaims(graph, failures);
        }
        checkHighImpactDeclarations(graph, failures);
        checkSchemaChangingNodesDeclareRollback(graph, failures);

        if (!failures.isEmpty()) {
            throw new GraphValidationException(failures);
        }
    }

    /** 1. {@code schema_version} matches {@link #SCHEMA_VERSION}. */
    private static void checkSchemaVersion(GraphSpec graph, List<String> failures) {
        if (!SCHEMA_VERSION.equals(graph.schemaVersion())) {
            failures.add("Unsupported schema_version '" + graph.schemaVersion()
                    + "'; expected '" + SCHEMA_VERSION + "'.");
        }
    }

    /** 2. Node ids are unique.
     *
     * @return true if a duplicate was found */
    private static boolean checkUniqueNodeIds(GraphSpec graph, List<String> failures) {
        Set<String> seen = new HashSet<>();
        boolean foundDuplicate = false;
        for (NodeSpec n : graph.nodes()) {
            if (!seen.add(n.id())) {
                failures.add("Duplicate node id: '" + n.id() + "'.");
                foundDuplicate = true;
            }
        }
        return foundDuplicate;
    }

    /** 3. Every {@code depends_on} names an existing node. */
    private static void checkDependenciesResolve(GraphSpec graph, List<String> failures) {
        Set<String> ids = new HashSet<>(graph.nodeIds());
        for (NodeSpec n : graph.nodes()) {
            for (String dep : n.dependsOn()) {
                if (!ids.contains(dep)) {
                    failures.add("Node '" + n.id() + "' has depends_on '" + dep
                            + "', which does not name an existing node.");
                }
            }
        }
    }

    /** 4. The edge set is acyclic (Kahn; delegates to {@link GraphAlgorithms}). */
    private static void checkAcyclic(GraphSpec graph, List<String> failures) {
        try {
            GraphAlgorithms.topologicalOrder(graph);
        } catch (GraphValidationException e) {
            failures.addAll(e.failures());
        }
    }

    /** 5. Every {@code produces} key exists in the artifact registry. */
    private static void checkProducesRegistered(GraphSpec graph, List<String> failures) {
        for (NodeSpec n : graph.nodes()) {
            for (String artifact : n.produces()) {
                if (!graph.artifacts().containsKey(artifact)) {
                    failures.add("Node '" + n.id() + "' produces unregistered artifact '"
                            + artifact + "'; add it to the graph's artifact registry.");
                }
            }
        }
    }

    /** 6. Every {@code re_plan_triggers.artifact} names a registered artifact. */
    private static void checkRePlanTriggersRegistered(GraphSpec graph, List<String> failures) {
        for (NodeSpec n : graph.nodes()) {
            for (RePlanTrigger trigger : n.rePlanTriggers()) {
                if (!graph.artifacts().containsKey(trigger.artifact())) {
                    failures.add("Node '" + n.id() + "' declares a re_plan_trigger on unregistered artifact '"
                            + trigger.artifact() + "'.");
                }
            }
        }
    }

    /** 7. {@code can_run_parallel} claims agree with the computed frontier (DEC-0011). */
    private static void checkParallelismClaims(GraphSpec graph, List<String> failures) {
        try {
            failures.addAll(GraphAlgorithms.validateParallelismClaims(graph));
        } catch (GraphValidationException e) {
            // The graph is already cyclic (reported by checkAcyclic); parallelism
            // claims are meaningless until that is fixed, so don't pile on a second,
            // derived error about the same root cause.
        }
    }

    /**
     * 8. {@code requires_human_approval} agrees with the DEC-0006 policy criteria —
     * a node meeting a high-impact criterion may not declare {@code false}.
     *
     * <p>Delegates to {@link PolicyEngine}, which is a deliberately <b>narrow</b>
     * slice of DEC-0006 (see decision log entry DEC-0014): only the two criteria
     * structurally observable from a {@link NodeSpec} today are checked. Delegating
     * rather than keeping a second copy of the classification here is exactly
     * DEC-0014's stated consequence — the two must not be able to drift apart.
     */
    private static void checkHighImpactDeclarations(GraphSpec graph, List<String> failures) {
        for (NodeSpec n : graph.nodes()) {
            failures.addAll(PolicyEngine.validateDeclaration(n));
        }
    }

    /** 9. Every node with a schema-changing action declares a {@code rollback_action}. */
    private static void checkSchemaChangingNodesDeclareRollback(GraphSpec graph, List<String> failures) {
        for (NodeSpec n : graph.nodes()) {
            boolean schemaChanging = PolicyEngine.classify(n).contains(PolicyEngine.Criterion.SCHEMA_CHANGE);
            if (schemaChanging && n.rollbackAction() == null) {
                failures.add("Node '" + n.id() + "' performs a schema-changing action "
                        + "but declares no rollback_action.");
            }
        }
    }
}
