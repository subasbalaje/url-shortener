package com.sdlc.orchestrator.lineage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DecisionLoggerTest {

    @Test
    void appendWritesOneJsonLinePerEntry(@TempDir Path dir) throws IOException {
        Path lineagePath = dir.resolve("lineage.jsonl");
        DecisionLogger logger = new DecisionLogger(lineagePath);

        logger.append(LineageEntry.builder(EventType.RUN_STARTED, "r-1", "t1").actor("orchestrator").build());
        logger.append(LineageEntry.builder(EventType.NODE_STARTED, "r-1", "t2").nodeId("requirements").attempt(1).build());

        List<String> lines = Files.readAllLines(lineagePath);
        assertThat(lines).hasSize(2);
        assertThat(lines.get(0)).contains("\"event\":\"RUN_STARTED\"").contains("\"run_id\":\"r-1\"");
        assertThat(lines.get(1)).contains("\"node_id\":\"requirements\"").contains("\"attempt\":1");
    }

    @Test
    void appendOmitsNullFieldsRatherThanWritingNullLiterals(@TempDir Path dir) throws IOException {
        Path lineagePath = dir.resolve("lineage.jsonl");
        DecisionLogger logger = new DecisionLogger(lineagePath);
        logger.append(LineageEntry.builder(EventType.RUN_STARTED, "r-1", "t1").build());

        String line = Files.readString(lineagePath);
        assertThat(line).doesNotContain("node_id").doesNotContain("null");
    }

    @Test
    void appendIsSingleWriterUnderConcurrentVirtualThreads(@TempDir Path dir) throws Exception {
        // DEC-0013: virtual threads are genuinely concurrent, unlike the asyncio
        // model this replaced, so single-writer must now be enforced with a lock
        // rather than assumed from single-threadedness. 200 concurrent appends must
        // produce exactly 200 well-formed, non-interleaved lines.
        Path lineagePath = dir.resolve("lineage.jsonl");
        DecisionLogger logger = new DecisionLogger(lineagePath);
        int count = 200;

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                int idx = i;
                futures.add(executor.submit(() -> {
                    try {
                        logger.append(LineageEntry.builder(EventType.NODE_STARTED, "r-1", "t" + idx)
                                .nodeId("node-" + idx).build());
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }));
            }
            for (Future<?> f : futures) {
                f.get();
            }
        }

        List<String> lines = Files.readAllLines(lineagePath);
        assertThat(lines).hasSize(count);
        // Every line must be independently parseable JSON -- proof no two
        // concurrent writers interleaved mid-line.
        for (String line : lines) {
            assertThat(line).startsWith("{").endsWith("}");
        }
    }

    @Test
    void logDecisionRequiresANonEmptyRationale(@TempDir Path dir) throws IOException {
        DecisionLogger logger = new DecisionLogger(dir.resolve("lineage.jsonl"));
        assertThatThrownBy(() -> logger.logDecision("r-1", "requirements", "requirements_agent",
                "Short code length = 7 characters", "  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void logDecisionAppendsAnEntryWithTheRationale(@TempDir Path dir) throws IOException {
        Path lineagePath = dir.resolve("lineage.jsonl");
        DecisionLogger logger = new DecisionLogger(lineagePath);

        logger.logDecision("r-1", "requirements", "requirements_agent",
                "7-character codes over base62", "62^7 is ample at any plausible volume.");

        List<Map<String, Object>> entries = DecisionLogger.readLineage(lineagePath);
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0)).containsEntry("rationale", "62^7 is ample at any plausible volume.");
    }

    @Test
    void logReplanRecordsBothStaledAndPreservedNodes(@TempDir Path dir) throws IOException {
        Path lineagePath = dir.resolve("lineage.jsonl");
        DecisionLogger logger = new DecisionLogger(lineagePath);

        logger.logReplan("r-1", "requirement_spec", "a1b2c3d4", "f9e8d7c6",
                List.of("design", "testing"), List.of("requirements", "apply_migration"),
                "Stakeholder added expiry requirement", 1);

        List<Map<String, Object>> entries = DecisionLogger.readLineage(lineagePath);
        @SuppressWarnings("unchecked")
        Map<String, Object> details = (Map<String, Object>) entries.get(0).get("details");
        @SuppressWarnings("unchecked")
        List<String> staled = (List<String>) details.get("staled_nodes");
        @SuppressWarnings("unchecked")
        List<String> preserved = (List<String>) details.get("preserved_nodes");
        assertThat(staled).containsExactly("design", "testing");
        assertThat(preserved).containsExactly("requirements", "apply_migration");
    }

    @Test
    void verifyAppendOnlyPassesWhenTheSnapshotIsAStrictPrefix(@TempDir Path dir) throws IOException {
        Path lineagePath = dir.resolve("lineage.jsonl");
        DecisionLogger logger = new DecisionLogger(lineagePath);
        logger.append(LineageEntry.builder(EventType.RUN_STARTED, "r-1", "t1").build());

        String snapshot = Files.readString(lineagePath);
        logger.append(LineageEntry.builder(EventType.NODE_STARTED, "r-1", "t2").build());

        assertThat(DecisionLogger.verifyAppendOnly(lineagePath, snapshot)).isTrue();
    }

    @Test
    void verifyAppendOnlyFailsIfHistoryWasRewritten(@TempDir Path dir) throws IOException {
        Path lineagePath = dir.resolve("lineage.jsonl");
        DecisionLogger logger = new DecisionLogger(lineagePath);
        logger.append(LineageEntry.builder(EventType.RUN_STARTED, "r-1", "t1").build());
        String snapshot = Files.readString(lineagePath);

        // Simulate history being rewritten (never done by DecisionLogger itself --
        // it offers no such method -- but a test double for "something went wrong").
        Files.writeString(lineagePath, "{\"event\":\"TAMPERED\"}\n");

        assertThat(DecisionLogger.verifyAppendOnly(lineagePath, snapshot)).isFalse();
    }

    @Test
    void readLineageSkipsATornFinalLine(@TempDir Path dir) throws IOException {
        Path lineagePath = dir.resolve("lineage.jsonl");
        DecisionLogger logger = new DecisionLogger(lineagePath);
        logger.append(LineageEntry.builder(EventType.RUN_STARTED, "r-1", "t1").build());
        logger.append(LineageEntry.builder(EventType.NODE_STARTED, "r-1", "t2").build());
        // Simulate a crash mid-append: an incomplete final line.
        Files.writeString(lineagePath, "{\"event\":\"NODE_COMPLETED\",\"run_i", java.nio.file.StandardOpenOption.APPEND);

        List<Map<String, Object>> entries = DecisionLogger.readLineage(lineagePath);
        assertThat(entries).hasSize(2);
    }

    @Test
    void decisionLoggerHasNoUpdateOrDeleteMethod() {
        // Structural enforcement, not convention: assert by reflection that no
        // method could rewrite or remove a prior entry.
        for (var method : DecisionLogger.class.getDeclaredMethods()) {
            String name = method.getName().toLowerCase();
            assertThat(name).doesNotContain("update").doesNotContain("delete").doesNotContain("remove")
                    .doesNotContain("truncate").doesNotContain("rewrite");
        }
    }
}
