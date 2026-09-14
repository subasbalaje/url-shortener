package com.sdlc.orchestrator.graph;

import com.sdlc.orchestrator.model.GraphSpec;
import com.sdlc.orchestrator.model.NodeSpec;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Pure graph algorithms. No I/O, no executor, no concurrency — unit-testable in
 * isolation. This is where hand-rolled orchestrators actually break, so it is
 * tested hard.
 */
public final class GraphAlgorithms {

    private GraphAlgorithms() {}

    /**
     * Valid execution order via Kahn's algorithm.
     *
     * <p>Operates on the <b>edge set only</b>. Retry self-loops are invisible here by
     * design — they live in the node state machine, not the DAG — which is the formal
     * reason bounded retry does not make the graph cyclic.
     *
     * @throws GraphValidationException naming the nodes in the cycle
     */
    public static List<String> topologicalOrder(GraphSpec graph) {
        Map<String, Integer> inDegree = new HashMap<>();
        for (NodeSpec n : graph.nodes()) {
            inDegree.put(n.id(), n.dependsOn().size());
        }

        // A TreeSet, not an ArrayDeque, so the frontier stays sorted across the whole
        // run — polling only the initial frontier in order and then appending
        // newly-ready nodes at the back would make the result depend on YAML
        // declaration order among same-wave nodes, which is not deterministic in the
        // sense this method promises.
        Set<String> ready = new TreeSet<>(inDegree.entrySet().stream()
                .filter(e -> e.getValue() == 0)
                .map(Map.Entry::getKey)
                .toList());

        List<String> order = new ArrayList<>();
        while (!ready.isEmpty()) {
            String id = ready.iterator().next();
            ready.remove(id);
            order.add(id);
            for (String dep : graph.dependentsOf(id)) {
                int remaining = inDegree.merge(dep, -1, Integer::sum);
                if (remaining == 0) {
                    ready.add(dep);
                }
            }
        }

        if (order.size() != graph.nodes().size()) {
            Set<String> inCycle = new TreeSet<>(inDegree.keySet());
            inCycle.removeAll(order);
            throw new GraphValidationException(List.of(
                    "Cycle detected. Nodes in or behind the cycle: " + inCycle));
        }
        return order;
    }

    /**
     * Group nodes into successive waves of mutually-independent nodes.
     *
     * <p>Wave <i>k</i> contains every node whose dependencies are all satisfied by
     * waves {@code < k}. Each wave <b>is</b> a parallelism opportunity, derived from
     * the edge set rather than declared — which is why {@code can_run_parallel} can
     * be checked rather than trusted.
     *
     * @throws GraphValidationException naming the nodes in a cycle, mirroring
     *     {@link #topologicalOrder}
     */
    public static List<Set<String>> parallelFrontiers(GraphSpec graph) {
        Map<String, Integer> inDegree = new HashMap<>();
        for (NodeSpec n : graph.nodes()) {
            inDegree.put(n.id(), n.dependsOn().size());
        }

        List<Set<String>> waves = new ArrayList<>();
        Set<String> remaining = new LinkedHashSet<>(inDegree.keySet());

        while (!remaining.isEmpty()) {
            Set<String> wave = new TreeSet<>();
            for (String id : remaining) {
                if (inDegree.get(id) == 0) {
                    wave.add(id);
                }
            }
            if (wave.isEmpty()) {
                throw new GraphValidationException(List.of(
                        "Cycle detected. Nodes in or behind the cycle: " + new TreeSet<>(remaining)));
            }
            for (String id : wave) {
                for (String dep : graph.dependentsOf(id)) {
                    inDegree.merge(dep, -1, Integer::sum);
                }
            }
            remaining.removeAll(wave);
            waves.add(wave);
        }
        return waves;
    }

    /**
     * Check each node's {@code can_run_parallel} against computed frontiers.
     *
     * <p>Implements DEC-0011: the declaration documents intent and is
     * <b>verified</b>, but never drives scheduling. Catches the dangerous edit —
     * adding a dependency between two nodes still flagged parallel — which under a
     * declaration-driven scheduler would run them concurrently in violation of that
     * dependency.
     *
     * @return human-readable mismatch descriptions; empty means consistent
     */
    public static List<String> validateParallelismClaims(GraphSpec graph) {
        List<Set<String>> frontiers = parallelFrontiers(graph);
        Map<String, Integer> waveOf = new HashMap<>();
        for (int i = 0; i < frontiers.size(); i++) {
            for (String id : frontiers.get(i)) {
                waveOf.put(id, i);
            }
        }

        List<String> mismatches = new ArrayList<>();
        for (NodeSpec node : graph.nodes()) {
            for (String partner : node.canRunParallel()) {
                Integer nodeWave = waveOf.get(node.id());
                Integer partnerWave = waveOf.get(partner);
                if (!nodeWave.equals(partnerWave)) {
                    mismatches.add(String.format(
                            "Node '%s' declares can_run_parallel with '%s', but they are not in the same "
                                    + "execution wave ('%s' is wave %d, '%s' is wave %d). "
                                    + "A dependency likely now exists between them.",
                            node.id(), partner, node.id(), nodeWave, partner, partnerWave));
                }
            }
        }
        return mismatches;
    }

    /** Longest weighted path from entry to terminal: the latency floor. */
    public record CriticalPath(List<String> nodeIds, double totalDurationSeconds) {}

    /**
     * Computed by relaxing edges <b>in topological order</b> — O(V+E), no
     * shortest-path machinery needed precisely because a topological order exists:
     * {@code earliestFinish[v] = duration[v] + max(earliestFinish[u] for u in deps)}.
     *
     * <p>Used to compare <i>expected</i> against <i>measured</i> end-to-end latency.
     * A gap is a signal (unexpected serialisation, gate wait, retries) rather than a
     * bare number with no baseline.
     */
    public static CriticalPath criticalPath(GraphSpec graph, Map<String, Double> durations) {
        List<String> order = topologicalOrder(graph);
        Map<String, Double> earliestFinish = new HashMap<>();
        Map<String, String> predecessor = new HashMap<>();

        for (String id : order) {
            double duration = durations.getOrDefault(id, 0.0);
            double best = 0.0;
            String bestPred = null;
            for (NodeSpec n : graph.nodes()) {
                if (n.id().equals(id)) {
                    for (String dep : n.dependsOn()) {
                        double candidate = earliestFinish.get(dep);
                        if (candidate >= best) {
                            best = candidate;
                            bestPred = dep;
                        }
                    }
                    break;
                }
            }
            earliestFinish.put(id, duration + best);
            if (bestPred != null) {
                predecessor.put(id, bestPred);
            }
        }

        // The critical path ends at whichever sink (no dependents) has the largest
        // earliestFinish — the graph's true terminal, not just the last node emitted.
        String end = null;
        double endValue = -1.0;
        for (String id : order) {
            if (graph.dependentsOf(id).isEmpty() && earliestFinish.get(id) > endValue) {
                end = id;
                endValue = earliestFinish.get(id);
            }
        }

        List<String> path = new ArrayList<>();
        String cursor = end;
        while (cursor != null) {
            path.add(cursor);
            cursor = predecessor.get(cursor);
        }
        java.util.Collections.reverse(path);

        return new CriticalPath(List.copyOf(path), endValue);
    }
}
