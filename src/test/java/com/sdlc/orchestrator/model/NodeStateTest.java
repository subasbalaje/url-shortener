package com.sdlc.orchestrator.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NodeStateTest {

    @Test
    void completedAndSkippedAreTerminalSuccess() {
        assertThat(NodeState.COMPLETED.isTerminalSuccess()).isTrue();
        assertThat(NodeState.SKIPPED.isTerminalSuccess()).isTrue();
    }

    @Test
    void otherStatesAreNotTerminalSuccess() {
        assertThat(NodeState.PENDING.isTerminalSuccess()).isFalse();
        assertThat(NodeState.READY.isTerminalSuccess()).isFalse();
        assertThat(NodeState.RUNNING.isTerminalSuccess()).isFalse();
        assertThat(NodeState.BLOCKED_ON_GATE.isTerminalSuccess()).isFalse();
        assertThat(NodeState.FAILED.isTerminalSuccess()).isFalse();
        assertThat(NodeState.RETRYING.isTerminalSuccess()).isFalse();
        assertThat(NodeState.ROLLED_BACK.isTerminalSuccess()).isFalse();
        assertThat(NodeState.UPSTREAM_FAILED.isTerminalSuccess()).isFalse();
        assertThat(NodeState.STALE.isTerminalSuccess()).isFalse();
    }

    @Test
    void rolledBackAndUpstreamFailedAreTerminalFailure() {
        assertThat(NodeState.ROLLED_BACK.isTerminalFailure()).isTrue();
        assertThat(NodeState.UPSTREAM_FAILED.isTerminalFailure()).isTrue();
    }

    @Test
    void failedIsNotTerminalFailure() {
        // FAILED is a transient state the failure ladder has not yet resolved
        // into RETRYING or ROLLED_BACK — it must not be counted as terminal.
        assertThat(NodeState.FAILED.isTerminalFailure()).isFalse();
    }

    @Test
    void runningRetryingAndBlockedOnGateAreActive() {
        assertThat(NodeState.RUNNING.isActive()).isTrue();
        assertThat(NodeState.RETRYING.isActive()).isTrue();
        assertThat(NodeState.BLOCKED_ON_GATE.isActive()).isTrue();
    }

    @Test
    void terminalAndPendingStatesAreNotActive() {
        assertThat(NodeState.PENDING.isActive()).isFalse();
        assertThat(NodeState.READY.isActive()).isFalse();
        assertThat(NodeState.COMPLETED.isActive()).isFalse();
        assertThat(NodeState.SKIPPED.isActive()).isFalse();
        assertThat(NodeState.FAILED.isActive()).isFalse();
        assertThat(NodeState.ROLLED_BACK.isActive()).isFalse();
        assertThat(NodeState.UPSTREAM_FAILED.isActive()).isFalse();
        assertThat(NodeState.STALE.isActive()).isFalse();
    }

    @Test
    void hasExactlyElevenStates() {
        assertThat(NodeState.values()).hasSize(11);
    }
}
