package com.sdlc.orchestrator.gates;

import com.sdlc.orchestrator.model.NodeSpec;
import com.sdlc.orchestrator.model.Run;

import java.util.Map;

/**
 * A checker answers one question about run state. Returning a message rather than
 * throwing lets the executor collect ALL failures in one pass, so a retrying agent
 * fixes everything at once instead of discovering problems serially.
 */
@FunctionalInterface
public interface ConditionChecker {
    CheckResult check(Run run, NodeSpec node, Map<String, Object> params);
}
