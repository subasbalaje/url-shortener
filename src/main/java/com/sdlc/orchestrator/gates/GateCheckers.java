package com.sdlc.orchestrator.gates;

/**
 * Single entry point wiring up every checker group ({@link GenericCheckers},
 * {@link ArtifactCheckers}, {@link SourceCodeCheckers}) into one {@link CheckerRegistry}.
 * Callers (eventually {@code GateEvaluator}/{@code GraphExecutor}) should build their
 * registry through here rather than remembering to call each group's
 * {@code registerDefaults} separately.
 */
public final class GateCheckers {

    private GateCheckers() {}

    public static CheckerRegistry newFullyRegisteredRegistry() {
        CheckerRegistry registry = new CheckerRegistry();
        GenericCheckers.registerDefaults(registry);
        ArtifactCheckers.registerDefaults(registry);
        SourceCodeCheckers.registerDefaults(registry);
        return registry;
    }
}
