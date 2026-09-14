package com.sdlc.orchestrator.gates;

import com.sdlc.orchestrator.model.Artifact;
import com.sdlc.orchestrator.model.ApprovalRecord;
import com.sdlc.orchestrator.model.Decision;
import com.sdlc.orchestrator.model.NodeSpec;
import com.sdlc.orchestrator.model.Run;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ArtifactCheckersTest {

    private CheckerRegistry registry;
    private NodeSpec node;

    @BeforeEach
    void setUp() {
        registry = new CheckerRegistry();
        GenericCheckers.registerDefaults(registry);
        ArtifactCheckers.registerDefaults(registry);
        node = NodeSpec.builder("n", "N", "agent").build();
    }

    private void putArtifact(Run run, String key, Object content) {
        run.artifacts().put(key, new Artifact(key, "producer", content, "", "", 1));
    }

    // ---- all_items_have_field --------------------------------------------------

    @Test
    void allItemsHaveFieldPassesWhenEveryItemHasIt() {
        Run run = new Run("r", "g", "greenfield", "req");
        putArtifact(run, "requirement_spec", Map.of("acceptance_criteria", List.of(
                Map.of("id", "AC1", "verifiable_by", "integration test"),
                Map.of("id", "AC2", "verifiable_by", "unit test"))));

        CheckResult result = registry.get("all_items_have_field").check(run, node, Map.of(
                "field", "requirement_spec.acceptance_criteria", "item_field", "verifiable_by"));
        assertThat(result.passed()).isTrue();
    }

    @Test
    void allItemsHaveFieldFailsNamingTheOffendingItem() {
        Run run = new Run("r", "g", "greenfield", "req");
        putArtifact(run, "requirement_spec", Map.of("acceptance_criteria", List.of(
                Map.of("id", "AC1", "verifiable_by", "integration test"),
                Map.of("id", "AC2"))));

        CheckResult result = registry.get("all_items_have_field").check(run, node, Map.of(
                "field", "requirement_spec.acceptance_criteria", "item_field", "verifiable_by"));
        assertThat(result.passed()).isFalse();
        assertThat(result.message()).contains("AC2");
    }

    // ---- ambiguities_resolved_or_escalated ---------------------------------------

    @Test
    void ambiguitiesResolvedOrEscalatedPassesWhenFullyResolved() {
        Run run = new Run("r", "g", "greenfield", "req");
        putArtifact(run, "ambiguity_report", Map.of("ambiguities", List.of(
                Map.of("id", "AMB1", "resolution", "7 chars", "assumption", "ample volume", "rationale", "see notes",
                        "escalated_to_human", false))));

        CheckResult result = registry.get("ambiguities_resolved_or_escalated")
                .check(run, node, Map.of("field", "ambiguity_report"));
        assertThat(result.passed()).isTrue();
    }

    @Test
    void ambiguitiesResolvedOrEscalatedPassesWhenEscalated() {
        Run run = new Run("r", "g", "greenfield", "req");
        putArtifact(run, "ambiguity_report", Map.of("ambiguities", List.of(
                Map.of("id", "AMB1", "escalated_to_human", true))));

        CheckResult result = registry.get("ambiguities_resolved_or_escalated")
                .check(run, node, Map.of("field", "ambiguity_report"));
        assertThat(result.passed()).isTrue();
    }

    @Test
    void ambiguitiesResolvedOrEscalatedFailsOnSilentResolution() {
        // THIS is the check that must be able to fail, or scenarios/ambiguous.md is
        // theatre: an ambiguity with neither a stated assumption nor an escalation.
        Run run = new Run("r", "g", "ambiguous", "req");
        putArtifact(run, "ambiguity_report", Map.of("ambiguities", List.of(
                Map.of("id", "AMB1", "text", "unspecified", "escalated_to_human", false))));

        CheckResult result = registry.get("ambiguities_resolved_or_escalated")
                .check(run, node, Map.of("field", "ambiguity_report"));
        assertThat(result.passed()).isFalse();
        assertThat(result.message()).contains("AMB1");
    }

    @Test
    void ambiguitiesResolvedOrEscalatedPassesWithNoAmbiguities() {
        Run run = new Run("r", "g", "greenfield", "req");
        putArtifact(run, "ambiguity_report", Map.of("ambiguities", List.of()));

        CheckResult result = registry.get("ambiguities_resolved_or_escalated")
                .check(run, node, Map.of("field", "ambiguity_report"));
        assertThat(result.passed()).isTrue();
    }

    // ---- all_tests_pass -----------------------------------------------------------

    @Test
    void allTestsPassPassesWhenFailuresEmpty() {
        Run run = new Run("r", "g", "greenfield", "req");
        putArtifact(run, "test_results", Map.of("failures", List.of()));

        CheckResult result = registry.get("all_tests_pass")
                .check(run, node, Map.of("field", "test_results.failures", "expect_empty", true));
        assertThat(result.passed()).isTrue();
    }

    @Test
    void allTestsPassFailsWhenFailuresPresent() {
        Run run = new Run("r", "g", "greenfield", "req");
        putArtifact(run, "test_results", Map.of("failures", List.of("test_create_link failed")));

        CheckResult result = registry.get("all_tests_pass")
                .check(run, node, Map.of("field", "test_results.failures", "expect_empty", true));
        assertThat(result.passed()).isFalse();
    }

    // ---- coverage_threshold ---------------------------------------------------------

    @Test
    void coverageThresholdPassesAboveMinimum() {
        Run run = new Run("r", "g", "greenfield", "req");
        putArtifact(run, "test_results", Map.of("coverage_percent", 83.4));

        CheckResult result = registry.get("coverage_threshold")
                .check(run, node, Map.of("field", "test_results.coverage_percent", "min", 70));
        assertThat(result.passed()).isTrue();
    }

    @Test
    void coverageThresholdFailsBelowMinimum() {
        Run run = new Run("r", "g", "greenfield", "req");
        putArtifact(run, "test_results", Map.of("coverage_percent", 55.0));

        CheckResult result = registry.get("coverage_threshold")
                .check(run, node, Map.of("field", "test_results.coverage_percent", "min", 70));
        assertThat(result.passed()).isFalse();
    }

    // ---- field_present ---------------------------------------------------------------

    @Test
    void fieldPresentPassesForAnAllowedValue() {
        Run run = new Run("r", "g", "greenfield", "req");
        putArtifact(run, "readiness_report", Map.of("recommendation", "go"));

        CheckResult result = registry.get("field_present").check(run, node, Map.of(
                "field", "readiness_report.recommendation",
                "allowed_values", List.of("go", "no_go", "go_with_conditions")));
        assertThat(result.passed()).isTrue();
    }

    @Test
    void fieldPresentFailsForADisallowedValue() {
        Run run = new Run("r", "g", "greenfield", "req");
        putArtifact(run, "readiness_report", Map.of("recommendation", "maybe"));

        CheckResult result = registry.get("field_present").check(run, node, Map.of(
                "field", "readiness_report.recommendation",
                "allowed_values", List.of("go", "no_go", "go_with_conditions")));
        assertThat(result.passed()).isFalse();
    }

    @Test
    void fieldPresentFailsWhenMissing() {
        Run run = new Run("r", "g", "greenfield", "req");
        CheckResult result = registry.get("field_present").check(run, node, Map.of(
                "field", "readiness_report.recommendation",
                "allowed_values", List.of("go", "no_go")));
        assertThat(result.passed()).isFalse();
    }

    // ---- high_impact_actions_flagged ---------------------------------------------------

    @Test
    void highImpactActionsFlaggedPassesWhenNoSchemaChangeDeclared() {
        Run run = new Run("r", "g", "greenfield", "req");
        putArtifact(run, "design_doc", Map.of("requires_schema_change", false, "high_impact_actions", List.of()));

        CheckResult result = registry.get("high_impact_actions_flagged")
                .check(run, node, Map.of("field", "design_doc.high_impact_actions"));
        assertThat(result.passed()).isTrue();
    }

    @Test
    void highImpactActionsFlaggedFailsWhenSchemaChangeIsUnflagged() {
        Run run = new Run("r", "g", "brownfield", "req");
        putArtifact(run, "design_doc", Map.of("requires_schema_change", true, "high_impact_actions", List.of()));

        CheckResult result = registry.get("high_impact_actions_flagged")
                .check(run, node, Map.of("field", "design_doc.high_impact_actions"));
        assertThat(result.passed()).isFalse();
    }

    @Test
    void highImpactActionsFlaggedPassesWhenSchemaChangeIsFlagged() {
        Run run = new Run("r", "g", "brownfield", "req");
        putArtifact(run, "design_doc", Map.of("requires_schema_change", true,
                "high_impact_actions", List.of("apply_migration: adds click_events table")));

        CheckResult result = registry.get("high_impact_actions_flagged")
                .check(run, node, Map.of("field", "design_doc.high_impact_actions"));
        assertThat(result.passed()).isTrue();
    }

    // ---- no_contradictions -------------------------------------------------------------

    @Test
    void noContradictionsPassesWhenDocumentedPathsAreInTheSchema() {
        Run run = new Run("r", "g", "greenfield", "req");
        putArtifact(run, "documentation", Map.of("endpoints", List.of("/api/v1/links", "/{code}")));
        putArtifact(run, "api_schema", Map.of("paths", Map.of("/api/v1/links", Map.of(), "/{code}", Map.of())));

        CheckResult result = registry.get("no_contradictions")
                .check(run, node, Map.of("docs", "documentation", "schema", "api_schema"));
        assertThat(result.passed()).isTrue();
    }

    @Test
    void noContradictionsFailsWhenDocsDescribeAnUndeclaredPath() {
        Run run = new Run("r", "g", "greenfield", "req");
        putArtifact(run, "documentation", Map.of("endpoints", List.of("/api/v1/links", "/api/v1/nonexistent")));
        putArtifact(run, "api_schema", Map.of("paths", Map.of("/api/v1/links", Map.of())));

        CheckResult result = registry.get("no_contradictions")
                .check(run, node, Map.of("docs", "documentation", "schema", "api_schema"));
        assertThat(result.passed()).isFalse();
        assertThat(result.message()).contains("/api/v1/nonexistent");
    }

    // ---- no_test_weakening -----------------------------------------------------------------

    @Test
    void noTestWeakeningPassesWhenThereIsNoPriorRun() {
        Run run = new Run("r", "g", "greenfield", "req");
        putArtifact(run, "test_suite", Map.of("test_count", 18, "assertion_count", 40));

        CheckResult result = registry.get("no_test_weakening")
                .check(run, node, Map.of("compare_to", "previous_run.test_suite"));
        assertThat(result.passed()).isTrue();
    }

    @Test
    void noTestWeakeningPassesWhenCountsHoldOrGrow() {
        Run run = new Run("r", "g", "greenfield", "req");
        putArtifact(run, "test_suite", Map.of("test_count", 20, "assertion_count", 45));
        putArtifact(run, "previous_run", Map.of("test_suite", Map.of("test_count", 18, "assertion_count", 40)));

        CheckResult result = registry.get("no_test_weakening")
                .check(run, node, Map.of("compare_to", "previous_run.test_suite"));
        assertThat(result.passed()).isTrue();
    }

    @Test
    void noTestWeakeningFailsWhenTestCountDrops() {
        Run run = new Run("r", "g", "greenfield", "req");
        putArtifact(run, "test_suite", Map.of("test_count", 12, "assertion_count", 40));
        putArtifact(run, "previous_run", Map.of("test_suite", Map.of("test_count", 18, "assertion_count", 40)));

        CheckResult result = registry.get("no_test_weakening")
                .check(run, node, Map.of("compare_to", "previous_run.test_suite"));
        assertThat(result.passed()).isFalse();
    }

    @Test
    void noTestWeakeningFailsWhenAssertionCountDrops() {
        Run run = new Run("r", "g", "greenfield", "req");
        putArtifact(run, "test_suite", Map.of("test_count", 18, "assertion_count", 30));
        putArtifact(run, "previous_run", Map.of("test_suite", Map.of("test_count", 18, "assertion_count", 40)));

        CheckResult result = registry.get("no_test_weakening")
                .check(run, node, Map.of("compare_to", "previous_run.test_suite"));
        assertThat(result.passed()).isFalse();
    }

    // ---- no_unapproved_high_impact -----------------------------------------------------------

    @Test
    void noUnapprovedHighImpactPassesWhenNoMigrationWasApplied() {
        Run run = new Run("r", "g", "greenfield", "req");
        CheckResult result = registry.get("no_unapproved_high_impact").check(run, node, Map.of());
        assertThat(result.passed()).isTrue();
    }

    @Test
    void noUnapprovedHighImpactPassesWhenMigrationWasApprovedFirst() {
        Run run = new Run("r", "g", "brownfield", "req");
        putArtifact(run, "migration_applied", Map.of("resulting_schema", Map.of()));
        run.approvals().add(new ApprovalRecord("apply_migration", "mukesh", "t1",
                Decision.APPROVED, "reviewed", Map.of(), "r"));

        CheckResult result = registry.get("no_unapproved_high_impact").check(run, node, Map.of());
        assertThat(result.passed()).isTrue();
    }

    @Test
    void noUnapprovedHighImpactFailsWhenMigrationRanWithNoApproval() {
        Run run = new Run("r", "g", "brownfield", "req");
        putArtifact(run, "migration_applied", Map.of("resulting_schema", Map.of()));

        CheckResult result = registry.get("no_unapproved_high_impact").check(run, node, Map.of());
        assertThat(result.passed()).isFalse();
    }

    // ---- recommendation_consistent_with_evidence -----------------------------------------------

    @Test
    void recommendationConsistentWithEvidencePassesForGoWithPassingTests() {
        Run run = new Run("r", "g", "greenfield", "req");
        putArtifact(run, "readiness_report", Map.of("recommendation", "go"));
        putArtifact(run, "test_results", Map.of("failures", List.of()));

        CheckResult result = registry.get("recommendation_consistent_with_evidence").check(run, node, Map.of(
                "recommendation", "readiness_report.recommendation",
                "evidence", List.of("test_results")));
        assertThat(result.passed()).isTrue();
    }

    @Test
    void recommendationConsistentWithEvidenceFailsForGoWithFailingTests() {
        Run run = new Run("r", "g", "greenfield", "req");
        putArtifact(run, "readiness_report", Map.of("recommendation", "go"));
        putArtifact(run, "test_results", Map.of("failures", List.of("test_x failed")));

        CheckResult result = registry.get("recommendation_consistent_with_evidence").check(run, node, Map.of(
                "recommendation", "readiness_report.recommendation",
                "evidence", List.of("test_results")));
        assertThat(result.passed()).isFalse();
    }

    @Test
    void recommendationConsistentWithEvidencePassesForNoGoWithFailingTests() {
        Run run = new Run("r", "g", "greenfield", "req");
        putArtifact(run, "readiness_report", Map.of("recommendation", "no_go"));
        putArtifact(run, "test_results", Map.of("failures", List.of("test_x failed")));

        CheckResult result = registry.get("recommendation_consistent_with_evidence").check(run, node, Map.of(
                "recommendation", "readiness_report.recommendation",
                "evidence", List.of("test_results")));
        assertThat(result.passed()).isTrue();
    }

    // ---- schema_matches_expectation -----------------------------------------------------------

    @Test
    void schemaMatchesExpectationPassesWhenEqual() {
        Run run = new Run("r", "g", "brownfield", "req");
        putArtifact(run, "migration_applied", Map.of("resulting_schema", Map.of("columns", List.of("code", "click_count"))));
        putArtifact(run, "migration_plan", Map.of("expected_schema", Map.of("columns", List.of("code", "click_count"))));

        CheckResult result = registry.get("schema_matches_expectation").check(run, node, Map.of(
                "field", "migration_applied.resulting_schema", "expected", "migration_plan.expected_schema"));
        assertThat(result.passed()).isTrue();
    }

    @Test
    void schemaMatchesExpectationFailsWhenDifferent() {
        Run run = new Run("r", "g", "brownfield", "req");
        putArtifact(run, "migration_applied", Map.of("resulting_schema", Map.of("columns", List.of("code"))));
        putArtifact(run, "migration_plan", Map.of("expected_schema", Map.of("columns", List.of("code", "click_count"))));

        CheckResult result = registry.get("schema_matches_expectation").check(run, node, Map.of(
                "field", "migration_applied.resulting_schema", "expected", "migration_plan.expected_schema"));
        assertThat(result.passed()).isFalse();
    }

    // ---- setup_instructions_present -----------------------------------------------------------

    @Test
    void setupInstructionsPresentPassesWhenNonBlank() {
        Run run = new Run("r", "g", "greenfield", "req");
        putArtifact(run, "documentation", Map.of("setup", "mvn spring-boot:run"));

        CheckResult result = registry.get("setup_instructions_present")
                .check(run, node, Map.of("field", "documentation.setup"));
        assertThat(result.passed()).isTrue();
    }

    @Test
    void setupInstructionsPresentFailsWhenMissing() {
        Run run = new Run("r", "g", "greenfield", "req");
        putArtifact(run, "documentation", Map.of());

        CheckResult result = registry.get("setup_instructions_present")
                .check(run, node, Map.of("field", "documentation.setup"));
        assertThat(result.passed()).isFalse();
    }

    // ---- tests_executed --------------------------------------------------------------------

    @Test
    void testsExecutedPassesWhenTotalIsPositive() {
        Run run = new Run("r", "g", "greenfield", "req");
        putArtifact(run, "test_results", Map.of("total", 18, "passed", 18, "failed", 0));

        CheckResult result = registry.get("tests_executed").check(run, node, Map.of("field", "test_results"));
        assertThat(result.passed()).isTrue();
    }

    @Test
    void testsExecutedFailsWhenTotalIsZeroOrMissing() {
        Run run = new Run("r", "g", "greenfield", "req");
        putArtifact(run, "test_results", Map.of("total", 0));

        CheckResult result = registry.get("tests_executed").check(run, node, Map.of("field", "test_results"));
        assertThat(result.passed()).isFalse();
    }

    // ---- acceptance_criteria_covered -----------------------------------------------------------

    @Test
    void acceptanceCriteriaCoveredPassesWhenEveryCriterionIsCovered() {
        Run run = new Run("r", "g", "greenfield", "req");
        putArtifact(run, "requirement_spec", Map.of("acceptance_criteria", List.of(
                Map.of("id", "AC1"), Map.of("id", "AC2"))));
        putArtifact(run, "test_results", Map.of("covered_acceptance_criteria", List.of("AC1", "AC2")));

        CheckResult result = registry.get("acceptance_criteria_covered").check(run, node, Map.of(
                "criteria", "requirement_spec.acceptance_criteria", "results", "test_results"));
        assertThat(result.passed()).isTrue();
    }

    @Test
    void acceptanceCriteriaCoveredFailsNamingTheUncoveredCriterion() {
        Run run = new Run("r", "g", "greenfield", "req");
        putArtifact(run, "requirement_spec", Map.of("acceptance_criteria", List.of(
                Map.of("id", "AC1"), Map.of("id", "AC2"))));
        putArtifact(run, "test_results", Map.of("covered_acceptance_criteria", List.of("AC1")));

        CheckResult result = registry.get("acceptance_criteria_covered").check(run, node, Map.of(
                "criteria", "requirement_spec.acceptance_criteria", "results", "test_results"));
        assertThat(result.passed()).isFalse();
        assertThat(result.message()).contains("AC2");
    }
}
