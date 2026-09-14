package com.sdlc.orchestrator.gates;

import com.sdlc.orchestrator.model.NodeSpec;
import com.sdlc.orchestrator.model.RollbackAction;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PolicyEngineTest {

    // ---- classify() ----------------------------------------------------------

    @Test
    void classifiesAMigrationProducingNodeAsSchemaChange() {
        NodeSpec applyMigration = NodeSpec.builder("apply_migration", "Apply Migration", "implementation_agent")
                .produces(List.of("migration_applied"))
                .build();
        assertThat(PolicyEngine.classify(applyMigration)).containsExactly(PolicyEngine.Criterion.SCHEMA_CHANGE);
    }

    @Test
    void classifiesANodeWithAnExecuteSqlRollbackAsSchemaChange() {
        NodeSpec node = NodeSpec.builder("n", "N", "implementation_agent")
                .rollbackAction(new RollbackAction("execute_sql", "", Map.of(), "safe_stop_and_page"))
                .build();
        assertThat(PolicyEngine.classify(node)).containsExactly(PolicyEngine.Criterion.SCHEMA_CHANGE);
    }

    @Test
    void classifiesTheReleaseReadinessAgentRoleAsReleaseReadiness() {
        NodeSpec node = NodeSpec.builder("release_readiness", "Release Readiness", "release_readiness_agent").build();
        assertThat(PolicyEngine.classify(node)).containsExactly(PolicyEngine.Criterion.RELEASE_READINESS);
    }

    @Test
    void classifiesAnOrdinaryNodeAsMeetingNoCriteria() {
        NodeSpec node = NodeSpec.builder("design", "Design", "design_agent").build();
        assertThat(PolicyEngine.classify(node)).isEmpty();
    }

    // ---- isHighImpact() --------------------------------------------------------

    @Test
    void isHighImpactTrueWhenAnyCriterionIsMet() {
        NodeSpec node = NodeSpec.builder("apply_migration", "Apply Migration", "implementation_agent")
                .produces(List.of("migration_applied"))
                .build();
        assertThat(PolicyEngine.isHighImpact(node)).isTrue();
    }

    @Test
    void isHighImpactFalseWhenNoCriteriaAreMet() {
        NodeSpec node = NodeSpec.builder("design", "Design", "design_agent").build();
        assertThat(PolicyEngine.isHighImpact(node)).isFalse();
    }

    // ---- validateDeclaration() --------------------------------------------------

    @Test
    void validateDeclarationFailsWhenHighImpactNodeDoesNotRequireApproval() {
        NodeSpec node = NodeSpec.builder("apply_migration", "Apply Migration", "implementation_agent")
                .produces(List.of("migration_applied"))
                .requiresHumanApproval(false)
                .build();

        List<String> failures = PolicyEngine.validateDeclaration(node);
        assertThat(failures).isNotEmpty();
        assertThat(failures.get(0)).contains("apply_migration").contains("schema_change");
    }

    @Test
    void validateDeclarationPassesWhenHighImpactNodeDoesRequireApproval() {
        NodeSpec node = NodeSpec.builder("apply_migration", "Apply Migration", "implementation_agent")
                .produces(List.of("migration_applied"))
                .requiresHumanApproval(true)
                .build();

        assertThat(PolicyEngine.validateDeclaration(node)).isEmpty();
    }

    @Test
    void validateDeclarationPassesForANonHighImpactNodeRegardlessOfFlag() {
        NodeSpec node = NodeSpec.builder("design", "Design", "design_agent").requiresHumanApproval(false).build();
        assertThat(PolicyEngine.validateDeclaration(node)).isEmpty();
    }
}
