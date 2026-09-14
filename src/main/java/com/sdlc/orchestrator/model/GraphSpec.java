package com.sdlc.orchestrator.model;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

/** The whole parsed graph: nodes, artifact registry, and policy. */
public record GraphSpec(
        String id,
        String name,
        List<NodeSpec> nodes,
        Map<String, Map<String, Object>> artifacts,
        Map<String, Object> policy,
        String schemaVersion,
        String description
) {

    public GraphSpec {
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        artifacts = artifacts == null ? Map.of() : Map.copyOf(artifacts);
        policy = policy == null ? Map.of() : Map.copyOf(policy);
        description = description == null ? "" : description;
    }

    /** Look up a node by id, throwing a clear error if absent. */
    public NodeSpec node(String nodeId) {
        for (NodeSpec n : nodes) {
            if (n.id().equals(nodeId)) {
                return n;
            }
        }
        throw new NoSuchElementException("No such node in graph '" + id + "': '" + nodeId + "'");
    }

    public List<String> nodeIds() {
        List<String> ids = new ArrayList<>();
        for (NodeSpec n : nodes) {
            ids.add(n.id());
        }
        return List.copyOf(ids);
    }

    /** Direct successors — nodes listing {@code nodeId} in {@code dependsOn}. */
    public List<String> dependentsOf(String nodeId) {
        List<String> result = new ArrayList<>();
        for (NodeSpec n : nodes) {
            if (n.dependsOn().contains(nodeId)) {
                result.add(n.id());
            }
        }
        return List.copyOf(result);
    }

    /**
     * All descendants, via traversal over the edge set.
     *
     * <p>This is the candidate set for staleness propagation; the executor then
     * intersects it with each node's declared {@code rePlanTriggers}.
     *
     * <p>Safe against cycles by construction — the graph is validated acyclic at load
     * — but uses a {@code seen} set regardless, since a traversal that can hang is
     * worse than one that returns a wrong answer loudly.
     */
    public Set<String> transitiveDependentsOf(String nodeId) {
        Set<String> seen = new HashSet<>();
        Deque<String> frontier = new ArrayDeque<>(dependentsOf(nodeId));
        while (!frontier.isEmpty()) {
            String current = frontier.pop();
            if (!seen.add(current)) {
                continue;
            }
            frontier.addAll(dependentsOf(current));
        }
        return seen;
    }

    /** Which node produces {@code artifactKey}, if any. */
    public String producerOf(String artifactKey) {
        for (NodeSpec n : nodes) {
            if (n.produces().contains(artifactKey)) {
                return n.id();
            }
        }
        return null;
    }
}
