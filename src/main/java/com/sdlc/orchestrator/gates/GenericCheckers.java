package com.sdlc.orchestrator.gates;

import com.sdlc.orchestrator.model.NodeRuntime;
import com.sdlc.orchestrator.model.NodeState;
import com.sdlc.orchestrator.model.Run;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * The six graph-agnostic checkers every gate condition type in
 * {@code docs/orchestration-graph.yaml} that isn't specific to one exit-gate's
 * business rule ultimately reduces to: is this input present, is this field
 * non-empty, is that upstream node in the state I need.
 *
 * <p>The domain-specific checkers (e.g. {@code ambiguities_resolved_or_escalated},
 * {@code no_test_weakening}, {@code api_contract_conformance}) are a separate,
 * larger set — build-order step 6's full 20-checker registry — layered on top of
 * this generic core.
 */
public final class GenericCheckers {

    private GenericCheckers() {}

    public static void registerDefaults(CheckerRegistry registry) {
        registry.register("input_present", GenericCheckers::inputPresent);
        registry.register("artifact_present", GenericCheckers::artifactPresent);
        registry.register("field_non_empty", GenericCheckers::fieldNonEmpty);
        registry.register("upstream_state", GenericCheckers::upstreamState);
        registry.register("upstream_state_in", GenericCheckers::upstreamStateIn);
        registry.register("conditional_required", GenericCheckers::conditionalRequired);
    }

    private static CheckResult inputPresent(Run run, com.sdlc.orchestrator.model.NodeSpec node, Map<String, Object> params) {
        String field = (String) params.get("field");
        Object value = RunFieldResolver.resolve(run, field);
        if (isEmpty(value)) {
            return CheckResult.fail(errorOr(params, "Required input '" + field + "' is not present."));
        }
        return CheckResult.pass();
    }

    private static CheckResult artifactPresent(Run run, com.sdlc.orchestrator.model.NodeSpec node, Map<String, Object> params) {
        String artifact = (String) params.get("artifact");
        if (!run.artifacts().containsKey(artifact)) {
            return CheckResult.fail(errorOr(params, "Required artifact '" + artifact + "' is not present."));
        }
        return CheckResult.pass();
    }

    private static CheckResult fieldNonEmpty(Run run, com.sdlc.orchestrator.model.NodeSpec node, Map<String, Object> params) {
        String field = (String) params.get("field");
        Object value = RunFieldResolver.resolve(run, field);
        if (!isEmpty(value)) {
            return CheckResult.pass();
        }
        String allowEmptyIf = (String) params.get("allow_empty_if");
        if (allowEmptyIf != null && ExpressionEvaluator.evaluate(run, allowEmptyIf)) {
            return CheckResult.pass();
        }
        return CheckResult.fail(errorOr(params, "Field '" + field + "' is empty."));
    }

    private static CheckResult upstreamState(Run run, com.sdlc.orchestrator.model.NodeSpec node, Map<String, Object> params) {
        String upstreamNodeId = (String) params.get("node");
        String expected = (String) params.get("equals");
        NodeRuntime runtime = run.nodes().get(upstreamNodeId);
        if (runtime == null || runtime.state() != NodeState.valueOf(expected)) {
            String actual = runtime == null ? "not yet run" : runtime.state().name();
            return CheckResult.fail(errorOr(params, "Node '" + upstreamNodeId + "' is " + actual
                    + "; expected " + expected + "."));
        }
        return CheckResult.pass();
    }

    private static CheckResult upstreamStateIn(Run run, com.sdlc.orchestrator.model.NodeSpec node, Map<String, Object> params) {
        String upstreamNodeId = (String) params.get("node");
        @SuppressWarnings("unchecked")
        Collection<String> anyOf = (Collection<String>) params.get("any_of");
        NodeRuntime runtime = run.nodes().get(upstreamNodeId);
        boolean matches = runtime != null && anyOf.contains(runtime.state().name());
        if (!matches) {
            String actual = runtime == null ? "not yet run" : runtime.state().name();
            return CheckResult.fail(errorOr(params, "Node '" + upstreamNodeId + "' is " + actual
                    + "; expected one of " + anyOf + "."));
        }
        return CheckResult.pass();
    }

    /**
     * ⚠️ Uses {@link ExpressionEvaluator}'s restricted grammar only — see that
     * class's javadoc for why this must never be upgraded to a general scripting
     * engine.
     */
    private static CheckResult conditionalRequired(Run run, com.sdlc.orchestrator.model.NodeSpec node, Map<String, Object> params) {
        String when = (String) params.get("when");
        if (!ExpressionEvaluator.evaluate(run, when)) {
            return CheckResult.pass(); // guard doesn't apply in this run
        }
        return fieldNonEmpty(run, node, params);
    }

    private static boolean isEmpty(Object value) {
        if (value == null) return true;
        if (value instanceof String s) return s.isBlank();
        if (value instanceof Collection<?> c) return c.isEmpty();
        if (value instanceof Map<?, ?> m) return m.isEmpty();
        return false;
    }

    private static String errorOr(Map<String, Object> params, String fallback) {
        Object error = params.get("error");
        return error != null ? String.valueOf(error) : fallback;
    }
}
