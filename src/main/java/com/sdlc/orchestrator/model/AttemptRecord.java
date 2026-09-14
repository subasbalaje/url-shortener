package com.sdlc.orchestrator.model;

import java.util.LinkedHashMap;
import java.util.Map;

/** One execution attempt of one node. The unit of the metrics timeline. Mutable —
 *  fields are filled in as the attempt progresses from dispatch to outcome. */
public final class AttemptRecord {

    private final int attempt;
    private final String startedAt;
    private String endedAt;
    private String outcome = "running"; // running | success | failure | gate_failed
    private String errorType;
    private String errorMessage;
    private Double durationSeconds;

    public AttemptRecord(int attempt, String startedAt) {
        this.attempt = attempt;
        this.startedAt = startedAt;
    }

    public void complete(String endedAt, String outcome, double durationSeconds) {
        this.endedAt = endedAt;
        this.outcome = outcome;
        this.durationSeconds = durationSeconds;
    }

    public void fail(String endedAt, String errorType, String errorMessage, double durationSeconds) {
        this.endedAt = endedAt;
        this.outcome = "failure";
        this.errorType = errorType;
        this.errorMessage = errorMessage;
        this.durationSeconds = durationSeconds;
    }

    public int attempt() { return attempt; }
    public String startedAt() { return startedAt; }
    public String endedAt() { return endedAt; }
    public String outcome() { return outcome; }
    public String errorType() { return errorType; }
    public String errorMessage() { return errorMessage; }
    public Double durationSeconds() { return durationSeconds; }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("attempt", attempt);
        map.put("started_at", startedAt);
        map.put("ended_at", endedAt);
        map.put("outcome", outcome);
        map.put("error_type", errorType);
        map.put("error_message", errorMessage);
        map.put("duration_seconds", durationSeconds);
        return map;
    }

    public static AttemptRecord fromMap(Map<String, Object> map) {
        AttemptRecord record = new AttemptRecord(
                ((Number) map.get("attempt")).intValue(), (String) map.get("started_at"));
        record.endedAt = (String) map.get("ended_at");
        record.outcome = (String) map.get("outcome");
        record.errorType = (String) map.get("error_type");
        record.errorMessage = (String) map.get("error_message");
        Object duration = map.get("duration_seconds");
        record.durationSeconds = duration == null ? null : ((Number) duration).doubleValue();
        return record;
    }
}
