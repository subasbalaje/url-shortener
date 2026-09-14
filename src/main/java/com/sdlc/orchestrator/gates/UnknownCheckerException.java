package com.sdlc.orchestrator.gates;

/**
 * Thrown when a gate condition names a {@code type} with no registered checker.
 *
 * <p>Fail-closed by design: a gate that silently skips a condition it does not
 * understand is worse than no gate, because it still looks enforced.
 */
public class UnknownCheckerException extends RuntimeException {
    public UnknownCheckerException(String type) {
        super("No checker registered for condition type '" + type + "'.");
    }
}
