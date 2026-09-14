package com.sdlc.orchestrator.gates;

import com.sdlc.orchestrator.model.Artifact;
import com.sdlc.orchestrator.model.Run;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RunFieldResolverTest {

    private Run runWithRequirementSpec() {
        Run run = new Run("r-1", "sdlc-core", "brownfield", "Build a shortener");
        Map<String, Object> content = Map.of(
                "acceptance_criteria", List.of(Map.of("id", "AC1", "verifiable_by", "integration test")),
                "requires_schema_change", true);
        run.artifacts().put("requirement_spec", new Artifact("requirement_spec", "requirements", content, "", "", 1));
        return run;
    }

    @Test
    void resolvesRawRequestDirectlyOnTheRun() {
        Run run = runWithRequirementSpec();
        assertThat(RunFieldResolver.resolve(run, "raw_request")).isEqualTo("Build a shortener");
    }

    @Test
    void resolvesRunContextMode() {
        Run run = runWithRequirementSpec();
        assertThat(RunFieldResolver.resolve(run, "run_context.mode")).isEqualTo("brownfield");
    }

    @Test
    void resolvesTopLevelArtifactField() {
        Run run = runWithRequirementSpec();
        Object value = RunFieldResolver.resolve(run, "requirement_spec.requires_schema_change");
        assertThat(value).isEqualTo(true);
    }

    @Test
    void resolvesNestedArtifactField() {
        Run run = runWithRequirementSpec();
        Object value = RunFieldResolver.resolve(run, "requirement_spec.acceptance_criteria");
        assertThat(value).isInstanceOf(List.class);
        assertThat((List<?>) value).hasSize(1);
    }

    @Test
    void returnsNullForAMissingArtifact() {
        Run run = runWithRequirementSpec();
        assertThat(RunFieldResolver.resolve(run, "no_such_artifact.field")).isNull();
    }

    @Test
    void returnsNullForAMissingNestedField() {
        Run run = runWithRequirementSpec();
        assertThat(RunFieldResolver.resolve(run, "requirement_spec.no_such_field")).isNull();
    }
}
