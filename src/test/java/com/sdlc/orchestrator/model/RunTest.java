package com.sdlc.orchestrator.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RunTest {

    @Test
    void newRunStartsInitialisedWithATimestamp() {
        Run run = new Run("r-1", "sdlc-core", "greenfield", "Build a shortener");

        assertThat(run.state()).isEqualTo(RunState.INITIALISED);
        assertThat(run.startedAt()).isNotBlank();
        assertThat(run.endedAt()).isNull();
        assertThat(run.mode()).isEqualTo("greenfield");
        assertThat(run.rawRequest()).isEqualTo("Build a shortener");
    }

    @Test
    void nodesAndArtifactsStartEmpty() {
        Run run = new Run("r-1", "sdlc-core", "greenfield", "req");
        assertThat(run.nodes()).isEmpty();
        assertThat(run.artifacts()).isEmpty();
        assertThat(run.approvals()).isEmpty();
        assertThat(run.replanCycles()).isEqualTo(0);
        assertThat(run.safeStopReason()).isNull();
    }

    @Test
    void nodeRuntimeCanBeRegisteredAndRetrieved() {
        Run run = new Run("r-1", "sdlc-core", "greenfield", "req");
        NodeRuntime requirements = new NodeRuntime("requirements");

        run.nodes().put("requirements", requirements);

        assertThat(run.nodes()).containsEntry("requirements", requirements);
    }

    @Test
    void artifactsCanBeRecorded() {
        Run run = new Run("r-1", "sdlc-core", "greenfield", "req");
        Artifact spec = new Artifact("requirement_spec", "requirements", java.util.Map.of("k", "v"), "", "", 1);

        run.artifacts().put("requirement_spec", spec);

        assertThat(run.artifacts().get("requirement_spec").contentHash()).isEqualTo(spec.contentHash());
    }

    @Test
    void stateCanTransition() {
        Run run = new Run("r-1", "sdlc-core", "greenfield", "req");
        run.setState(RunState.RUNNING);
        assertThat(run.state()).isEqualTo(RunState.RUNNING);
    }

    @Test
    void saveThenLoadRoundTripsEveryField(@TempDir Path dir) throws IOException {
        Run run = new Run("r-1", "sdlc-core", "brownfield", "Add click analytics");
        run.setState(RunState.AWAITING_APPROVAL);
        run.setEndedAt("2026-09-13T08:00:00Z");
        run.setSafeStopReason(null);
        run.incrementReplanCycles();
        run.incrementApprovalsInvalidatedByReplan();

        NodeRuntime implementation = new NodeRuntime("implementation");
        implementation.recordTransition(NodeState.RUNNING, "t0");
        implementation.recordTransition(NodeState.FAILED, "t1");
        implementation.recordTransition(NodeState.RETRYING, "t1");
        implementation.recordTransition(NodeState.COMPLETED, "t2");
        implementation.addTimeRunningSeconds(12.5);
        implementation.attempts().add(new AttemptRecord(1, "t0"));
        run.nodes().put("implementation", implementation);

        run.artifacts().put("source_code", new Artifact("source_code", "implementation",
                Map.of("files", List.of("Link.java")), "", "", 1));
        run.approvals().add(new ApprovalRecord("apply_migration", "mukesh", "t3",
                Decision.APPROVED_WITH_CONDITIONS, "reviewed the SQL", Map.of("migration_applied", "abcd1234"), "r-1"));

        Path statePath = dir.resolve("state.json");
        run.save(statePath);
        Run loaded = Run.load(statePath);

        assertThat(loaded.runId()).isEqualTo("r-1");
        assertThat(loaded.mode()).isEqualTo("brownfield");
        assertThat(loaded.state()).isEqualTo(RunState.AWAITING_APPROVAL);
        assertThat(loaded.endedAt()).isEqualTo("2026-09-13T08:00:00Z");
        assertThat(loaded.replanCycles()).isEqualTo(1);
        assertThat(loaded.approvalsInvalidatedByReplan()).isEqualTo(1);

        NodeRuntime loadedImpl = loaded.nodes().get("implementation");
        assertThat(loadedImpl.state()).isEqualTo(NodeState.COMPLETED);
        assertThat(loadedImpl.firstFailedAt()).isEqualTo("t1");
        assertThat(loadedImpl.recoveredAt()).isEqualTo("t2");
        assertThat(loadedImpl.retryCount()).isEqualTo(1);
        assertThat(loadedImpl.timeRunningSeconds()).isEqualTo(12.5);
        assertThat(loadedImpl.attempts()).hasSize(1);

        Artifact loadedArtifact = loaded.artifacts().get("source_code");
        assertThat(loadedArtifact.contentHash()).isEqualTo(run.artifacts().get("source_code").contentHash());

        ApprovalRecord loadedApproval = loaded.approvals().get(0);
        assertThat(loadedApproval.nodeId()).isEqualTo("apply_migration");
        assertThat(loadedApproval.decision()).isEqualTo(Decision.APPROVED_WITH_CONDITIONS);
        assertThat(loadedApproval.artifactRef()).containsEntry("migration_applied", "abcd1234");
    }

    @Test
    void saveLeavesNoTempFileBehind(@TempDir Path dir) throws IOException {
        Run run = new Run("r-1", "g", "greenfield", "req");
        Path statePath = dir.resolve("state.json");
        run.save(statePath);

        assertThat(Files.exists(statePath)).isTrue();
        assertThat(Files.exists(dir.resolve("state.json.tmp"))).isFalse();
    }

    @Test
    void saveOverwritesAPreviousStateFileCompletely() throws IOException {
        Path dir = Files.createTempDirectory("run-save-test");
        try {
            Run first = new Run("r-1", "g", "greenfield", "a very long raw request indeed");
            Path statePath = dir.resolve("state.json");
            first.save(statePath);

            Run second = new Run("r-1", "g", "greenfield", "x");
            second.save(statePath);

            Run loaded = Run.load(statePath);
            assertThat(loaded.rawRequest()).isEqualTo("x"); // not a mix of old/new content
        } finally {
            Files.walk(dir).sorted((a, b) -> b.compareTo(a)).forEach(p -> p.toFile().delete());
        }
    }
}
