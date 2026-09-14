package com.sdlc.orchestrator.gates;

import com.sdlc.orchestrator.model.ApprovalRecord;
import com.sdlc.orchestrator.model.NodeSpec;
import com.sdlc.orchestrator.model.Run;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The domain-specific checkers whose logic operates entirely on already-produced
 * artifact <em>content</em> (JSON-shaped maps/lists) — no filesystem or subprocess
 * access. Checkers that must inspect real source files live in
 * {@link SourceCodeCheckers} instead, per the "never execute agent-written code
 * in-process" rule: separating the two groups keeps that boundary visible.
 *
 * <p>Each checker documents the artifact shape it expects. These shapes are the
 * contract the stage agents (build-order step 11) must honour when they start
 * producing real artifacts instead of the synthetic fixtures these tests use.
 */
public final class ArtifactCheckers {

    private ArtifactCheckers() {}

    public static void registerDefaults(CheckerRegistry registry) {
        registry.register("all_items_have_field", ArtifactCheckers::allItemsHaveField);
        registry.register("ambiguities_resolved_or_escalated", ArtifactCheckers::ambiguitiesResolvedOrEscalated);
        registry.register("all_tests_pass", ArtifactCheckers::allTestsPass);
        registry.register("coverage_threshold", ArtifactCheckers::coverageThreshold);
        registry.register("field_present", ArtifactCheckers::fieldPresent);
        registry.register("high_impact_actions_flagged", ArtifactCheckers::highImpactActionsFlagged);
        registry.register("no_contradictions", ArtifactCheckers::noContradictions);
        registry.register("no_test_weakening", ArtifactCheckers::noTestWeakening);
        registry.register("no_unapproved_high_impact", ArtifactCheckers::noUnapprovedHighImpact);
        registry.register("recommendation_consistent_with_evidence", ArtifactCheckers::recommendationConsistentWithEvidence);
        registry.register("schema_matches_expectation", ArtifactCheckers::schemaMatchesExpectation);
        registry.register("setup_instructions_present", ArtifactCheckers::setupInstructionsPresent);
        registry.register("tests_executed", ArtifactCheckers::testsExecuted);
        registry.register("acceptance_criteria_covered", ArtifactCheckers::acceptanceCriteriaCovered);
    }

    /** Expects {@code field} to resolve to a {@code List<Map>}; every item must
     *  contain {@code item_field} as a non-blank entry. */
    private static CheckResult allItemsHaveField(Run run, NodeSpec node, Map<String, Object> params) {
        String field = (String) params.get("field");
        String itemField = (String) params.get("item_field");
        List<?> items = asList(RunFieldResolver.resolve(run, field));

        for (Object item : items) {
            Map<?, ?> map = item instanceof Map<?, ?> m ? m : Map.of();
            Object value = map.get(itemField);
            if (isBlankOrNull(value)) {
                Object identity = map.containsKey("id") ? map.get("id") : item;
                return CheckResult.fail("Item '" + identity + "' in '" + field
                        + "' has no '" + itemField + "'.");
            }
        }
        return CheckResult.pass();
    }

    /**
     * Expects {@code field} to name an artifact whose content has an
     * {@code ambiguities} list; each ambiguity must have {@code resolution},
     * {@code assumption} and {@code rationale} all non-blank, OR
     * {@code escalated_to_human: true}.
     *
     * <p>This is the check that makes {@code scenarios/ambiguous.md} real: it MUST
     * be able to fail, or that scenario is theatre.
     */
    private static CheckResult ambiguitiesResolvedOrEscalated(Run run, NodeSpec node, Map<String, Object> params) {
        String field = (String) params.get("field");
        Object reportContent = RunFieldResolver.resolve(run, field);
        List<?> ambiguities = asList(reportContent instanceof Map<?, ?> m ? m.get("ambiguities") : null);

        for (Object item : ambiguities) {
            Map<?, ?> ambiguity = item instanceof Map<?, ?> m ? m : Map.of();
            boolean escalated = Boolean.TRUE.equals(ambiguity.get("escalated_to_human"));
            boolean resolved = !isBlankOrNull(ambiguity.get("resolution"))
                    && !isBlankOrNull(ambiguity.get("assumption"))
                    && !isBlankOrNull(ambiguity.get("rationale"));
            if (!escalated && !resolved) {
                Object id = ambiguity.containsKey("id") ? ambiguity.get("id") : "?";
                return CheckResult.fail("Ambiguity '" + id
                        + "' was neither resolved (resolution+assumption+rationale) nor escalated to a human.");
            }
        }
        return CheckResult.pass();
    }

    /** Expects {@code field} to resolve to a list; fails if non-empty when
     *  {@code expect_empty} is true. */
    private static CheckResult allTestsPass(Run run, NodeSpec node, Map<String, Object> params) {
        String field = (String) params.get("field");
        boolean expectEmpty = Boolean.TRUE.equals(params.getOrDefault("expect_empty", true));
        List<?> failures = asList(RunFieldResolver.resolve(run, field));

        if (expectEmpty && !failures.isEmpty()) {
            return CheckResult.fail("Test failures present: " + failures);
        }
        return CheckResult.pass();
    }

