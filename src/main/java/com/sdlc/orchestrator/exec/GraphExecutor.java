package com.sdlc.orchestrator.exec;

import com.sdlc.orchestrator.agents.StageAgent;
import com.sdlc.orchestrator.gates.CheckerRegistry;
import com.sdlc.orchestrator.gates.ExpressionEvaluator;
import com.sdlc.orchestrator.gates.GateEvaluator;
import com.sdlc.orchestrator.gates.GateManager;
import com.sdlc.orchestrator.gates.PolicyEngine;
import com.sdlc.orchestrator.graph.GraphAlgorithms;
import com.sdlc.orchestrator.lineage.DecisionLogger;
import com.sdlc.orchestrator.lineage.EventType;
import com.sdlc.orchestrator.lineage.LineageEntry;
import com.sdlc.orchestrator.metrics.MetricsCollector;
import com.sdlc.orchestrator.metrics.RunMetrics;
import com.sdlc.orchestrator.model.ApprovalRecord;
import com.sdlc.orchestrator.model.Artifact;
import com.sdlc.orchestrator.model.AttemptRecord;
import com.sdlc.orchestrator.model.GraphSpec;
import com.sdlc.orchestrator.model.NodeRuntime;
import com.sdlc.orchestrator.model.NodeSpec;
import com.sdlc.orchestrator.model.NodeState;
import com.sdlc.orchestrator.model.RePlanTrigger;
import com.sdlc.orchestrator.model.RollbackAction;
import com.sdlc.orchestrator.model.Run;
import com.sdlc.orchestrator.model.RunState;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Executes a {@link GraphSpec}, producing a governed, audited {@link Run}.
 *
 * <p>Owns orchestration mechanics — traversal, state, gates, retries, rollback,
 * lineage, metrics — and is the ONLY component permitted to mutate run state. It
 * has zero authority over content: it never judges whether a design is good, only
 * whether the declared exit gate passed. That separation is what lets the lineage
 * be trusted.
 *
 * <p><b>Concurrency (DEC-0013):</b> virtual threads are genuinely concurrent,
 * unlike the {@code asyncio} model this replaced. All mutation of {@code run}
 * happens under {@code stateLock}. Agents receive immutable inputs and return
 * immutable results; they never touch {@code Run}.
 */
public class GraphExecutor {

    private final GraphSpec graph;
    private final Map<String, StageAgent> agentsByRole;
    private final Path runDir;
    private final DecisionLogger logger;
    private final CheckerRegistry registry;
    private final GateManager gateManager;
    private final Map<String, RollbackHandler> rollbackHandlers;
    private final ReentrantLock stateLock = new ReentrantLock();

    private Run run;

    public GraphExecutor(GraphSpec graph, Map<String, StageAgent> agentsByRole, Path runDir,
                          DecisionLogger logger, CheckerRegistry registry, GateManager gateManager,
                          Map<String, RollbackHandler> extraRollbackHandlers) {
        this.graph = graph;
        this.agentsByRole = agentsByRole;
        this.runDir = runDir;
        this.logger = logger;
        this.registry = registry;
        this.gateManager = gateManager;

        Map<String, RollbackHandler> handlers = new LinkedHashMap<>();
        handlers.put("discard_artifacts", this::handleDiscardArtifacts);
        handlers.put("escalate_to_human", this::handleEscalateToHuman);
        handlers.put("revert_filetree", this::handleRevertFiletree);
        if (extraRollbackHandlers != null) {
            handlers.putAll(extraRollbackHandlers); // caller-supplied handlers (e.g. execute_sql) may override
        }
        this.rollbackHandlers = handlers;
    }

    public Run currentRun() {
        return run;
    }

    // ======================================================================
    // Lifecycle
    // ======================================================================

