package com.sdlc.orchestrator.gates;

import com.sdlc.orchestrator.model.NodeSpec;
import com.sdlc.orchestrator.model.Run;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The domain-specific checkers that must inspect real files on disk.
 *
 * <p>Kept deliberately separate from {@link ArtifactCheckers}: these are the ones
 * where the "never execute agent-written code in-process" rule
 * (CLAUDE.md / orchestrator/README.md implementation rule 7) actually bites.
 * {@link #codeImportsCleanly} shells out to {@code javac} in a subprocess rather
 * than loading and running the agent's classes inside this JVM — otherwise the
 * thing being governed would run inside its governor.
 *
 * <p><b>Known scope limits</b> (stated rather than silently true): the endpoint
 * scanners use a regex over Spring Mapping annotations, not a real Java/Spring
 * parser — they see {@code @GetMapping("/x")}-shaped literals only, not
 * composed class-level {@code @RequestMapping} prefixes, path variables expressed
 * unusually, or annotations spread across multiple lines in unexpected ways.
 * {@link #apiContractConformance} checks path+method <em>presence</em> in both
 * directions; it does not diff response bodies or status codes (the worked-trace
 * example in {@code docs/example-run.md} §3.4 catches a status-code mismatch,
 * which is out of scope for a regex-based scanner and would need real OpenAPI
 * generation to compare against, not source scanning).
 */
public final class SourceCodeCheckers {

    private static final Pattern MAPPING_ANNOTATION = Pattern.compile(
            "@(Get|Post|Put|Delete|Patch)Mapping\\s*\\(\\s*(?:value\\s*=\\s*)?\"([^\"]+)\"");

    private static final List<Pattern> SECRET_PATTERNS = List.of(
            Pattern.compile("AKIA[0-9A-Z]{16}"), // AWS access key id
            Pattern.compile("(?i)(password|passwd|secret|api[_-]?key)\\s*[:=]\\s*[\"']([^\"']{6,})[\"']"),
            Pattern.compile("-----BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY-----"));

    private SourceCodeCheckers() {}

    public static void registerDefaults(CheckerRegistry registry) {
        registry.register("code_imports_cleanly", SourceCodeCheckers::codeImportsCleanly);
        registry.register("no_secrets_in_source", SourceCodeCheckers::noSecretsInSource);
        registry.register("documented_endpoints_exist", SourceCodeCheckers::documentedEndpointsExist);
        registry.register("api_contract_conformance", SourceCodeCheckers::apiContractConformance);
    }

    /** Compiles every {@code .java} file under {@code target} in a subprocess (never
     *  in-process); the current JVM's classpath is passed through so dependencies
     *  the source references (Spring, etc.) resolve. */
    private static CheckResult codeImportsCleanly(Run run, NodeSpec node, Map<String, Object> params) {
        Path target = Paths.get((String) params.get("target"));
        List<Path> javaFiles = findFiles(target, ".java");
        if (javaFiles.isEmpty()) {
            return CheckResult.fail("No .java files found under '" + target + "'; nothing to import.");
        }

        try {
            Path outDir = Files.createTempDirectory("code-imports-cleanly-");
            String javac = Paths.get(System.getProperty("java.home"), "bin", "javac").toString();

            List<String> command = new ArrayList<>(List.of(
                    javac, "-d", outDir.toString(), "-cp", System.getProperty("java.class.path")));
            for (Path f : javaFiles) {
                command.add(f.toString());
            }

            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes());
            int exitCode = process.waitFor();
            deleteRecursively(outDir);

            if (exitCode != 0) {
                return CheckResult.fail("Source does not import/compile:\n" + output);
            }
            return CheckResult.pass();
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return CheckResult.fail("Could not run the compile-check subprocess: " + e.getMessage());
        }
    }

    /** Regex-scans every file under {@code target} for common secret literal
     *  shapes. A denylist of patterns, not a proof of absence — stated as a
     *  mitigation, matching architecture.md's honesty posture on this class of check. */
    private static CheckResult noSecretsInSource(Run run, NodeSpec node, Map<String, Object> params) {
        Path target = Paths.get((String) params.get("target"));
        for (Path file : findFiles(target, "")) {
            String content;
            try {
                content = Files.readString(file);
            } catch (IOException e) {
                continue; // unreadable (e.g. binary) file: not a text secret leak concern here
            }
            for (Pattern pattern : SECRET_PATTERNS) {
                Matcher m = pattern.matcher(content);
                if (m.find()) {
                    return CheckResult.fail("Possible credential/secret literal in "
                            + target.relativize(file) + ": matched pattern '" + pattern.pattern() + "'.");
                }
            }
        }
        return CheckResult.pass();
    }

    /** Compares a documentation artifact's declared {@code endpoints} (path
     *  strings) against paths scanned from Spring Mapping annotations under
     *  {@code source}; every documented path must be implemented. */
    @SuppressWarnings("unchecked")
    private static CheckResult documentedEndpointsExist(Run run, NodeSpec node, Map<String, Object> params) {
        String docsKey = (String) params.get("docs");
        Path source = Paths.get((String) params.get("source"));

        List<String> documentedEndpoints = (List<String>) RunFieldResolver.resolve(run, docsKey + ".endpoints");
        if (documentedEndpoints == null) {
            documentedEndpoints = List.of();
        }
        Set<String> implementedPaths = scanEndpoints(source).keySet();

        for (String endpoint : documentedEndpoints) {
            if (!implementedPaths.contains(endpoint)) {
                return CheckResult.fail("Documentation describes endpoint '" + endpoint
                        + "', which is absent from the code under '" + source + "'.");
            }
        }
        return CheckResult.pass();
    }

    /** Compares implemented (path, method) pairs scanned from {@code target} against
     *  the {@code paths} map declared in the {@code schema} artifact, in both
     *  directions — an undeclared implementation and an unimplemented declaration
     *  are both a conformance failure. */
    @SuppressWarnings("unchecked")
    private static CheckResult apiContractConformance(Run run, NodeSpec node, Map<String, Object> params) {
        String schemaKey = (String) params.get("schema");
        Path target = Paths.get((String) params.get("target"));

        Map<String, Object> declaredPaths = (Map<String, Object>) RunFieldResolver.resolve(run, schemaKey + ".paths");
        if (declaredPaths == null) {
            declaredPaths = Map.of();
        }
        Map<String, Set<String>> implemented = scanEndpoints(target);

        for (Map.Entry<String, Object> pathEntry : declaredPaths.entrySet()) {
            String path = pathEntry.getKey();
            Set<String> declaredMethods = pathEntry.getValue() instanceof Map<?, ?> m
                    ? m.keySet().stream().map(k -> String.valueOf(k).toUpperCase()).collect(Collectors.toSet())
                    : Set.of();
            Set<String> implementedMethods = implemented.getOrDefault(path, Set.of());
            for (String method : declaredMethods) {
                if (!implementedMethods.contains(method)) {
                    return CheckResult.fail("Declared endpoint " + method + " " + path
                            + " has no matching implementation under '" + target + "'.");
                }
            }
        }
        for (Map.Entry<String, Set<String>> implEntry : implemented.entrySet()) {
            if (!declaredPaths.containsKey(implEntry.getKey())) {
                return CheckResult.fail("Implemented endpoints do not match the declared API schema: "
                        + implEntry.getKey() + " is not declared in '" + schemaKey + "'.");
            }
        }
        return CheckResult.pass();
    }

    /** path -> set of HTTP methods (uppercase), scanned from Spring Mapping
     *  annotations. See the class javadoc for this scanner's stated scope limits. */
    private static Map<String, Set<String>> scanEndpoints(Path sourceDir) {
        Map<String, Set<String>> endpoints = new LinkedHashMap<>();
        for (Path file : findFiles(sourceDir, ".java")) {
            String content;
            try {
                content = Files.readString(file);
            } catch (IOException e) {
                continue;
            }
            Matcher m = MAPPING_ANNOTATION.matcher(content);
            while (m.find()) {
                String method = m.group(1).toUpperCase();
                String path = m.group(2);
                endpoints.computeIfAbsent(path, p -> new LinkedHashSet<>()).add(method);
            }
        }
        return endpoints;
    }

    private static List<Path> findFiles(Path root, String suffix) {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(suffix))
                    .collect(Collectors.toList());
        } catch (IOException e) {
            return List.of();
        }
    }

    private static void deleteRecursively(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted((a, b) -> b.compareTo(a)).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best-effort cleanup of a scratch compile-output directory
                }
            });
        } catch (IOException ignored) {
            // best-effort cleanup
        }
    }
}
