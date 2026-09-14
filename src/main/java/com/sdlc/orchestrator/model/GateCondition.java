package com.sdlc.orchestrator.model;

import java.util.Map;

/**
 * One machine-checkable predicate within a gate.
 *
 * <p>{@code type} selects a checker registered in {@code gates.CheckerRegistry};
 * {@code params} carries that checker's arguments. {@code error} is the message
 * surfaced when it fails — written for a human reader, since a failed gate is
 * something a person must act on.
 */
public record GateCondition(String type, String error, Map<String, Object> params) {

    public GateCondition(String type, String error) {
        this(type, error, Map.of());
    }

    public GateCondition {
        params = params == null ? Map.of() : Map.copyOf(params);
    }
}