    public Run runGraph(String rawRequest, String mode) throws IOException, InterruptedException {
        stateLock.lock();
        try {
            run = new Run(generateRunId(), graph.id(), mode, rawRequest);
            run.setState(RunState.RUNNING);
            for (NodeSpec n : graph.nodes()) {
                run.nodes().put(n.id(), new NodeRuntime(n.id()));
            }
        } finally {
            stateLock.unlock();
        }
        logger.append(LineageEntry.builder(EventType.RUN_STARTED, run.runId(), now())
                .actor("orchestrator")
                .details(Map.of("mode", mode, "graph_id", graph.id(), "nodes", graph.nodes().size()))
                .build());
        return mainLoop();
    }

    /**
     * Resume a run that stopped at a human gate. This is the other half of
     * persist-and-exit (DEC-0007): the process that paused is gone. Everything
     * needed to continue comes off disk.
     */
    public Run resume(Path stateJsonPath, Path approvalResponsePath) throws IOException, InterruptedException {
        run = Run.load(stateJsonPath);
        String nodeId = run.blockedNodeId();
        String gateKind = run.blockedGateKind();
        if (nodeId == null) {
            throw new IllegalStateException("Run " + run.runId() + " is not paused at a gate.");
        }
        NodeSpec node = graph.node(nodeId);

        ApprovalRecord approval;
        try {
            approval = gateManager.recordDecision(runDir.resolve("pending_approval.json"), approvalResponsePath, run.runId());
        } catch (IllegalArgumentException e) {
            throw e; // blank comment -- surfaced to the caller, no state mutated
        }

        GateManager.ValidityResult validity = gateManager.verifyApprovalStillValid(approval, run.artifacts());
        if (!validity.valid()) {
            logger.append(LineageEntry.builder(EventType.APPROVAL_INVALIDATED, run.runId(), now())
                    .nodeId(nodeId).actor("orchestrator")
                    .rationale("Approved artifact changed since the decision: " + validity.mismatches())
                    .artifactRefs(approval.artifactRef())
                    .build());
            run.incrementApprovalsInvalidatedByReplan();
            // Re-gate: leave the node BLOCKED_ON_GATE and write a fresh pending file
            // reflecting current hashes, rather than silently proceeding on an
            // approval that no longer refers to what actually exists.
            enterGate(nodeId, gateKind);
            return run;
        }

        run.approvals().add(approval);

        if (!approval.permitsContinuation()) {
            logger.append(LineageEntry.builder(EventType.APPROVAL_REJECTED, run.runId(), now())
                    .nodeId(nodeId).actor(approval.approver())
                    .decision(approval.decision().name()).rationale(approval.comment())
                    .artifactRefs(approval.artifactRef())
                    .build());
            run.setSafeStopReason("Human rejected approval for node '" + nodeId + "': " + approval.comment());
            safeStop(run.safeStopReason());
            return run;
        }

        logger.append(LineageEntry.builder(EventType.APPROVAL_GRANTED, run.runId(), now())
                .nodeId(nodeId).actor(approval.approver())
                .decision(approval.decision().name()).rationale(approval.comment())
                .artifactRefs(approval.artifactRef())
                .build());

        NodeRuntime runtime = run.nodes().get(nodeId);
        run.clearBlockedGate();
        Files.deleteIfExists(runDir.resolve("pending_approval.json"));

        if ("exit".equals(gateKind)) {
            // Exit-side: the agent already ran and the exit gate already passed;
            // approval only unblocks committing to COMPLETED.
            runtime.recordTransition(NodeState.COMPLETED, now());
            logger.append(LineageEntry.builder(EventType.NODE_COMPLETED, run.runId(), now())
                    .nodeId(nodeId).fromState(NodeState.BLOCKED_ON_GATE.name()).toState(NodeState.COMPLETED.name())
                    .build());
        } else {
            // Entry-side: approval unblocks actually running the node.
            runtime.recordTransition(NodeState.PENDING, now());
        }

        return mainLoop();
    }

    // ======================================================================
    // Main loop
    // ======================================================================

