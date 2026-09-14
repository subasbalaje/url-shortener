package com.sdlc.orchestrator.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NodeSpecTest {

    private NodeSpec.Builder minimal(String id) {
        return NodeSpec.builder(id, "Name for " + id, "some_agent");
    }

    @Test
    void isHighImpactMirrorsRequiresHumanApproval() {
        NodeSpec gated = minimal("apply_migration").requiresHumanApproval(true).build();
        NodeSpec ungated = minimal("design").requiresHumanApproval(false).build();

        assertThat(gated.isHighImpact()).isTrue();
        assertThat(ungated.isHighImpact()).isFalse();
    }

    @Test
    void defaultsAreSensibleWhenNotSpecified() {
        NodeSpec node = minimal("requirements").build();

        assertThat(node.dependsOn()).isEmpty();
        assertThat(node.canRunParallel()).isEmpty();
        assertThat(node.produces()).isEmpty();
        assertThat(node.requiresHumanApproval()).isFalse();
        assertThat(node.retryPolicy()).isEqualTo(RetryPolicy.defaults());
        assertThat(node.rePlanTriggers()).isEmpty();
        assertThat(node.timeoutSeconds()).isEqualTo(600.0);
    }

    @Test
    void dependsOnAndProducesAreCapturedVerbatim() {
        NodeSpec node = minimal("design")
                .dependsOn(List.of("requirements"))
                .produces(List.of("design_doc", "api_schema"))
                .build();

        assertThat(node.dependsOn()).containsExactly("requirements");
        assertThat(node.produces()).containsExactly("design_doc", "api_schema");
    }
}
