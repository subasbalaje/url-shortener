package com.sdlc.orchestrator.exec;

import com.sdlc.orchestrator.agents.StageAgent;
import com.sdlc.orchestrator.gates.GateCheckers;
import com.sdlc.orchestrator.gates.GateManager;
import com.sdlc.orchestrator.graph.GraphLoader;
import com.sdlc.orchestrator.lineage.DecisionLogger;
import com.sdlc.orchestrator.model.GraphSpec;
import com.sdlc.orchestrator.model.NodeState;
import com.sdlc.orchestrator.model.Run;
import com.sdlc.orchestrator.model.RunState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises {@link GraphExecutor} against a compact custom fixture graph (not the
 * real docs/orchestration-graph.yaml, whose exit gates reference the real
 * `src/` tree by design — running that here would either pollute the real source
 * tree or require the full shortener to already exist). The real graph is
 * exercised end-to-end in step 13's scenario runs instead.
 */
class GraphExecutorTest {

    /** a -> b -> {c, d} -> e (join, exit-side human-gated). b has a small retry
     *  budget and a discard_artifacts rollback. */
    private static final String FIXTURE_YAML = """
            schema_version: "1.0"
            graph:
              id: "fixture"
              name: "fixture"
              artifacts:
                a_out: { produced_by: a, type: json }
                b_out: { produced_by: b, type: json }
                c_out: { produced_by: c, type: json }
                d_out: { produced_by: d, type: json }
                e_out: { produced_by: e, type: json }
            nodes:
              - id: a
                name: "A"
                agent_role: agent_a
                produces: [a_out]
              - id: b
                name: "B"
                agent_role: agent_b
                depends_on: [a]
                produces: [b_out]
                exit_gate:
                  conditions:
                    - type: field_non_empty
                      field: b_out.ok
                      error: "b_out.ok missing"
                retry_policy:
                  max_attempts: 3
                  initial_interval_seconds: 0
                  backoff_coefficient: 1.0
                  max_interval_seconds: 0
                  non_retryable_errors: []
                rollback_action:
                  type: discard_artifacts
                  artifacts: [b_out]
              - id: c
                name: "C"
                agent_role: agent_c
                depends_on: [b]
                can_run_parallel: [d]
                produces: [c_out]
              - id: d
                name: "D"
                agent_role: agent_d
                depends_on: [b]
                can_run_parallel: [c]
                produces: [d_out]
              - id: e
                name: "E"
                agent_role: agent_e
                depends_on: [c, d]
                produces: [e_out]
                requires_human_approval: true
                human_approval_reason: "test gate"
                approval_payload:
                  include: [e_out.summary]
            """;

    private GraphSpec fixtureGraph() {
        return GraphLoader.loadFromString(FIXTURE_YAML);
    }

