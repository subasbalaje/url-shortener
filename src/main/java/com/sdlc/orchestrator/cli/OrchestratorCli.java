package com.sdlc.orchestrator.cli;

import com.sdlc.orchestrator.agents.StageAgent;
import com.sdlc.orchestrator.exec.GraphExecutor;
import com.sdlc.orchestrator.gates.GateCheckers;
import com.sdlc.orchestrator.gates.GateManager;
import com.sdlc.orchestrator.graph.GraphAlgorithms;
import com.sdlc.orchestrator.graph.GraphLoader;
import com.sdlc.orchestrator.lineage.DecisionLogger;
import com.sdlc.orchestrator.model.GraphSpec;
import com.sdlc.orchestrator.model.NodeSpec;
import com.sdlc.orchestrator.model.Run;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Command-line entrypoint for graph validation and execution. */
public final class OrchestratorCli {

    private OrchestratorCli() {}

    public static void main(String[] args) {
        try {
            int exitCode = execute(args);
            if (exitCode != 0) {
                System.exit(exitCode);
            }
        } catch (Exception e) {
            System.err.println("[error] " + e.getMessage());
            System.exit(1);
        }
    }

    static int execute(String[] args) throws Exception {
        if (args.length == 0 || "help".equals(args[0]) || "--help".equals(args[0])) {
            printUsage();
            return 0;
        }

        return switch (args[0]) {
            case "validate" -> validate(options(args, 1));
            case "run" -> run(options(args, 1));
            case "resume" -> resume(options(args, 1));
            case "status" -> status(options(args, 1));
            case "replan" -> replan(options(args, 1));
            case "metrics" -> metrics(options(args, 1));
            default -> throw new IllegalArgumentException("Unknown command: " + args[0]);
        };
    }

    private static int validate(Map<String, String> options) throws IOException {
        GraphSpec graph = loadGraph(required(options, "graph"));
        List<String> order = GraphAlgorithms.topologicalOrder(graph);
        List<java.util.Set<String>> waves = GraphAlgorithms.parallelFrontiers(graph);
        System.out.printf("[load] schema_version %s - OK%n", graph.schemaVersion());
        System.out.printf("[load] %d nodes, %d edges - node ids unique - OK%n",
                graph.nodes().size(), graph.nodes().stream().mapToInt(n -> n.dependsOn().size()).sum());
        System.out.println("[load] all depends_on resolve - OK");
        System.out.printf("[load] cycle check (Kahn): %d of %d nodes emitted - ACYCLIC - OK%n",
                order.size(), graph.nodes().size());
        System.out.println("[load] graph validation - OK");
        System.out.println("Topological order:");
        for (int i = 0; i < order.size(); i++) {
            System.out.printf("  %d %s%n", i + 1, order.get(i));
        }
        System.out.println("Execution waves:");
        for (int i = 0; i < waves.size(); i++) {
            System.out.printf("  wave %d: %s%n", i, waves.get(i));
        }
        return 0;
    }

    private static int run(Map<String, String> options) throws Exception {
        GraphSpec graph = loadGraph(required(options, "graph"));
        String request = required(options, "request");
        String mode = options.getOrDefault("mode", "greenfield");
        Path runDir = Path.of(options.getOrDefault("runs-dir", "runs"));
        Files.createDirectories(runDir);

        Map<String, StageAgent> agents = deterministicAgents();
        String runId = "r-" + System.currentTimeMillis();
        Path actualRunDir = runDir.resolve(runId);
        GraphExecutor executor = newExecutor(graph, agents, actualRunDir);
        Run result = executor.runGraph(request, mode, runId);
        System.out.printf("Run %s %s%n", result.runId(), result.state());
        System.out.println("Run directory: " + actualRunDir);
        return result.state().name().equals("SAFE_STOPPED") ? 1 : 0;
    }

    private static int resume(Map<String, String> options) throws Exception {
        String runId = required(options, "run-id");
        Path runDir = Path.of(options.getOrDefault("runs-dir", "runs")).resolve(runId);
        GraphSpec graph = loadGraph(options.getOrDefault("graph", "docs/orchestration-graph.yaml"));
        GraphExecutor executor = newExecutor(graph, deterministicAgents(), runDir);
        Run result = executor.resume(runDir.resolve("state.json"), runDir.resolve("approval.json"));
        System.out.printf("Run %s %s%n", result.runId(), result.state());
        return result.state().name().equals("SAFE_STOPPED") ? 1 : 0;
    }

    private static int status(Map<String, String> options) throws IOException {
        Path state = runPath(options, "state.json");
        Run run = Run.load(state);
        System.out.printf("Run %s: %s%n", run.runId(), run.state());
        run.nodes().forEach((id, node) -> System.out.printf("  %s: %s%n", id, node.state()));
        return 0;
    }

    private static int metrics(Map<String, String> options) throws IOException {
        if (options.containsKey("cumulative")) {
            throw new IllegalArgumentException("--cumulative is not implemented yet; provide --run-id");
        }
        Path metrics = runPath(options, "metrics.json");
        System.out.println(Files.readString(metrics));
        return 0;
    }