    private Run mainLoop() throws IOException, InterruptedException {
        while (true) {
            List<String> ready = computeReady();
            if (ready.isEmpty()) {
                break; // terminal, or every remaining node is upstream-failed
            }

            String entryGatedNode = null;
            for (String id : ready) {
                NodeSpec node = graph.node(id);
                if (node.requiresHumanApproval() && !isExitSideGate(node)) {
                    entryGatedNode = id;
                    break;
                }
            }
            if (entryGatedNode != null) {
                enterGate(entryGatedNode, "entry");
                return run;
            }

            dispatchFrontier(ready);

            if (run.blockedNodeId() != null) {
                persistAndExit();
                return run;
            }
            if (run.safeStopReason() != null) {
                // One more pass so any dependents of the node(s) that just failed
                // are propagated to UPSTREAM_FAILED before the run closes -- a
                // downstream node must not be left PENDING forever just because
                // the run is about to stop.
                computeReady();
                safeStop(run.safeStopReason());
                return run;
            }
        }
        finalise();
        return run;
    }

    // ======================================================================
    // Scheduling
    // ======================================================================

    /**
     * Return nodes eligible to run now — the live ready-frontier.
     *
     * <p>Join semantics ({@code all_success}) are enforced here: no partial-input
     * starts (every dependency must be terminal-success), and failure propagates
     * (a terminally-failed dependency marks this node {@code UPSTREAM_FAILED}
     * rather than leaving it pending forever). {@code SKIPPED} counts as
     * satisfied.
     */
    private List<String> computeReady() throws IOException {
        stateLock.lock();
        try {
            List<String> ready = new ArrayList<>();
            for (NodeSpec node : graph.nodes()) {
                NodeRuntime runtime = run.nodes().get(node.id());
                if (runtime.state() != NodeState.PENDING && runtime.state() != NodeState.STALE) {
                    continue;
                }

                boolean anyDepFailed = false;
                boolean allDepsSatisfied = true;
                for (String dep : node.dependsOn()) {
                    NodeState depState = run.nodes().get(dep).state();
                    if (depState.isTerminalFailure()) {
                        anyDepFailed = true;
                    } else if (!depState.isTerminalSuccess()) {
                        allDepsSatisfied = false;
                    }
                }

                if (anyDepFailed) {
                    logger.append(LineageEntry.builder(EventType.NODE_FAILED, run.runId(), now())
                            .nodeId(node.id()).fromState(runtime.state().name()).toState(NodeState.UPSTREAM_FAILED.name())
                            .details(Map.of("reason", "upstream_dependency_failed"))
                            .build());
                    runtime.restoreState(NodeState.UPSTREAM_FAILED);
                    continue;
                }
                if (!allDepsSatisfied) {
                    continue;
                }

                String expression = (String) node.condition().get("expression");
                if (expression != null && !ExpressionEvaluator.evaluate(run, expression)) {
                    logger.append(LineageEntry.builder(EventType.NODE_SKIPPED, run.runId(), now())
                            .nodeId(node.id()).toState(NodeState.SKIPPED.name())
                            .details(Map.of("reason", "condition evaluated false: " + expression))
                            .build());
                    runtime.restoreState(NodeState.SKIPPED);
                    continue;
                }

                ready.add(node.id());
            }
            return ready;
        } finally {
            stateLock.unlock();
        }
    }

