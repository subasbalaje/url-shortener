package com.sdlc.orchestrator.gates;

import com.sdlc.orchestrator.graph.GraphLoader;
import com.sdlc.orchestrator.model.GateCondition;
import com.sdlc.orchestrator.model.GraphSpec;
import com.sdlc.orchestrator.model.NodeSpec;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Closes the loop between GraphLoader and gates/: every condition `type` the real
 *  graph actually declares must have a registered checker, or a run would hit
 *  UnknownCheckerException the first time that gate is evaluated — a failure mode
 *  that should be caught here, at build time, not discovered mid-run. */
class AllCheckersRegisteredTest {

    @Test
    void everyConditionTypeInTheRealGraphHasARegisteredChecker() throws IOException {
        CheckerRegistry registry = GateCheckers.newFullyRegisteredRegistry();
        GraphSpec graph = GraphLoader.load(Path.of("docs/orchestration-graph.yaml"));

        List<String> unregistered = new ArrayList<>();
        for (NodeSpec node : graph.nodes()) {
            collectUnregistered(registry, node.entryGate(), unregistered);
            collectUnregistered(registry, node.exitGate(), unregistered);
        }

        assertThat(unregistered).isEmpty();
    }

    private void collectUnregistered(CheckerRegistry registry, com.sdlc.orchestrator.model.Gate gate, List<String> unregistered) {
        if (gate == null) {
            return;
        }
        for (GateCondition condition : gate.conditions()) {
            if (!registry.isRegistered(condition.type())) {
                unregistered.add(condition.type());
            }
        }
    }
}