    private static int replan(Map<String, String> options) throws Exception {
        String runId = required(options, "run-id");
        String artifact = required(options, "changed-artifact");
        String reason = required(options, "reason");
        Path runDir = Path.of(options.getOrDefault("runs-dir", "runs")).resolve(runId);
        GraphSpec graph = loadGraph(options.getOrDefault("graph", "docs/orchestration-graph.yaml"));
        GraphExecutor executor = newExecutor(graph, deterministicAgents(), runDir);
        Run run = executor.loadRun(runDir.resolve("state.json"));
        var current = run.artifacts().get(artifact);
        String oldHash = current == null ? "" : current.contentHash();
        var staled = executor.markStaleFrom(artifact, oldHash, options.getOrDefault("new-hash", "changed"), reason);
        run.save(runDir.resolve("state.json"));
        System.out.println("Staled nodes: " + staled);
        return 0;
    }

    private static GraphExecutor newExecutor(GraphSpec graph, Map<String, StageAgent> agents, Path runDir) {
        return new GraphExecutor(graph, agents, runDir,
                new DecisionLogger(runDir.resolve("lineage.jsonl")),
                GateCheckers.newFullyRegisteredRegistry(), new GateManager(), Map.of());
    }

    private static GraphSpec loadGraph(String path) throws IOException {
        return GraphLoader.load(Path.of(path));
    }

    private static Path runPath(Map<String, String> options, String filename) {
        String runId = required(options, "run-id");
        return Path.of(options.getOrDefault("runs-dir", "runs")).resolve(runId).resolve(filename);
    }

    private static Map<String, String> options(String[] args, int start) {
        Map<String, String> options = new LinkedHashMap<>();
        for (int i = start; i < args.length; i++) {
            String arg = args[i];
            if (!arg.startsWith("--")) {
                throw new IllegalArgumentException("Expected option, got: " + arg);
            }
            String key = arg.substring(2);
            if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                options.put(key, args[++i]);
            } else {
                options.put(key, "true");
            }
        }
        return options;
    }

    private static String required(Map<String, String> options, String name) {
        String value = options.get(name);
        if (value == null || value.isBlank() || "true".equals(value)) {
            throw new IllegalArgumentException("Missing required option --" + name);
        }
        return value;
    }

    private static void printUsage() {
        System.out.println("Usage: java -jar target/orchestrator-cli.jar <command> [options]");
        System.out.println("  validate --graph <path>");
        System.out.println("  run --graph <path> --request <text> --mode <greenfield|brownfield|ambiguous>");
        System.out.println("  resume --run-id <id> [--runs-dir <path>] [--graph <path>]");
        System.out.println("  status --run-id <id> [--runs-dir <path>]");
        System.out.println("  metrics --run-id <id> [--runs-dir <path>]");
        System.out.println("  replan --run-id <id> --changed-artifact <name> --reason <text>");
    }

    private static Map<String, StageAgent> deterministicAgents() {
        Map<String, StageAgent> agents = new LinkedHashMap<>();
        for (String role : List.of("requirements_agent", "design_agent", "implementation_agent",
                "testing_agent", "documentation_agent", "release_readiness_agent")) {
            agents.put(role, (run, node) -> produceArtifacts(run, node));
        }
        return agents;
    }

    private static Map<String, Object> produceArtifacts(Run run, NodeSpec node) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String artifact : node.produces()) {
            result.put(artifact, artifactContent(run, node, artifact));
        }
        return result;
    }

    private static Object artifactContent(Run run, NodeSpec node, String artifact) {
        return switch (artifact) {
            case "requirement_spec" -> Map.of("acceptance_criteria", List.of(Map.of("id", "AC1", "verifiable_by", "test")),
                    "assumptions", List.of(Map.of("id", "A1", "rationale", "CLI deterministic default")));
                case "ambiguity_report" -> Map.of("ambiguities", List.of(Map.of(
                    "id", "AMB1", "resolution", "deterministic default",
                    "assumption", "The CLI uses a deterministic local fixture.",
                    "rationale", "Repeatable execution without external agents.",
                    "escalated_to_human", false)));
            case "design_doc" -> Map.of("components", List.of("api", "storage"), "requires_schema_change", false, "high_impact_actions", List.of());
            case "api_schema" -> Map.of("openapi", "3.1.0", "endpoints", List.of("POST /api/v1/links", "GET /{code}"));
            case "impact_analysis" -> Map.of("items", List.of());
            case "migration_plan" -> Map.of("sql", "", "requires_schema_change", false);
            case "migration_applied" -> Map.of("applied", true);
            case "source_code" -> Map.of("path", "src", "status", "generated");
            case "test_suite" -> Map.of("tests", List.of("smoke"));
            case "test_results" -> Map.of("total", 1, "passed", 1, "failed", 0, "coverage_percent", 100.0,
                    "acceptance_criteria", List.of("AC1"));
            case "documentation" -> Map.of("setup", "mvn test", "endpoints", List.of("POST /api/v1/links"));
            case "readiness_report" -> Map.of("recommendation", "go", "rationale", "Deterministic CLI run", "residual_risks", List.of("stub agents"));
            default -> Map.of("node", node.id(), "artifact", artifact, "request", run.rawRequest());
        };
    }
}