    /**
     * Run every node in the frontier concurrently and await them all. This is
     * where "sequential AND parallel with synchronisation" becomes real: frontier
     * members run under {@link StructuredScope}; awaiting the whole fan-out IS the
     * synchronisation barrier before the next frontier. Failures do not cancel
     * siblings.
     */
    private void dispatchFrontier(List<String> ready) throws InterruptedException {
        Map<String, Callable<Void>> tasks = new LinkedHashMap<>();
        for (String nodeId : ready) {
            tasks.put(nodeId, () -> { runNodeToConclusion(nodeId); return null; });
        }
        List<StructuredScope.Outcome<Void>> outcomes = StructuredScope.forkAll(tasks, Duration.ofHours(1));
        for (var outcome : outcomes) {
            if (!outcome.succeeded()) {
                // runNodeToConclusion never lets a normal node failure escape as an
                // exception (the failure ladder handles it internally) -- anything
                // that DOES escape here is a bug in the executor itself, and must
                // be surfaced loudly rather than swallowed.
                throw new IllegalStateException("Node '" + outcome.label() + "' failed unexpectedly", outcome.error());
            }
        }
    }

    // ======================================================================
    // Node execution: condition -> entry gate -> RUNNING -> agent -> exit gate -> commit
    // ======================================================================

    private void runNodeToConclusion(String nodeId) throws Exception {
        NodeSpec node = graph.node(nodeId);
        NodeRuntime runtime = run.nodes().get(nodeId);

        GateEvaluator.Result entryResult = GateEvaluator.evaluate(run, node, node.entryGate(), registry);
        if (!entryResult.passed()) {
            handleFailure(node, runtime, "EntryGateFailure", entryResult.failures(), 0);
            return;
        }

        int attempt = 0;
        while (true) {
            attempt++;
            String startedAt = now();
            AttemptRecord attemptRecord = new AttemptRecord(attempt, startedAt);

            stateLock.lock();
            try {
                runtime.attempts().add(attemptRecord);
                NodeState from = runtime.state();
                runtime.recordTransition(NodeState.RUNNING, startedAt);
                logger.append(LineageEntry.builder(EventType.NODE_STARTED, run.runId(), startedAt)
                        .nodeId(nodeId).attempt(attempt).actor(node.agentRole())
                        .fromState(from.name()).toState(NodeState.RUNNING.name())
                        .build());
            } finally {
                stateLock.unlock();
            }

            String errorType = null;
            List<String> failureMessages = List.of();
            Map<String, Object> produced = null;

            try {
                produced = invokeAgentWithTimeout(node);
            } catch (Exception e) {
                errorType = e.getClass().getSimpleName();
                failureMessages = List.of(e.getMessage() == null ? String.valueOf(e) : e.getMessage());
            }

            if (errorType == null) {
                stateLock.lock();
                try {
                    for (var entry : produced.entrySet()) {
                        run.artifacts().put(entry.getKey(), new Artifact(entry.getKey(), nodeId, entry.getValue(), "", "", 1));
                    }
                } finally {
                    stateLock.unlock();
                }

                GateEvaluator.Result exitResult = GateEvaluator.evaluate(run, node, node.exitGate(), registry);
                if (exitResult.passed()) {
                    logger.append(LineageEntry.builder(EventType.GATE_EXIT_PASSED, run.runId(), now())
                            .nodeId(nodeId).attempt(attempt)
                            .details(Map.of("conditions_evaluated", node.exitGate() == null ? 0 : node.exitGate().conditions().size()))
                            .build());

                    if (node.requiresHumanApproval() && isExitSideGate(node)) {
                        stateLock.lock();
                        try {
                            runtime.recordTransition(NodeState.BLOCKED_ON_GATE, now());
                            run.setState(RunState.AWAITING_APPROVAL);
                            run.setBlockedNodeId(nodeId);
                            run.setBlockedGateKind("exit");
                        } finally {
                            stateLock.unlock();
                        }
                        writeGateArtifacts(node);
                        return;
                    }

                    double duration = secondsBetween(startedAt, now());
                    attemptRecord.complete(now(), "success", duration);
                    stateLock.lock();
                    try {
                        runtime.recordTransition(NodeState.COMPLETED, now());
                        runtime.addTimeRunningSeconds(duration);
                    } finally {
                        stateLock.unlock();
                    }
                    logger.append(LineageEntry.builder(EventType.NODE_COMPLETED, run.runId(), now())
                            .nodeId(nodeId).attempt(attempt)
                            .fromState(NodeState.RUNNING.name()).toState(NodeState.COMPLETED.name())
                            .details(Map.of("artifact_refs", artifactRefsFor(produced.keySet())))
                            .build());
                    return;
                }
                logger.append(LineageEntry.builder(EventType.GATE_EXIT_FAILED, run.runId(), now())
                        .nodeId(nodeId).attempt(attempt)
                        .details(Map.of("failures", exitResult.failures()))
                        .build());
                errorType = "GateFailure";
                failureMessages = exitResult.failures();
            }

            boolean handledUpstream = maybeHandlePersistentFailure(node, runtime, errorType);
            if (handledUpstream) {
                return;
            }
            if (handleFailure(node, runtime, errorType, failureMessages, attempt)) {
                continue; // ladder decided RETRY; loop for the next attempt
            }
            return; // ladder decided ROLLBACK or SAFE_STOP; runNodeToConclusion is done
        }
    }

