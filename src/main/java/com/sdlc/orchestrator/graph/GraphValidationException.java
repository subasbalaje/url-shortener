package com.sdlc.orchestrator.graph;

import java.util.List;

/**
 * Raised when the graph definition is invalid. Carries every failure, not just the
 * first — fixing a graph one error per run is needless friction, and because the
 * graph is the governance artifact, a malformed one must not be partially honoured.
 */
public class GraphValidationException extends RuntimeException {

    private final List<String> failures;

    public GraphValidationException(List<String> failures) {
        super("Graph validation failed with " + failures.size() + " error(s):\n  - "
                + String.join("\n  - ", failures));
        this.failures = List.copyOf(failures);
    }

    public List<String> failures() {
        return failures;
    }
}