    /** Expects {@code field} to resolve to a number; fails if below {@code min}. */
    private static CheckResult coverageThreshold(Run run, NodeSpec node, Map<String, Object> params) {
        String field = (String) params.get("field");
        double min = ((Number) params.get("min")).doubleValue();
        Object value = RunFieldResolver.resolve(run, field);

        if (!(value instanceof Number n) || n.doubleValue() < min) {
            return CheckResult.fail("Coverage " + value + "% is below the declared " + min + "% threshold.");
        }
        return CheckResult.pass();
    }

    /** Expects {@code field} to be present and, if {@code allowed_values} is given,
     *  its value to be one of them. */
    @SuppressWarnings("unchecked")
    private static CheckResult fieldPresent(Run run, NodeSpec node, Map<String, Object> params) {
        String field = (String) params.get("field");
        Object value = RunFieldResolver.resolve(run, field);
        if (value == null) {
            return CheckResult.fail("Field '" + field + "' is not present.");
        }
        Collection<Object> allowedValues = (Collection<Object>) params.get("allowed_values");
        if (allowedValues != null && !allowedValues.contains(value)) {
            return CheckResult.fail("Field '" + field + "' has value '" + value + "', not one of " + allowedValues + ".");
        }
        return CheckResult.pass();
    }

    /**
     * Expects {@code field} to name the {@code high_impact_actions} list on a
     * design-shaped artifact that also carries a sibling {@code requires_schema_change}
     * boolean. If that sibling is true, {@code field} must be non-empty — an action
     * meeting a DEC-0006 criterion may be proposed, but never concealed.
     */
    private static CheckResult highImpactActionsFlagged(Run run, NodeSpec node, Map<String, Object> params) {
        String field = (String) params.get("field");
        String artifactKey = field.split("\\.")[0];
        Object requiresSchemaChange = RunFieldResolver.resolve(run, artifactKey + ".requires_schema_change");
        List<?> flagged = asList(RunFieldResolver.resolve(run, field));

        if (Boolean.TRUE.equals(requiresSchemaChange) && flagged.isEmpty()) {
            return CheckResult.fail("A schema change was proposed but no high-impact action was flagged in '" + field + "'.");
        }
        return CheckResult.pass();
    }

    /** Expects {@code docs} to name a documentation-shaped artifact with an
     *  {@code endpoints} list of path strings, and {@code schema} an OpenAPI-shaped
     *  artifact with a {@code paths} map; every documented path must exist in the
     *  schema. */
    @SuppressWarnings("unchecked")
    private static CheckResult noContradictions(Run run, NodeSpec node, Map<String, Object> params) {
        String docsKey = (String) params.get("docs");
        String schemaKey = (String) params.get("schema");
        List<?> documentedEndpoints = asList(RunFieldResolver.resolve(run, docsKey + ".endpoints"));
        Map<String, Object> schemaPaths = (Map<String, Object>) RunFieldResolver.resolve(run, schemaKey + ".paths");
        Map<String, Object> paths = schemaPaths == null ? Map.of() : schemaPaths;

        for (Object endpoint : documentedEndpoints) {
            if (!paths.containsKey(String.valueOf(endpoint))) {
                return CheckResult.fail("Documentation describes endpoint '" + endpoint
                        + "', which is not declared in '" + schemaKey + "'.");
            }
        }
        return CheckResult.pass();
    }

    /**
     * Expects {@code compare_to} to be a dotted path (e.g.
     * {@code previous_run.test_suite}) resolving to a {@code {test_count,
     * assertion_count}} baseline, staged as a pseudo-artifact named by the path's
     * first segment. Compares against the current run's {@code test_suite}
     * artifact. No baseline present (first run) passes trivially.
     */
    private static CheckResult noTestWeakening(Run run, NodeSpec node, Map<String, Object> params) {
        String compareTo = (String) params.get("compare_to");
        Object baselineObj = RunFieldResolver.resolve(run, compareTo);
        if (!(baselineObj instanceof Map<?, ?> baseline)) {
            return CheckResult.pass(); // no prior run to compare against
        }

        Object currentObj = run.artifacts().get("test_suite") != null ? run.artifacts().get("test_suite").content() : null;
        Map<?, ?> current = currentObj instanceof Map<?, ?> m ? m : Map.of();

        long baselineTests = numberOf(baseline.get("test_count"));
        long currentTests = numberOf(current.get("test_count"));
        long baselineAssertions = numberOf(baseline.get("assertion_count"));
        long currentAssertions = numberOf(current.get("assertion_count"));

        if (currentTests < baselineTests || currentAssertions < baselineAssertions) {
            return CheckResult.fail("Tests were weakened relative to the previous run: "
                    + currentTests + "/" + currentAssertions + " (tests/assertions) vs previous "
                    + baselineTests + "/" + baselineAssertions + ".");
        }
        return CheckResult.pass();
    }

