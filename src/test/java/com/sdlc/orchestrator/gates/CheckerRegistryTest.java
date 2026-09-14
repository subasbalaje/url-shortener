package com.sdlc.orchestrator.gates;

import com.sdlc.orchestrator.model.Run;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CheckerRegistryTest {

    @Test
    void checkResultPassHasNoMessage() {
        CheckResult result = CheckResult.pass();
        assertThat(result.passed()).isTrue();
        assertThat(result.message()).isEmpty();
    }

    @Test
    void checkResultFailCarriesTheMessage() {
        CheckResult result = CheckResult.fail("No acceptance criteria.");
        assertThat(result.passed()).isFalse();
        assertThat(result.message()).isEqualTo("No acceptance criteria.");
    }

    @Test
    void registersAndDispatchesByType() {
        CheckerRegistry registry = new CheckerRegistry();
        registry.register("always_pass", (run, node, params) -> CheckResult.pass());

        CheckResult result = registry.get("always_pass").check(null, null, Map.of());
        assertThat(result.passed()).isTrue();
    }

    @Test
    void unknownCheckerTypeThrowsRatherThanPassingVacuously() {
        // Fail closed: a gate that silently skips a condition it does not
        // understand is worse than no gate, because it still looks enforced.
        CheckerRegistry registry = new CheckerRegistry();
        assertThatThrownBy(() -> registry.get("no_such_checker_type"))
                .isInstanceOf(UnknownCheckerException.class)
                .hasMessageContaining("no_such_checker_type");
    }
}