    /** @return true if the caller should retry (loop again) */
    private boolean handleFailure(NodeSpec node, NodeRuntime runtime, String errorType, List<String> messages, int attempt) throws Exception {
        String failedAt = now();
        stateLock.lock();
        try {
            runtime.recordTransition(NodeState.FAILED, failedAt);
        } finally {
            stateLock.unlock();
        }
        logger.append(LineageEntry.builder(EventType.NODE_FAILED, run.runId(), failedAt)
                .nodeId(node.id()).attempt(attempt)
                .details(Map.of("error_type", errorType, "messages", messages))
                .build());

        FailureLadder.Action action = FailureLadder.decide(node.retryPolicy(), Math.max(attempt, 1), errorType, node.rollbackAction() != null);

        if (action == FailureLadder.Action.RETRY) {
            double backoff = node.retryPolicy().backoffFor(attempt + 1);
            stateLock.lock();
            try {
                runtime.recordTransition(NodeState.RETRYING, now());
            } finally {
                stateLock.unlock();
            }
            logger.append(LineageEntry.builder(EventType.RETRY_SCHEDULED, run.runId(), now())
                    .nodeId(node.id()).attempt(attempt + 1)
                    .rationale(errorType + " is retryable under policy; " + attempt + " of " + node.retryPolicy().maxAttempts() + " attempts used.")
                    .details(Map.of("backoff_seconds", backoff))
                    .build());
            if (backoff > 0) {
                Thread.sleep((long) (backoff * 1000));
            }
            stateLock.lock();
            try {
                runtime.recordTransition(NodeState.RUNNING, now());
            } finally {
                stateLock.unlock();
            }
            return true;
        }

        if (action == FailureLadder.Action.ROLLBACK) {
            boolean ok = performRollback(node);
            String at = now();
            stateLock.lock();
            try {
                if (ok) {
                    runtime.recordTransition(NodeState.ROLLED_BACK, at);
                    runtime.markRolledBack();
                }
            } finally {
                stateLock.unlock();
            }
            if (ok) {
                logger.append(LineageEntry.builder(EventType.ROLLBACK_COMPLETED, run.runId(), at).nodeId(node.id()).build());
                run.setSafeStopReason(node.id() + " exhausted retry budget (" + attempt + " attempts); "
                        + "rollback succeeded; no path forward without intervention");
            } else {
                // If rollback itself fails, stop. Do not retry it or improvise.
                run.setSafeStopReason(node.id() + " exhausted retry budget (" + attempt + " attempts); "
                        + "rollback action ALSO failed; system state model is unreliable, halting");
            }
            return false;
        }

        // SAFE_STOP: non-retryable (or exhausted) with no rollback_action declared.
        run.setSafeStopReason(node.id() + " failed with " + errorType + " and has no rollback_action; halting.");
        return false;
    }

