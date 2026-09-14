package com.sdlc.orchestrator.gates;

import com.sdlc.orchestrator.model.Artifact;
import com.sdlc.orchestrator.model.Run;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExpressionEvaluatorTest {

    private Run brownfieldRunWithSchemaChange() {
        Run run = new Run("r-1", "sdlc-core", "brownfield", "req");
        run.artifacts().put("design_doc", new Artifact("design_doc", "design",
                Map.of("requires_schema_change", true), "", "", 1));
        run.artifacts().put("ambiguity_report", new Artifact("ambiguity_report", "requirements",
                Map.of("ambiguities", List.of()), "", "", 1));
        return run;
    }

    @Test
    void evaluatesRunContextModeEquality() {
        Run run = brownfieldRunWithSchemaChange();
        assertThat(ExpressionEvaluator.evaluate(run, "run_context.mode == 'brownfield'")).isTrue();
        assertThat(ExpressionEvaluator.evaluate(run, "run_context.mode == 'greenfield'")).isFalse();
    }

    @Test
    void evaluatesBooleanLiteralEquality() {
        Run run = brownfieldRunWithSchemaChange();
        assertThat(ExpressionEvaluator.evaluate(run, "design_doc.requires_schema_change == true")).isTrue();
        assertThat(ExpressionEvaluator.evaluate(run, "design_doc.requires_schema_change == false")).isFalse();
    }

    @Test
    void evaluatesEmptyListLiteralEquality() {
        Run run = brownfieldRunWithSchemaChange();
        assertThat(ExpressionEvaluator.evaluate(run, "ambiguity_report.ambiguities == []")).isTrue();
    }

    @Test
    void evaluatesNotEqualsOperator() {
        Run run = brownfieldRunWithSchemaChange();
        assertThat(ExpressionEvaluator.evaluate(run, "run_context.mode != 'greenfield'")).isTrue();
        assertThat(ExpressionEvaluator.evaluate(run, "run_context.mode != 'brownfield'")).isFalse();
    }

    @Test
    void missingFieldComparesAsNotEqualToAnyLiteral() {
        Run run = brownfieldRunWithSchemaChange();
        assertThat(ExpressionEvaluator.evaluate(run, "no_such_artifact.field == true")).isFalse();
    }

    @Test
    void rejectsAnExpressionWithoutARecognisedOperator() {
        // Fail closed: this is a restricted evaluator, not a scripting engine. An
        // expression it cannot parse must be a loud error, never a silent pass.
        Run run = brownfieldRunWithSchemaChange();
        assertThatThrownBy(() -> ExpressionEvaluator.evaluate(run, "run_context.mode"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
