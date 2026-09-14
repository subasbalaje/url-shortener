package com.sdlc.orchestrator.gates;

import com.sdlc.orchestrator.model.Artifact;
import com.sdlc.orchestrator.model.NodeSpec;
import com.sdlc.orchestrator.model.Run;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SourceCodeCheckersTest {

    private CheckerRegistry registry;
    private NodeSpec node;

    @BeforeEach
    void setUp() {
        registry = new CheckerRegistry();
        SourceCodeCheckers.registerDefaults(registry);
        node = NodeSpec.builder("implementation", "Implementation", "implementation_agent").build();
    }

    private void write(Path dir, String relativePath, String content) throws IOException {
        Path file = dir.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    // ---- code_imports_cleanly ----------------------------------------------------

    @Test
    void codeImportsCleanlyPassesForValidJava(@TempDir Path dir) throws IOException {
        write(dir, "com/example/Hello.java", """
                package com.example;
                public class Hello {
                    public String greet() { return "hi"; }
                }
                """);
        Run run = new Run("r", "g", "greenfield", "req");

        CheckResult result = registry.get("code_imports_cleanly").check(run, node, Map.of("target", dir.toString()));
        assertThat(result.passed()).isTrue();
    }

    @Test
    void codeImportsCleanlyFailsForBrokenJavaWithCompilerOutput(@TempDir Path dir) throws IOException {
        write(dir, "com/example/Broken.java", """
                package com.example;
                public class Broken {
                    public String greet() { return "hi" // missing semicolon and brace
                }
                """);
        Run run = new Run("r", "g", "greenfield", "req");

        CheckResult result = registry.get("code_imports_cleanly").check(run, node, Map.of("target", dir.toString()));
        assertThat(result.passed()).isFalse();
        assertThat(result.message()).isNotBlank();
    }

    @Test
    void codeImportsCleanlyFailsWhenNoJavaFilesExistYet(@TempDir Path dir) {
        // An empty source tree is not "clean" — there is nothing to import, which
        // means the implementation node hasn't actually produced anything yet.
        Run run = new Run("r", "g", "greenfield", "req");
        CheckResult result = registry.get("code_imports_cleanly").check(run, node, Map.of("target", dir.toString()));
        assertThat(result.passed()).isFalse();
    }

    // ---- no_secrets_in_source -----------------------------------------------------

    @Test
    void noSecretsInSourcePassesForCleanCode(@TempDir Path dir) throws IOException {
        write(dir, "com/example/Config.java", """
                package com.example;
                public class Config {
                    private final String apiKeyEnvVarName = "API_KEY";
                }
                """);
        Run run = new Run("r", "g", "greenfield", "req");

        CheckResult result = registry.get("no_secrets_in_source").check(run, node, Map.of("target", dir.toString()));
        assertThat(result.passed()).isTrue();
    }

    @Test
    void noSecretsInSourceFailsOnAHardcodedPassword(@TempDir Path dir) throws IOException {
        write(dir, "com/example/Config.java", """
                package com.example;
                public class Config {
                    private final String password = "hunter2CorrectHorse";
                }
                """);
        Run run = new Run("r", "g", "greenfield", "req");

        CheckResult result = registry.get("no_secrets_in_source").check(run, node, Map.of("target", dir.toString()));
        assertThat(result.passed()).isFalse();
        assertThat(result.message()).contains("Config.java");
    }

    @Test
    void noSecretsInSourceFailsOnAnAwsAccessKey(@TempDir Path dir) throws IOException {
        write(dir, "application.yml", "aws:\n  accessKey: AKIAABCDEFGHIJKLMNOP\n");
        Run run = new Run("r", "g", "greenfield", "req");

        CheckResult result = registry.get("no_secrets_in_source").check(run, node, Map.of("target", dir.toString()));
        assertThat(result.passed()).isFalse();
    }

    // ---- documented_endpoints_exist ------------------------------------------------

    @Test
    void documentedEndpointsExistPassesWhenEveryDocumentedEndpointIsImplemented(@TempDir Path dir) throws IOException {
        write(dir, "com/sdlc/shortener/web/LinkController.java", """
                package com.sdlc.shortener.web;
                class LinkController {
                    @PostMapping("/api/v1/links")
                    void create() {}
                    @GetMapping("/api/v1/links/{code}")
                    void get() {}
                }
                """);
        Run run = new Run("r", "g", "greenfield", "req");
        run.artifacts().put("documentation", new Artifact("documentation", "documentation_agent",
                Map.of("endpoints", List.of("/api/v1/links", "/api/v1/links/{code}")), "", "", 1));

        CheckResult result = registry.get("documented_endpoints_exist")
                .check(run, node, Map.of("docs", "documentation", "source", dir.toString()));
        assertThat(result.passed()).isTrue();
    }

    @Test
    void documentedEndpointsExistFailsWhenDocsDescribeAnUnimplementedEndpoint(@TempDir Path dir) throws IOException {
        write(dir, "com/sdlc/shortener/web/LinkController.java", """
                package com.sdlc.shortener.web;
                class LinkController {
                    @PostMapping("/api/v1/links")
                    void create() {}
                }
                """);
        Run run = new Run("r", "g", "greenfield", "req");
        run.artifacts().put("documentation", new Artifact("documentation", "documentation_agent",
                Map.of("endpoints", List.of("/api/v1/links", "/api/v1/links/{code}/stats")), "", "", 1));

        CheckResult result = registry.get("documented_endpoints_exist")
                .check(run, node, Map.of("docs", "documentation", "source", dir.toString()));
        assertThat(result.passed()).isFalse();
        assertThat(result.message()).contains("/api/v1/links/{code}/stats");
    }

    // ---- api_contract_conformance -----------------------------------------------------

    @Test
    void apiContractConformancePassesWhenImplementationMatchesSchema(@TempDir Path dir) throws IOException {
        write(dir, "com/sdlc/shortener/web/LinkController.java", """
                package com.sdlc.shortener.web;
                class LinkController {
                    @PostMapping("/api/v1/links")
                    void create() {}
                    @DeleteMapping("/api/v1/links/{code}")
                    void disable() {}
                }
                """);
        Run run = new Run("r", "g", "greenfield", "req");
        run.artifacts().put("api_schema", new Artifact("api_schema", "design", Map.of("paths", Map.of(
                "/api/v1/links", Map.of("post", Map.of()),
                "/api/v1/links/{code}", Map.of("delete", Map.of()))), "", "", 1));

        CheckResult result = registry.get("api_contract_conformance")
                .check(run, node, Map.of("schema", "api_schema", "target", dir.toString()));
        assertThat(result.passed()).isTrue();
    }

    @Test
    void apiContractConformanceFailsOnAMethodMismatch(@TempDir Path dir) throws IOException {
        // Reproduces the worked-trace bug: DELETE implemented where the schema
        // declares a different method/shape for that path.
        write(dir, "com/sdlc/shortener/web/LinkController.java", """
                package com.sdlc.shortener.web;
                class LinkController {
                    @GetMapping("/api/v1/links/{code}")
                    void get() {}
                }
                """);
        Run run = new Run("r", "g", "greenfield", "req");
        run.artifacts().put("api_schema", new Artifact("api_schema", "design", Map.of("paths", Map.of(
                "/api/v1/links/{code}", Map.of("delete", Map.of()))), "", "", 1));

        CheckResult result = registry.get("api_contract_conformance")
                .check(run, node, Map.of("schema", "api_schema", "target", dir.toString()));
        assertThat(result.passed()).isFalse();
    }
}