    /**
     * {@code on_persistent_failure} (the testing node): persistent test failure
     * usually means the CODE is wrong, not that tests are flaky. Rather than
     * burning retries on a node correctly reporting a real problem, mark the
     * target node STALE and push work upstream, bounded by
     * {@code max_feedback_loops}.
     *
     * @return true if this failure was rerouted upstream (caller should stop)
     */
    private boolean maybeHandlePersistentFailure(NodeSpec node, NodeRuntime runtime, String errorType) throws Exception {
        Map<String, Object> config = node.onPersistentFailure();
        if (config.isEmpty() || !"GateFailure".equals(errorType)) {
            return false;
        }
        String targetNodeId = (String) config.get("target");
        int maxLoops = ((Number) config.getOrDefault("max_feedback_loops", 2)).intValue();

        if (runtime.feedbackLoops() >= maxLoops) {
            return false; // bounded: fall through to the normal ladder (rollback/safe-stop)
        }

        stateLock.lock();
        try {
            runtime.incrementFeedbackLoops();
            NodeRuntime target = run.nodes().get(targetNodeId);
            target.restoreState(NodeState.STALE);
            target.incrementReplanCount();
            runtime.recordTransition(NodeState.PENDING, now()); // eligible again once target redoes its work
        } finally {
            stateLock.unlock();
        }
        logger.logDecision(run.runId(), node.id(), "orchestrator",
                "Mark '" + targetNodeId + "' STALE after a persistent testing failure",
                "Persistent test failure usually means the code is wrong, not that tests are flaky; "
                        + "pushing work upstream instead of burning retries (feedback loop "
                        + runtime.feedbackLoops() + " of " + maxLoops + ").");
        return true;
    }

    private boolean performRollback(NodeSpec node) throws Exception {
        RollbackAction action = node.rollbackAction();
        RollbackHandler handler = rollbackHandlers.get(action.type());
        if (handler == null) {
            return false; // unregistered rollback type: fail closed
        }
        return handler.rollback(run, node, action);
    }

    private boolean handleDiscardArtifacts(Run run, NodeSpec node, RollbackAction action) {
        for (String key : node.produces()) {
            run.artifacts().remove(key);
        }
        return true;
    }