    private GraphExecutor newExecutor(GraphSpec graph, Map<String, StageAgent> agents, Path runDir) {
        try {
            return new GraphExecutor(graph, agents, runDir, new DecisionLogger(runDir.resolve("lineage.jsonl")),
                    GateCheckers.newFullyRegisteredRegistry(), new GateManager(), Map.of());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private StageAgent alwaysProduces(String artifactKey, Object content) {
        return (run, node) -> Map.of(artifactKey, content);
    }

    // ---- happy path -----------------------------------------------------------

    @Test
    void happyPathCompletesEveryNodeInOrder(@TempDir Path dir) throws Exception {
        Map<String, StageAgent> agents = Map.of(
                "agent_a", alwaysProduces("a_out", Map.of("v", 1)),
                "agent_b", alwaysProduces("b_out", Map.of("ok", true)),
                "agent_c", alwaysProduces("c_out", Map.of("v", 1)),
                "agent_d", alwaysProduces("d_out", Map.of("v", 1)),
                "agent_e", alwaysProduces("e_out", Map.of("summary", "done")));

        GraphExecutor executor = newExecutor(fixtureGraph(), agents, dir);
        Run run = executor.runGraph("do it", "greenfield");

        // e requires approval -- run pauses there, not COMPLETED yet.
        assertThat(run.state()).isEqualTo(RunState.AWAITING_APPROVAL);
        assertThat(run.nodes().get("a").state()).isEqualTo(NodeState.COMPLETED);
        assertThat(run.nodes().get("b").state()).isEqualTo(NodeState.COMPLETED);
        assertThat(run.nodes().get("c").state()).isEqualTo(NodeState.COMPLETED);
        assertThat(run.nodes().get("d").state()).isEqualTo(NodeState.COMPLETED);
        assertThat(run.nodes().get("e").state()).isEqualTo(NodeState.BLOCKED_ON_GATE);
        assertThat(Files.exists(dir.resolve("pending_approval.json"))).isTrue();
    }

    // ---- retry then succeed -----------------------------------------------------

    @Test
    void retriesOnExitGateFailureThenSucceeds(@TempDir Path dir) throws Exception {
        AtomicInteger callCount = new AtomicInteger();
        Map<String, StageAgent> agents = Map.of(
                "agent_a", alwaysProduces("a_out", Map.of("v", 1)),
                "agent_b", (run, node) -> callCount.incrementAndGet() < 2
                        ? Map.of("b_out", Map.of()) // missing "ok" -> exit gate fails
                        : Map.of("b_out", Map.of("ok", true)),
                "agent_c", alwaysProduces("c_out", Map.of()),
                "agent_d", alwaysProduces("d_out", Map.of()),
                "agent_e", alwaysProduces("e_out", Map.of("summary", "done")));

        GraphExecutor executor = newExecutor(fixtureGraph(), agents, dir);
        Run run = executor.runGraph("do it", "greenfield");

        assertThat(callCount.get()).isEqualTo(2);
        assertThat(run.nodes().get("b").state()).isEqualTo(NodeState.COMPLETED);
        assertThat(run.nodes().get("b").attempts()).hasSize(2);
    }

    // ---- retry exhaustion -> rollback -> safe-stop -------------------------------

    @Test
    void exhaustingRetriesRollsBackAndSafeStops(@TempDir Path dir) throws Exception {
        Map<String, StageAgent> agents = Map.of(
                "agent_a", alwaysProduces("a_out", Map.of("v", 1)),
                "agent_b", alwaysProduces("b_out", Map.of()), // always missing "ok"
                "agent_c", alwaysProduces("c_out", Map.of()),
                "agent_d", alwaysProduces("d_out", Map.of()),
                "agent_e", alwaysProduces("e_out", Map.of("summary", "done")));

        GraphExecutor executor = newExecutor(fixtureGraph(), agents, dir);
        Run run = executor.runGraph("do it", "greenfield");

        assertThat(run.state()).isEqualTo(RunState.SAFE_STOPPED);
        assertThat(run.nodes().get("b").state()).isEqualTo(NodeState.ROLLED_BACK);
        assertThat(run.nodes().get("b").attempts()).hasSize(3); // max_attempts: 3
        assertThat(run.artifacts()).doesNotContainKey("b_out"); // discard_artifacts ran

        // Terminal dependency failure -> UPSTREAM_FAILED, NOT FAILED.
        assertThat(run.nodes().get("c").state()).isEqualTo(NodeState.UPSTREAM_FAILED);
        assertThat(run.nodes().get("d").state()).isEqualTo(NodeState.UPSTREAM_FAILED);

        // The join must refuse to fire on partial (here: total) upstream failure --
        // it inherits UPSTREAM_FAILED rather than ever running, exactly like
        // docs/example-run.md §7's release_readiness.
        assertThat(run.nodes().get("e").state()).isEqualTo(NodeState.UPSTREAM_FAILED);
        assertThat(Files.exists(dir.resolve("safe_stop_report.md"))).isTrue();
    }

    // ---- parallel dispatch ---------------------------------------------------------

    @Test
    void independentNodesInAFrontierRunConcurrently(@TempDir Path dir) throws Exception {
        CountDownLatch bothStarted = new CountDownLatch(2);
        Map<String, StageAgent> agents = Map.of(
                "agent_a", alwaysProduces("a_out", Map.of()),
                "agent_b", alwaysProduces("b_out", Map.of("ok", true)),
                "agent_c", (run, node) -> {
                    bothStarted.countDown();
                    if (!bothStarted.await(2, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("d did not start concurrently with c");
                    }
                    return Map.of("c_out", Map.of());
                },
                "agent_d", (run, node) -> {
                    bothStarted.countDown();
                    if (!bothStarted.await(2, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("c did not start concurrently with d");
                    }
                    return Map.of("d_out", Map.of());
                },
                "agent_e", alwaysProduces("e_out", Map.of("summary", "done")));

        GraphExecutor executor = newExecutor(fixtureGraph(), agents, dir);
        Run run = executor.runGraph("do it", "greenfield");

        assertThat(run.nodes().get("c").state()).isEqualTo(NodeState.COMPLETED);
        assertThat(run.nodes().get("d").state()).isEqualTo(NodeState.COMPLETED);
    }

    // ---- gate pause / resume, discarding the executor entirely -----------------------

    @Test
    void gateSurvivesDiscardingTheExecutorEntirely(@TempDir Path dir) throws Exception {
        Map<String, StageAgent> agents = Map.of(
                "agent_a", alwaysProduces("a_out", Map.of()),
                "agent_b", alwaysProduces("b_out", Map.of("ok", true)),
                "agent_c", alwaysProduces("c_out", Map.of()),
                "agent_d", alwaysProduces("d_out", Map.of()),
                "agent_e", alwaysProduces("e_out", Map.of("summary", "release it")));

        GraphExecutor firstExecutor = newExecutor(fixtureGraph(), agents, dir);
        Run pausedRun = firstExecutor.runGraph("do it", "greenfield");

        assertThat(pausedRun.state()).isEqualTo(RunState.AWAITING_APPROVAL);
        assertThat(Files.exists(dir.resolve("pending_approval.json"))).isTrue();
        assertThat(Files.exists(dir.resolve("state.json"))).isTrue();

        // THE POINT: discard the executor (and its Run reference) entirely. A
        // fresh object, constructed from nothing but what's on disk, must resume
        // correctly -- otherwise this test would pass for a Thread.sleep().
        firstExecutor = null;

        Files.writeString(dir.resolve("approval.json"), """
                {"approver":"mukesh","decision":"approved","comment":"looks good"}
                """);

        GraphExecutor freshExecutor = newExecutor(fixtureGraph(), agents, dir);
        Run resumed = freshExecutor.resume(dir.resolve("state.json"), dir.resolve("approval.json"));

        assertThat(resumed.state()).isEqualTo(RunState.COMPLETED);
        assertThat(resumed.nodes().get("e").state()).isEqualTo(NodeState.COMPLETED);
        assertThat(resumed.approvals()).hasSize(1);
        assertThat(resumed.approvals().get(0).comment()).isEqualTo("looks good");
        assertThat(Files.exists(dir.resolve("pending_approval.json"))).isFalse();
    }

    @Test
    void resumeRequiresANonEmptyComment(@TempDir Path dir) throws Exception {
        Map<String, StageAgent> agents = Map.of(
                "agent_a", alwaysProduces("a_out", Map.of()),
                "agent_b", alwaysProduces("b_out", Map.of("ok", true)),
                "agent_c", alwaysProduces("c_out", Map.of()),
                "agent_d", alwaysProduces("d_out", Map.of()),
                "agent_e", alwaysProduces("e_out", Map.of("summary", "x")));

        GraphExecutor executor = newExecutor(fixtureGraph(), agents, dir);
        executor.runGraph("do it", "greenfield");

        Files.writeString(dir.resolve("approval.json"), """
                {"approver":"mukesh","decision":"approved","comment":""}
                """);

        org.junit.jupiter.api.function.Executable resumeCall = () ->
                newExecutor(fixtureGraph(), agents, dir).resume(dir.resolve("state.json"), dir.resolve("approval.json"));

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, resumeCall);
    }

    // ---- stale approval: mutated artifact between pause and resume --------------------

    @Test
    void mutatingAnApprovedArtifactBetweenPauseAndResumeVoidsTheApproval(@TempDir Path dir) throws Exception {
        Map<String, StageAgent> agents = Map.of(
                "agent_a", alwaysProduces("a_out", Map.of()),
                "agent_b", alwaysProduces("b_out", Map.of("ok", true)),
                "agent_c", alwaysProduces("c_out", Map.of()),
                "agent_d", alwaysProduces("d_out", Map.of()),
                "agent_e", alwaysProduces("e_out", Map.of("summary", "original")));

        GraphExecutor executor = newExecutor(fixtureGraph(), agents, dir);
        executor.runGraph("do it", "greenfield");

        // Simulate a re-plan/edit mutating e_out's content directly in state.json
        // between the pause and the human's response.
        Run tampered = Run.load(dir.resolve("state.json"));
        tampered.artifacts().put("e_out",
                new com.sdlc.orchestrator.model.Artifact("e_out", "e", Map.of("summary", "CHANGED"), "", "", 2));
        tampered.save(dir.resolve("state.json"));

        Files.writeString(dir.resolve("approval.json"), """
                {"approver":"mukesh","decision":"approved","comment":"looks good"}
                """);

        GraphExecutor freshExecutor = newExecutor(fixtureGraph(), agents, dir);
        Run resumed = freshExecutor.resume(dir.resolve("state.json"), dir.resolve("approval.json"));

        // Approval must be rejected as stale and the node re-gated, not silently
        // inherited by the changed content.
        assertThat(resumed.state()).isEqualTo(RunState.AWAITING_APPROVAL);
        assertThat(resumed.nodes().get("e").state()).isEqualTo(NodeState.BLOCKED_ON_GATE);
        assertThat(resumed.approvals()).isEmpty();
        assertThat(resumed.approvalsInvalidatedByReplan()).isEqualTo(1);
    }

    // ---- rejected approval -------------------------------------------------------------

    @Test
    void rejectedApprovalSafeStopsRatherThanErroring(@TempDir Path dir) throws Exception {
        Map<String, StageAgent> agents = Map.of(
                "agent_a", alwaysProduces("a_out", Map.of()),
                "agent_b", alwaysProduces("b_out", Map.of("ok", true)),
                "agent_c", alwaysProduces("c_out", Map.of()),
                "agent_d", alwaysProduces("d_out", Map.of()),
                "agent_e", alwaysProduces("e_out", Map.of("summary", "x")));

        GraphExecutor executor = newExecutor(fixtureGraph(), agents, dir);
        executor.runGraph("do it", "greenfield");

        Files.writeString(dir.resolve("approval.json"), """
                {"approver":"mukesh","decision":"rejected","comment":"not ready"}
                """);

        Run resumed = newExecutor(fixtureGraph(), agents, dir).resume(dir.resolve("state.json"), dir.resolve("approval.json"));

        assertThat(resumed.state()).isEqualTo(RunState.SAFE_STOPPED);
        assertThat(resumed.approvals()).hasSize(1);
        assertThat(resumed.safeStopReason()).contains("rejected");
    }

    // ---- on_persistent_failure: reroute upstream instead of burning retries -------------

    private static final String FEEDBACK_YAML = """
            schema_version: "1.0"
            graph:
              id: "feedback-fixture"
              name: "feedback fixture"
              artifacts:
                impl_out: { produced_by: implementation, type: json }
                test_out: { produced_by: testing, type: json }
            nodes:
              - id: implementation
                name: "Implementation"
                agent_role: impl_agent
                produces: [impl_out]
              - id: testing
                name: "Testing"
                agent_role: test_agent
                depends_on: [implementation]
                produces: [test_out]
                exit_gate:
                  conditions:
                    - type: field_non_empty
                      field: test_out.pass
                      error: "tests did not pass"
                on_persistent_failure:
                  action: mark_upstream_stale
                  target: implementation
                  max_feedback_loops: 2
                rollback_action:
                  type: discard_artifacts
                  artifacts: [test_out]
            """;

    @Test
    void persistentTestingFailureMarksImplementationStaleInsteadOfRetrying(@TempDir Path dir) throws Exception {
        AtomicInteger implRuns = new AtomicInteger();
        Map<String, StageAgent> agents = Map.of(
                "impl_agent", (run, node) -> { implRuns.incrementAndGet(); return Map.of("impl_out", Map.of()); },
                "test_agent", (run, node) -> Map.of("test_out", Map.of())); // never has "pass" -> always fails

        GraphExecutor executor = newExecutor(GraphLoader.loadFromString(FEEDBACK_YAML), agents, dir);
        Run run = executor.runGraph("do it", "greenfield");

        // Bounded by max_feedback_loops: 2 -- after that many reroutes, the normal
        // ladder takes over and the run safe-stops rather than looping forever.
        assertThat(run.state()).isEqualTo(RunState.SAFE_STOPPED);
        assertThat(implRuns.get()).isEqualTo(3); // initial run + 2 feedback-triggered reruns
        assertThat(run.nodes().get("testing").feedbackLoops()).isEqualTo(2);
    }

    // ---- markStaleFrom: re-planning ---------------------------------------------------

    private static final String REPLAN_YAML = """
            schema_version: "1.0"
            graph:
              id: "replan-fixture"
              name: "replan fixture"
              artifacts:
                requirement_spec: { produced_by: requirements, type: json }
                design_doc: { produced_by: design, type: json }
                migration_plan: { produced_by: design, type: json }
            nodes:
              - id: requirements
                name: "Requirements"
                agent_role: req_agent
                produces: [requirement_spec]
              - id: design
                name: "Design"
                agent_role: design_agent
                depends_on: [requirements]
                produces: [design_doc, migration_plan]
                re_plan_triggers:
                  - artifact: requirement_spec
                    on_change: mark_stale
              - id: apply_migration
                name: "Apply Migration"
                agent_role: migration_agent
                depends_on: [design]
                re_plan_triggers:
                  - artifact: migration_plan
                    on_change: mark_stale
            """;

    @Test
    void markStaleFromOnlyStalesNodesDeclaringAMatchingTrigger(@TempDir Path dir) throws Exception {
        Map<String, StageAgent> agents = Map.of(
                "req_agent", alwaysProduces("requirement_spec", Map.of("v", 1)),
                "design_agent", (run, node) -> Map.of("design_doc", Map.of(), "migration_plan", Map.of()),
                "migration_agent", alwaysProduces("migration_applied", Map.of()));

        GraphExecutor executor = newExecutor(GraphLoader.loadFromString(REPLAN_YAML), agents, dir);
        Run run = executor.runGraph("do it", "greenfield");
        assertThat(run.state()).isEqualTo(RunState.COMPLETED);

        String snapshotBeforeReplan = Files.readString(dir.resolve("lineage.jsonl"));

        var staled = executor.markStaleFrom("requirement_spec", "old-hash", "new-hash",
                "Stakeholder added a requirement");

        // design declares a trigger on requirement_spec -> staled.
        assertThat(staled).contains("design");
        // apply_migration declares a trigger on migration_plan ONLY, not on
        // requirement_spec directly, and is NOT staled by this change even though
        // it is a transitive descendant of requirements -- getting this wrong
        // means re-running an already-approved destructive migration.
        assertThat(staled).doesNotContain("apply_migration");
        assertThat(run.nodes().get("apply_migration").state()).isEqualTo(NodeState.COMPLETED);

        // Lineage for the untouched node is preserved byte-identically (the
        // snapshot taken before the replan remains a strict prefix).
        assertThat(DecisionLogger.verifyAppendOnly(dir.resolve("lineage.jsonl"), snapshotBeforeReplan)).isTrue();
    }

    // ---- append-only lineage --------------------------------------------------------------

    @Test
    void lineageIsAppendOnlyAcrossAFullRun(@TempDir Path dir) throws Exception {
        Map<String, StageAgent> agents = Map.of(
                "agent_a", alwaysProduces("a_out", Map.of()),
                "agent_b", alwaysProduces("b_out", Map.of("ok", true)),
                "agent_c", alwaysProduces("c_out", Map.of()),
                "agent_d", alwaysProduces("d_out", Map.of()),
                "agent_e", alwaysProduces("e_out", Map.of("summary", "x")));

        GraphExecutor executor = newExecutor(fixtureGraph(), agents, dir);
        executor.runGraph("do it", "greenfield");

        String snapshot = Files.readString(dir.resolve("lineage.jsonl"));

        Files.writeString(dir.resolve("approval.json"), """
                {"approver":"mukesh","decision":"approved","comment":"ok"}
                """);
        newExecutor(fixtureGraph(), agents, dir).resume(dir.resolve("state.json"), dir.resolve("approval.json"));

        assertThat(DecisionLogger.verifyAppendOnly(dir.resolve("lineage.jsonl"), snapshot)).isTrue();
        List<Map<String, Object>> entries = DecisionLogger.readLineage(dir.resolve("lineage.jsonl"));
        assertThat(entries).isNotEmpty();
        assertThat(entries.get(0).get("event")).isEqualTo("RUN_STARTED");
    }
}
