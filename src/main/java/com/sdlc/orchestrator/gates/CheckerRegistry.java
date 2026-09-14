package com.sdlc.orchestrator.gates;

import java.util.LinkedHashMap;
import java.util.Map;

/** Maps the YAML {@code type} string on a {@code GateCondition} to a checker
 *  implementation. Fail-closed: {@link #get} throws on an unregistered type rather
 *  than returning something that vacuously passes. */
public final class CheckerRegistry {

    private final Map<String, ConditionChecker> checkers = new LinkedHashMap<>();

    public void register(String type, ConditionChecker checker) {
        checkers.put(type, checker);
    }

    public ConditionChecker get(String type) {
        ConditionChecker checker = checkers.get(type);
        if (checker == null) {
            throw new UnknownCheckerException(type);
        }
        return checker;
    }

    public boolean isRegistered(String type) {
        return checkers.containsKey(type);
    }
}
