package com.sdlc.orchestrator.lineage;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Appends lineage entries. The only writer of the audit trail.
 *
 * <p><b>Append-only is enforced structurally</b>, not by convention: the file is
 * always opened with {@link StandardOpenOption#APPEND}, and this class simply
 * offers no method that updates or deletes a prior entry — the absence itself is
 * the guarantee. {@code DecisionLoggerTest.decisionLoggerHasNoUpdateOrDeleteMethod}
 * asserts that by reflection over this class's declared methods.
 *
 * <p>Flushes per entry ({@code Files.writeString} with {@code APPEND} does an
 * OS-level write per call) — a deliberate durability-over-throughput trade, since
 * buffered lineage lost in a crash would be missing exactly the entries explaining
 * the crash.
 *
 * <p><b>Concurrency (DEC-0013):</b> a single {@code synchronized} lock serialises
 * every append. Virtual threads are genuinely concurrent — unlike the
 * single-threaded {@code asyncio} model this replaced — so the single-writer
 * property that keeps the audit trail trustworthy must now be enforced with a
 * lock rather than assumed from single-threadedness.
 */
public final class DecisionLogger {

    private static final ObjectMapper READ_MAPPER = new ObjectMapper();

    private final Path lineagePath;
    private final Object writeLock = new Object();

    public DecisionLogger(Path lineagePath) {
        this.lineagePath = lineagePath;
    }

    public void append(LineageEntry entry) throws IOException {
        synchronized (writeLock) {
            if (lineagePath.getParent() != null) {
                Files.createDirectories(lineagePath.getParent());
            }
            Files.writeString(lineagePath, entry.toJsonLine() + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
    }

    /**
     * Records a narrative decision. Requires a non-empty {@code rationale}: a
     * decision logged without <em>why</em> satisfies the letter of an audit trail
     * while losing its entire purpose.
     */
    public void logDecision(String runId, String nodeId, String actor, String decisionSummary, String rationale) throws IOException {
        if (rationale == null || rationale.isBlank()) {
            throw new IllegalArgumentException("logDecision requires a non-empty rationale.");
        }
        append(LineageEntry.builder(EventType.DECISION_RECORDED, runId, now())
                .nodeId(nodeId)
                .actor(actor)
                .decision(decisionSummary)
                .rationale(rationale)
                .build());
    }

    /**
     * Records a re-plan cycle. {@code preservedNodes} is recorded alongside
     * {@code staledNodes} deliberately — it is the positive evidence that
     * re-planning preserved governance rather than resetting the run.
     */
    public void logReplan(String runId, String changedArtifact, String oldHash, String newHash,
                           List<String> staledNodes, List<String> preservedNodes, String rationale, int replanCycle) throws IOException {
        append(LineageEntry.builder(EventType.REPLAN_TRIGGERED, runId, now())
                .actor("orchestrator")
                .rationale(rationale)
                .details(Map.of(
                        "changed_artifact", changedArtifact,
                        "old_hash", oldHash,
                        "new_hash", newHash,
                        "staled_nodes", staledNodes,
                        "preserved_nodes", preservedNodes,
                        "replan_cycle", replanCycle))
                .build());
    }

    /**
     * Makes "append-only" a testable property rather than a docstring claim: true
     * iff {@code currentContent} still starts with {@code previousSnapshot} —
     * i.e. every byte previously written is still there, untouched, at the front
     * of the file.
     */
    public static boolean verifyAppendOnly(Path path, String previousSnapshot) throws IOException {
        String current = Files.readString(path);
        return current.startsWith(previousSnapshot);
    }

    /**
     * Reads every syntactically-complete line as a raw JSON object. Tolerates a
     * torn final line (a crash mid-append) by skipping it — recoverable partial
     * evidence beats an unreadable file.
     */
    public static List<Map<String, Object>> readLineage(Path path) throws IOException {
        List<Map<String, Object>> entries = new ArrayList<>();
        for (String line : Files.readAllLines(path)) {
            if (line.isBlank()) {
                continue;
            }
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> parsed = READ_MAPPER.readValue(line, Map.class);
                entries.add(parsed);
            } catch (JsonProcessingException e) {
                // A torn line can only be the last one a well-behaved appender ever
                // produces (each append is one complete write); skip it rather than
                // failing the whole file.
                break;
            }
        }
        return entries;
    }

    private static String now() {
        return Instant.now().toString();
    }
}
