package com.sdlc.orchestrator.gates;

import com.sdlc.orchestrator.model.Artifact;
import com.sdlc.orchestrator.model.Run;

import java.util.List;
import java.util.Map;

/**
 * Resolves the dotted field paths gate conditions reference (e.g.
 * {@code "requirement_spec.acceptance_criteria"}, {@code "run_context.mode"}) against
 * a {@link Run}'s artifacts and a small set of run-level fields.
 *
 * <p>This is <b>read-only field lookup</b>, not a general expression language — the
 * restricted grammar {@link ExpressionEvaluator} builds on top of it is a deliberate
 * choice (see {@code CLAUDE-CODE-PROMPT.md} §3.6): the graph YAML is a data file
 * defining a sandbox, and wiring in a scripting engine here would let a graph edit
 * escape the very sandbox the graph exists to define.
 */
public final class RunFieldResolver {

    private RunFieldResolver() {}

    /**
     * @param path a dotted path. The first segment is either a special run-level
     *     name ({@code raw_request}, {@code run_context.<field>}) or an artifact
     *     key; remaining segments navigate into that artifact's content (assumed
     *     JSON-shaped: nested {@code Map}/{@code List}).
     * @return the resolved value, or {@code null} if any segment is absent
     */
    public static Object resolve(Run run, String path) {
        if (path.equals("raw_request")) {
            return run.rawRequest();
        }
        if (path.startsWith("run_context.")) {
            String field = path.substring("run_context.".length());
            return switch (field) {
                case "mode" -> run.mode();
                case "run_id" -> run.runId();
                default -> null;
            };
        }

        String[] segments = path.split("\\.");
        Artifact artifact = run.artifacts().get(segments[0]);
        if (artifact == null) {
            return null;
        }

        Object current = artifact.content();
        for (int i = 1; i < segments.length; i++) {
            current = navigate(current, segments[i]);
            if (current == null) {
                return null;
            }
        }
        return current;
    }

    private static Object navigate(Object current, String segment) {
        if (current instanceof Map<?, ?> map) {
            return map.get(segment);
        }
        if (current instanceof List<?> list) {
            try {
                return list.get(Integer.parseInt(segment));
            } catch (NumberFormatException | IndexOutOfBoundsException e) {
                return null;
            }
        }
        return null;
    }
}