    private boolean handleEscalateToHuman(Run run, NodeSpec node, RollbackAction action) throws IOException {
        Path path = runDir.resolve("pending_clarification.json");
        Files.createDirectories(runDir);
        Files.writeString(path, new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                "run_id", run.runId(), "node_id", node.id(),
                "reason", "Escalated per rollback_action: " + action.description())));
        return true; // escalating IS the successful compensation for this action type
    }

    private boolean handleRevertFiletree(Run run, NodeSpec node, RollbackAction action) {
        // KNOWN SIMPLIFICATION: no real filetree snapshot/restore mechanism exists
        // yet. Treated as a successful no-op compensation; the artifacts this node
        // produced are discarded (same effect discard_artifacts would have) so a
        // failed implementation attempt does not leave partial output committed.
        for (String key : node.produces()) {
            run.artifacts().remove(key);
        }
        return true;
    }

    private Map<String, Object> invokeAgentWithTimeout(NodeSpec node) throws Exception {
        StageAgent agent = agentsByRole.get(node.agentRole());
        if (agent == null) {
            throw new IllegalStateException("No agent registered for role '" + node.agentRole() + "'.");
        }
        Map<String, Callable<Map<String, Object>>> single = Map.of(node.id(), () -> agent.execute(run, node));
        var outcomes = StructuredScope.forkAll(single, Duration.ofSeconds((long) node.timeoutSeconds()));
        var outcome = outcomes.get(0);
        if (!outcome.succeeded()) {
            if (outcome.error() instanceof Exception e) {
                throw e;
            }
            throw new RuntimeException(outcome.error());
        }
        return outcome.value();
    }

    private boolean isExitSideGate(NodeSpec node) {
        return node.approvalPayload().stream().anyMatch(path -> node.produces().contains(path.split("\\.")[0]));
    }

    // ======================================================================
    // Human gates
    // ======================================================================

    /**
     * Pause for human approval: persist, surface, and EXIT. The process ends here
     * — nothing spins or sleeps. A gate you can inspect on disk, kill the machine,
     * and still resume is provably a gate; one that blocks in memory is
     * indistinguishable from {@code Thread.sleep()}.
     */
    private void enterGate(String nodeId, String kind) throws IOException {
        NodeSpec node = graph.node(nodeId);
        NodeRuntime runtime = run.nodes().get(nodeId);

        stateLock.lock();
        try {
            runtime.recordTransition(NodeState.BLOCKED_ON_GATE, now());
            run.setState(RunState.AWAITING_APPROVAL);
            run.setBlockedNodeId(nodeId);
            run.setBlockedGateKind(kind);
        } finally {
            stateLock.unlock();
        }
        writeGateArtifacts(node);
        run.save(runDir.resolve("state.json"));
    }

    /** Writes {@code pending_approval.json} and the {@code APPROVAL_REQUESTED}
     *  lineage entry. Shared by both entry-side gating ({@link #enterGate}, called
     *  from the main loop before a node ever runs) and exit-side gating (from
     *  inside {@link #runNodeToConclusion}, after the node's own exit gate has
     *  already passed) — the two differ only in *when* they fire, not in what
     *  gets written. */
    private void writeGateArtifacts(NodeSpec node) throws IOException {
        gateManager.writePending(run, node, runDir.resolve("pending_approval.json"));
        logger.append(LineageEntry.builder(EventType.APPROVAL_REQUESTED, run.runId(), now())
                .nodeId(node.id()).actor("orchestrator").rationale(node.humanApprovalReason())
                .build());
    }

    private void persistAndExit() throws IOException {
        run.setState(RunState.AWAITING_APPROVAL);
        run.save(runDir.resolve("state.json"));
    }

    // ======================================================================
    // Re-planning
    // ======================================================================

    /**
     * Propagate staleness after an upstream artifact changes (Dagster's staleness
     * model, not a restart problem):
     * <ol>
     *   <li>Find the producing node for {@code changedArtifact}.</li>
     *   <li>Take its transitive descendants.</li>
     *   <li>Intersect with nodes declaring a matching {@code re_plan_triggers} entry.</li>
     *   <li>Mark that set STALE.</li>
     *   <li>Invalidate approvals whose {@code artifactRef} covers the changed artifact.</li>
     *   <li>Preserve lineage for every untouched node (do nothing to it).</li>
     * </ol>
     */
    public Set<String> markStaleFrom(String changedArtifact, String oldHash, String newHash, String rationale) throws IOException {
        if (run.replanCycles() >= 3) {
            run.setSafeStopReason("max_replan_cycles (3) exceeded");
            safeStop(run.safeStopReason());
            return Set.of();
        }

        String producer = graph.producerOf(changedArtifact);
        Set<String> descendants = producer == null ? Set.of() : graph.transitiveDependentsOf(producer);

        // Deliberately a SINGLE pass: transitive descendants of the changed
        // artifact's producer, intersected with nodes whose OWN declared trigger
        // names THIS artifact. No speculative multi-hop cascade through nodes'
        // other produced artifacts -- apply_migration is a transitive descendant
        // of design and design also produces migration_plan, but apply_migration
        // must NOT be staled by a requirement_spec change (it triggers on
        // migration_plan only, and design's migration_plan output has not been
        // shown to have actually changed just because design re-runs). The
        // fuller cascade the worked trace narrates (design -> implementation ->
        // documentation/testing -> release_readiness) happens incrementally in
        // practice: once a staled node actually re-runs and its own output hash
        // is found to differ, the caller invokes markStaleFrom again for THAT
        // artifact -- propagation is evidence-driven, not speculative.
        Set<String> staled = new HashSet<>();
        for (String nodeId : descendants) {
            NodeSpec node = graph.node(nodeId);
            boolean triggers = node.rePlanTriggers().stream().anyMatch(t -> t.artifact().equals(changedArtifact));
            if (triggers) {
                staled.add(nodeId);
            }
        }

        Set<String> preserved = new HashSet<>(graph.nodeIds());
        preserved.removeAll(staled);

        stateLock.lock();
        try {
            for (String nodeId : staled) {
                run.nodes().get(nodeId).restoreState(NodeState.STALE);
                run.nodes().get(nodeId).incrementReplanCount();
            }
            run.incrementReplanCycles();

            List<ApprovalRecord> toInvalidate = run.approvals().stream()
                    .filter(a -> a.artifactRef().containsKey(changedArtifact) || staled.contains(a.nodeId()))
                    .toList();
            run.approvals().removeAll(toInvalidate);
            for (int i = 0; i < toInvalidate.size(); i++) {
                run.incrementApprovalsInvalidatedByReplan();
            }
        } finally {
            stateLock.unlock();
        }

        logger.logReplan(run.runId(), changedArtifact, oldHash, newHash,
                staled.stream().sorted().toList(), preserved.stream().sorted().toList(), rationale, run.replanCycles());

        return staled;
    }

    // ======================================================================
    // Finalisation
    // ======================================================================

    private void finalise() throws IOException {
        run.setEndedAt(now());
        run.setState(RunState.COMPLETED);
        writeMetricsAndClose(EventType.RUN_COMPLETED);
    }

    /**
     * Halt deliberately, preserving everything needed for diagnosis. Deliberately
     * NOT "continue without it": proceeding past an unrecoverable node would
     * produce a readiness verdict based on work that never happened.
     */
    private void safeStop(String reason) throws IOException {
        run.setEndedAt(now());
        run.setState(RunState.SAFE_STOPPED);
        run.setSafeStopReason(reason);
        writeMetricsAndClose(EventType.RUN_SAFE_STOPPED);
        Files.writeString(runDir.resolve("safe_stop_report.md"),
                "# Safe-stop report\n\nRun: " + run.runId() + "\nReason: " + reason + "\n");
    }

    private void writeMetricsAndClose(EventType terminalEvent) throws IOException {
        GraphAlgorithms.CriticalPath criticalPath;
        try {
            criticalPath = GraphAlgorithms.criticalPath(graph, Map.of());
        } catch (RuntimeException e) {
            criticalPath = null;
        }
        RunMetrics metrics = MetricsCollector.compute(run, graph.nodes().size(),
                criticalPath == null ? null : criticalPath.totalDurationSeconds());

        Files.createDirectories(runDir);
        Files.writeString(runDir.resolve("metrics.json"),
                new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(metrics));

        logger.append(LineageEntry.builder(terminalEvent, run.runId(), now())
                .actor("orchestrator")
                .details(Map.of("success_rate", metrics.successRate()))
                .build());
        run.save(runDir.resolve("state.json"));
    }

    // ======================================================================
    // Small helpers
    // ======================================================================

    private Map<String, String> artifactRefsFor(Set<String> keys) {
        Map<String, String> refs = new LinkedHashMap<>();
        for (String key : keys) {
            Artifact a = run.artifacts().get(key);
            if (a != null) {
                refs.put(key, a.contentHash());
            }
        }
        return refs;
    }

    private static final AtomicInteger RUN_SEQUENCE = new AtomicInteger();

    private static String generateRunId() {
        return "r-" + Instant.now().toEpochMilli() + "-" + RUN_SEQUENCE.incrementAndGet();
    }

    private static String now() {
        return Instant.now().toString();
    }

    private static double secondsBetween(String startIso, String endIso) {
        return Duration.between(Instant.parse(startIso), Instant.parse(endIso)).toMillis() / 1000.0;
    }
}
