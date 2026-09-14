package com.sdlc.orchestrator.exec;

import com.sdlc.orchestrator.model.NodeSpec;
import com.sdlc.orchestrator.model.RollbackAction;
import com.sdlc.orchestrator.model.Run;

/**
 * Executes one {@link RollbackAction}. Dispatched by {@code type}
 * ({@code discard_artifacts}, {@code revert_filetree}, {@code execute_sql},
 * {@code escalate_to_human}).
 *
 * <p>{@code discard_artifacts} and {@code escalate_to_human} are generic enough to
 * ship a default implementation in {@link GraphExecutor}. {@code execute_sql}
 * inherently needs a real database connection that only the caller wiring up a
 * specific service (e.g. the shortener's migration in the brownfield scenario)
 * has — {@link GraphExecutor} has no default handler for it, so an unregistered
 * {@code execute_sql} rollback fails closed (per {@code CLAUDE.md} §5.3: "if
 * rollback itself fails, stop").
 */
@FunctionalInterface
public interface RollbackHandler {
    boolean rollback(Run run, NodeSpec node, RollbackAction action) throws Exception;
}