    /** Passes unless a {@code migration_applied} artifact exists with no
     *  corresponding granted approval for {@code apply_migration} on record — i.e. a
     *  schema change that happened outside its gate. */
    private static CheckResult noUnapprovedHighImpact(Run run, NodeSpec node, Map<String, Object> params) {
        if (!run.artifacts().containsKey("migration_applied")) {
            return CheckResult.pass();
        }
        boolean approved = run.approvals().stream()
                .anyMatch(a -> "apply_migration".equals(a.nodeId()) && a.permitsContinuation());
        if (!approved) {
            return CheckResult.fail("A migration was applied with no recorded, granted approval for apply_migration.");
        }
        return CheckResult.pass();
    }

    /** Expects {@code recommendation} to resolve to go/no_go/go_with_conditions and
     *  each entry in {@code evidence} to name an artifact; a {@code "go"} recommendation
     *  is inconsistent with any evidence artifact carrying a non-empty {@code failures}
     *  list. */
    @SuppressWarnings("unchecked")
    private static CheckResult recommendationConsistentWithEvidence(Run run, NodeSpec node, Map<String, Object> params) {
        String recommendationField = (String) params.get("recommendation");
        Object recommendation = RunFieldResolver.resolve(run, recommendationField);
        if (!"go".equals(recommendation)) {
            return CheckResult.pass(); // no_go / go_with_conditions never contradicts caution
        }

        for (Object evidenceRef : (Collection<Object>) params.getOrDefault("evidence", List.of())) {
            Object failures = RunFieldResolver.resolve(run, evidenceRef + ".failures");
            if (failures instanceof Collection<?> c && !c.isEmpty()) {
                return CheckResult.fail("Recommendation 'go' contradicts evidence: '" + evidenceRef
                        + "' has failures: " + failures);
            }
        }
        return CheckResult.pass();
    }

    /** Expects {@code field} and {@code expected} to both be dotted paths; the
     *  resolved values must be structurally equal. */
    private static CheckResult schemaMatchesExpectation(Run run, NodeSpec node, Map<String, Object> params) {
        String field = (String) params.get("field");
        String expectedField = (String) params.get("expected");
        Object actual = RunFieldResolver.resolve(run, field);
        Object expected = RunFieldResolver.resolve(run, expectedField);

        if (!Objects.equals(actual, expected)) {
            return CheckResult.fail("Post-migration schema does not match the plan.\n  actual:   "
                    + actual + "\n  expected: " + expected);
        }
        return CheckResult.pass();
    }

    /** Expects {@code field} to resolve to a non-blank string. */
    private static CheckResult setupInstructionsPresent(Run run, NodeSpec node, Map<String, Object> params) {
        String field = (String) params.get("field");
        Object value = RunFieldResolver.resolve(run, field);
        if (isBlankOrNull(value)) {
            return CheckResult.fail("No setup instructions produced ('" + field + "' is empty).");
        }
        return CheckResult.pass();
    }

    /** Expects {@code field} to name a test-results-shaped artifact with a
     *  {@code total} count greater than zero. */
    private static CheckResult testsExecuted(Run run, NodeSpec node, Map<String, Object> params) {
        String field = (String) params.get("field");
        Object results = RunFieldResolver.resolve(run, field);
        long total = results instanceof Map<?, ?> m ? numberOf(m.get("total")) : 0;
        if (total <= 0) {
            return CheckResult.fail("Test suite did not actually run (no test_results.total).");
        }
        return CheckResult.pass();
    }

    /** Expects {@code criteria} to resolve to a list of {@code {id: ...}} maps and
     *  {@code results} to name an artifact with a {@code covered_acceptance_criteria}
     *  list of ids; every criterion id must appear there. */
    private static CheckResult acceptanceCriteriaCovered(Run run, NodeSpec node, Map<String, Object> params) {
        String criteriaField = (String) params.get("criteria");
        String resultsKey = (String) params.get("results");
        List<?> criteria = asList(RunFieldResolver.resolve(run, criteriaField));
        List<?> covered = asList(RunFieldResolver.resolve(run, resultsKey + ".covered_acceptance_criteria"));

        for (Object item : criteria) {
            Object id = item instanceof Map<?, ?> m ? m.get("id") : item;
            if (!covered.contains(id)) {
                return CheckResult.fail("Acceptance criterion '" + id + "' has no corresponding test.");
            }
        }
        return CheckResult.pass();
    }

    // ---- helpers ------------------------------------------------------------------

    private static List<?> asList(Object o) {
        return o instanceof List<?> l ? l : List.of();
    }

    private static boolean isBlankOrNull(Object value) {
        if (value == null) return true;
        if (value instanceof String s) return s.isBlank();
        return false;
    }

    private static long numberOf(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }
}
