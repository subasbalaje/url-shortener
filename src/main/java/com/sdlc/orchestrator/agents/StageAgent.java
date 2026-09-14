package com.sdlc.orchestrator.agents;

import com.sdlc.orchestrator.model.NodeSpec;
import com.sdlc.orchestrator.model.Run;

import java.util.Map;

/**
 * Signature every stage agent must satisfy. Agents receive the run (for context and
 * prior artifacts) and their own spec; they return the artifact contents they
 * produced, keyed by artifact name (matching {@link NodeSpec#produces()}).
 *
 * <p>Agents <b>never mutate {@code run}</b> — only {@link
 * com.sdlc.orchestrator.exec.GraphExecutor} may do that. This is what keeps the
 * audit trail single-writer (DEC-0013): an agent that could touch {@code Run}
 * directly would be a second, unaudited writer.
 */
@FunctionalInterface
public interface StageAgent {
    Map<String, Object> execute(Run run, NodeSpec node) throws Exception;
}
