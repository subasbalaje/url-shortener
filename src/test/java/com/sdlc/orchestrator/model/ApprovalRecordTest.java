package com.sdlc.orchestrator.model;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ApprovalRecordTest {

    private ApprovalRecord record(Decision decision) {
        return new ApprovalRecord("release_readiness", "mukesh", "2026-09-13T07:45:09Z",
                decision, "comment", Map.of("readiness_report", "b2c3d4e5"), "r-1");
    }

    @Test
    void approvedPermitsContinuation() {
        assertThat(record(Decision.APPROVED).permitsContinuation()).isTrue();
    }

    @Test
    void approvedWithConditionsPermitsContinuation() {
        assertThat(record(Decision.APPROVED_WITH_CONDITIONS).permitsContinuation()).isTrue();
    }

    @Test
    void rejectedDoesNotPermitContinuation() {
        assertThat(record(Decision.REJECTED).permitsContinuation()).isFalse();
    }

    @Test
    void artifactRefBindsApprovalToExactContentHashes() {
        ApprovalRecord approval = record(Decision.APPROVED);
        assertThat(approval.artifactRef()).containsEntry("readiness_report", "b2c3d4e5");
    }
}
