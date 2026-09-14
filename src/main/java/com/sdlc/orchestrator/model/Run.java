package com.sdlc.orchestrator.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Complete state of one execution. Serialised to {@code state.json}.
 *
 * <p>This object <em>is</em> the resumability story: everything needed to continue
 * after a gate must be here, because the process is expected to exit while blocked
 * (DEC-0007).
 *
 * <p>Serialisation is hand-rolled ({@link #toStateMap()}/{@link #fromStateMap}
 * rather than reflection-based Jackson bean binding, deliberately: this class and
 * its nested {@link NodeRuntime}/{@link Artifact}/{@link AttemptRecord} use
 * record-style bare accessors (e.g. {@code runId()}), not JavaBean {@code getX()}
 * methods, and a hand-written map keeps the persisted shape exactly the
 * snake_case, human-inspectable form DEC-0005 calls for without fighting Jackson's
 * bean-introspection conventions.
 */
public final class Run {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String runId;
    private final String graphId;
    private final String mode; // greenfield | brownfield | ambiguous
    private RunState state = RunState.INITIALISED;

    private final String rawRequest;
    private String startedAt;
    private String endedAt;

    private final Map<String, NodeRuntime> nodes = new LinkedHashMap<>();
    private final Map<String, Artifact> artifacts = new LinkedHashMap<>();
    private final List<ApprovalRecord> approvals = new ArrayList<>();

    private int replanCycles = 0;
    private String safeStopReason;
    private int approvalsInvalidatedByReplan = 0;

    /** Set while paused at a gate; survives persistence so a freshly-loaded Run
     *  (post process-death) still knows which node is blocked and how. */
    private String blockedNodeId;
    private String blockedGateKind; // "entry" | "exit"

    public Run(String runId, String graphId, String mode, String rawRequest) {
        this.runId = runId;
        this.graphId = graphId;
        this.mode = mode;
        this.rawRequest = rawRequest;
        this.startedAt = Instant.now().toString();
    }

    public String runId() { return runId; }
    public String graphId() { return graphId; }
    public String mode() { return mode; }
    public RunState state() { return state; }
    public void setState(RunState state) { this.state = state; }
    public String rawRequest() { return rawRequest; }
    public String startedAt() { return startedAt; }
    public String endedAt() { return endedAt; }
    public void setEndedAt(String endedAt) { this.endedAt = endedAt; }
    public Map<String, NodeRuntime> nodes() { return nodes; }
    public Map<String, Artifact> artifacts() { return artifacts; }
    public List<ApprovalRecord> approvals() { return approvals; }
    public int replanCycles() { return replanCycles; }
    public void incrementReplanCycles() { replanCycles++; }
    public String safeStopReason() { return safeStopReason; }
    public void setSafeStopReason(String reason) { this.safeStopReason = reason; }
    public int approvalsInvalidatedByReplan() { return approvalsInvalidatedByReplan; }
    public void incrementApprovalsInvalidatedByReplan() { approvalsInvalidatedByReplan++; }
    public String blockedNodeId() { return blockedNodeId; }
    public void setBlockedNodeId(String nodeId) { this.blockedNodeId = nodeId; }
    public String blockedGateKind() { return blockedGateKind; }
    public void setBlockedGateKind(String kind) { this.blockedGateKind = kind; }
    public void clearBlockedGate() { this.blockedNodeId = null; this.blockedGateKind = null; }

    /**
     * Persist atomically: write a temp file, then {@code Files.move} with
     * {@code ATOMIC_MOVE}. Atomicity matters because this file is read back to
     * resume — a torn {@code state.json} from a crash mid-write would make a
     * paused run unresumable, losing exactly the state the gate exists to protect.
     */
    public void save(Path path) throws IOException {
        String json;
        try {
            json = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(toStateMap());
        } catch (JsonProcessingException e) {
            throw new IOException("Run state is not JSON-serialisable", e);
        }
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        Files.writeString(tmp, json, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    /** Load persisted run state for {@code resume()}. */
    public static Run load(Path path) throws IOException {
        @SuppressWarnings("unchecked")
        Map<String, Object> map = MAPPER.readValue(Files.readString(path), Map.class);
        return fromStateMap(map);
    }

    // ---- explicit map (de)serialisation -----------------------------------------

    public Map<String, Object> toStateMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("run_id", runId);
        map.put("graph_id", graphId);
        map.put("mode", mode);
        map.put("state", state.name());
        map.put("raw_request", rawRequest);
        map.put("started_at", startedAt);
        map.put("ended_at", endedAt);
        map.put("replan_cycles", replanCycles);
        map.put("safe_stop_reason", safeStopReason);
        map.put("approvals_invalidated_by_replan", approvalsInvalidatedByReplan);
        map.put("blocked_node_id", blockedNodeId);
        map.put("blocked_gate_kind", blockedGateKind);

        Map<String, Object> nodesMap = new LinkedHashMap<>();
        nodes.forEach((id, runtime) -> nodesMap.put(id, runtime.toMap()));
        map.put("nodes", nodesMap);

        Map<String, Object> artifactsMap = new LinkedHashMap<>();
        artifacts.forEach((key, artifact) -> artifactsMap.put(key, artifact.toMap()));
        map.put("artifacts", artifactsMap);

        map.put("approvals", approvals.stream().map(Run::approvalToMap).toList());
        return map;
    }

    @SuppressWarnings("unchecked")
    public static Run fromStateMap(Map<String, Object> map) {
        Run run = new Run((String) map.get("run_id"), (String) map.get("graph_id"),
                (String) map.get("mode"), (String) map.get("raw_request"));
        run.startedAt = (String) map.get("started_at");
        run.endedAt = (String) map.get("ended_at");
        run.state = RunState.valueOf((String) map.get("state"));
        run.replanCycles = ((Number) map.getOrDefault("replan_cycles", 0)).intValue();
        run.safeStopReason = (String) map.get("safe_stop_reason");
        run.approvalsInvalidatedByReplan = ((Number) map.getOrDefault("approvals_invalidated_by_replan", 0)).intValue();
        run.blockedNodeId = (String) map.get("blocked_node_id");
        run.blockedGateKind = (String) map.get("blocked_gate_kind");

        Map<String, Object> nodesMap = (Map<String, Object>) map.getOrDefault("nodes", Map.of());
        nodesMap.forEach((id, raw) -> run.nodes.put(id, NodeRuntime.fromMap((Map<String, Object>) raw)));

        Map<String, Object> artifactsMap = (Map<String, Object>) map.getOrDefault("artifacts", Map.of());
        artifactsMap.forEach((key, raw) -> run.artifacts.put(key, Artifact.fromMap((Map<String, Object>) raw)));

        List<Object> approvalsList = (List<Object>) map.getOrDefault("approvals", List.of());
        for (Object raw : approvalsList) {
            run.approvals.add(approvalFromMap((Map<String, Object>) raw));
        }
        return run;
    }

    private static Map<String, Object> approvalToMap(ApprovalRecord a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("node_id", a.nodeId());
        m.put("approver", a.approver());
        m.put("decided_at", a.decidedAt());
        m.put("decision", a.decision().name());
        m.put("comment", a.comment());
        m.put("artifact_ref", a.artifactRef());
        m.put("run_id", a.runId());
        return m;
    }

    @SuppressWarnings("unchecked")
    private static ApprovalRecord approvalFromMap(Map<String, Object> m) {
        return new ApprovalRecord((String) m.get("node_id"), (String) m.get("approver"), (String) m.get("decided_at"),
                Decision.valueOf((String) m.get("decision")), (String) m.get("comment"),
                (Map<String, String>) m.getOrDefault("artifact_ref", Map.of()), (String) m.get("run_id"));
    }
}
