package com.sdlc.orchestrator.model;

import java.util.Map;

/**
 * Compensating action for a node, in the Saga sense.
 *
 * <p>Rollback is data on the node, so it is inspectable before it ever runs.
 * {@code onRollbackFailure} matters more than it looks: if compensation itself fails
 * the system must stop, not improvise — an orchestrator that keeps trying to fix a
 * failed fix is how small incidents become large ones.
 */
public record RollbackAction(String type, String description, Map<String, Object> params, String onRollbackFailure) {

    public RollbackAction {
        params = params == null ? Map.of() : Map.copyOf(params);
        description = description == null ? "" : description;
        onRollbackFailure = onRollbackFailure == null ? "safe_stop_and_page" : onRollbackFailure;
    }
}
