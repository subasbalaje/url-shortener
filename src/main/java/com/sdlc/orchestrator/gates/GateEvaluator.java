package com.sdlc.orchestrator.gates;

import com.sdlc.orchestrator.model.Gate;
import com.sdlc.orchestrator.model.GateCondition;
import com.sdlc.orchestrator.model.NodeSpec;
import com.sdlc.orchestrator.model.Run;

import java.util.ArrayList;
import java.util.List;

/**
 * Evaluates every condition in a {@link Gate} (AND semantics), dispatching each to
 * the registered {@link ConditionChecker}.
 *
 * <p><b>Never short-circuits</b>: every condition is evaluated even after one has
 * already failed, so a failure report lists everything wrong at once — an agent
 * retrying with feedback can fix all of it in one pass instead of discovering
 * problems serially (the same reasoning behind {@link CheckResult} returning a
 * message rather than throwing).
 *
 * <p>Fail-closed by construction: an unregistered condition {@code type}
 * propagates {@link UnknownCheckerException} out of {@link CheckerRegistry#get}
 * rather than being silently skipped.
 */
public final class GateEvaluator {

    /** @param failures the declared {@code error} for every condition that failed,
     *  in declaration order; empty means the gate passed. */
    public record Result(boolean passed, List<String> failures) {}

    private GateEvaluator() {}

    public static Result evaluate(Run run, NodeSpec node, Gate gate, CheckerRegistry registry) {
        if (gate == null) {
            return new Result(true, List.of());
        }

        List<String> failures = new ArrayList<>();
        for (GateCondition condition : gate.conditions()) {
            ConditionChecker checker = registry.get(condition.type());
            CheckResult result = checker.check(run, node, condition.params());
            if (!result.passed()) {
                // The condition's own declared `error` is written for a human
                // reader (GateCondition javadoc); prefer it over the checker's
                // generic fallback message, which exists for callers that build a
                // GateCondition without an explicit error string.
                String message = condition.error() != null && !condition.error().isBlank()
                        ? condition.error()
                        : result.message();
                failures.add(message);
            }
        }
        return new Result(failures.isEmpty(), List.copyOf(failures));
    }
}
