package com.sdlc.orchestrator.model;

import java.util.List;

/** A named collection of conditions evaluated together (AND semantics). */
public record Gate(GateType gateType, List<GateCondition> conditions, String description) {

    public Gate {
        conditions = conditions == null ? List.of() : List.copyOf(conditions);
        description = description == null ? "" : description;
    }
}
