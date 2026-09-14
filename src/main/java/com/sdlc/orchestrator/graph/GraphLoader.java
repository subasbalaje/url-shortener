package com.sdlc.orchestrator.graph;

import com.sdlc.orchestrator.model.Gate;
import com.sdlc.orchestrator.model.GateCondition;
import com.sdlc.orchestrator.model.GateType;
import com.sdlc.orchestrator.model.GraphSpec;
import com.sdlc.orchestrator.model.NodeSpec;
import com.sdlc.orchestrator.model.RePlanTrigger;
import com.sdlc.orchestrator.model.RetryPolicy;
import com.sdlc.orchestrator.model.RollbackAction;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Parses {@code docs/orchestration-graph.yaml} (or any graph in the same schema)
 * into a validated {@link GraphSpec}.
 *
 * <p>Validation is fail-closed and happens here, not left to the caller: a graph
 * that does not validate never becomes a usable {@code GraphSpec}, so there is no
 * code path that runs a single node against a malformed graph.
 *
 * <p><b>Known simplification</b> (documented rather than silently true): unlike the
 * original design note for the Python skeleton, this loader does not reject
 * unrecognised YAML keys. Several keys in the real graph are purely narrative
 * (e.g. {@code retry_rationale}, the various {@code description} fields) and are
 * intentionally not modelled on {@link NodeSpec}; rejecting unknown keys outright
 * would mean maintaining an exhaustive whitelist of narrative-only fields for no
 * validation benefit the nine {@link GraphValidator} checks don't already provide.
 */
public final class GraphLoader {

    private GraphLoader() {}

    public static GraphSpec load(Path path) throws IOException {
        return loadFromString(Files.readString(path, StandardCharsets.UTF_8));
    }

    public static GraphSpec loadFromString(String yamlText) {
        Yaml yaml = new Yaml();
        Map<String, Object> root = yaml.load(yamlText);
        GraphSpec spec = parseGraph(root);
        GraphValidator.validate(spec);
        return spec;
    }

    @SuppressWarnings("unchecked")
    private static GraphSpec parseGraph(Map<String, Object> root) {
        String schemaVersion = stringOf(root.get("schema_version"), "");
        Map<String, Object> graphBlock = mapOf(root.get("graph"));

        String id = stringOf(graphBlock.get("id"), "");
        String name = stringOf(graphBlock.get("name"), "");
        String description = stringOf(graphBlock.get("description"), "");

        Map<String, Object> defaultsBlock = mapOf(graphBlock.get("defaults"));
        RetryPolicy graphDefaultRetry = defaultsBlock.containsKey("retry_policy")
                ? parseRetryPolicy(mapOf(defaultsBlock.get("retry_policy")))
                : RetryPolicy.defaults();
        double graphDefaultTimeout = doubleOf(defaultsBlock.get("timeout_seconds"), 600.0);

        Map<String, Map<String, Object>> artifacts = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : mapOf(graphBlock.get("artifacts")).entrySet()) {
            artifacts.put(e.getKey(), mapOf(e.getValue()));
        }

        List<NodeSpec> nodes = new ArrayList<>();
        for (Object nodeObj : listOf(root.get("nodes"))) {
            nodes.add(parseNode(mapOf(nodeObj), graphDefaultRetry, graphDefaultTimeout));
        }

        Map<String, Object> policy = mapOf(root.get("policy"));

        return new GraphSpec(id, name, nodes, artifacts, policy, schemaVersion, description);
    }

    private static NodeSpec parseNode(Map<String, Object> m, RetryPolicy graphDefaultRetry, double graphDefaultTimeout) {
        NodeSpec.Builder builder = NodeSpec.builder(
                stringOf(m.get("id"), ""),
                stringOf(m.get("name"), ""),
                stringOf(m.get("agent_role"), ""));

        builder.dependsOn(stringListOf(m.get("depends_on")));
        builder.canRunParallel(stringListOf(m.get("can_run_parallel")));
        builder.produces(stringListOf(m.get("produces")));

        builder.entryGate(parseGate(m.get("entry_gate"), GateType.ENTRY));
        builder.exitGate(parseGate(m.get("exit_gate"), GateType.EXIT));

        builder.requiresHumanApproval(boolOf(m.get("requires_human_approval"), false));
        if (m.get("human_approval_reason") != null) {
            builder.humanApprovalReason(stringOf(m.get("human_approval_reason"), null));
        }

        Map<String, Object> approvalPayload = mapOf(m.get("approval_payload"));
        builder.approvalPayload(stringListOf(approvalPayload.get("include")));

        if (m.get("condition") != null) {
            builder.condition(mapOf(m.get("condition")));
        }

        builder.retryPolicy(m.containsKey("retry_policy")
                ? parseRetryPolicy(mapOf(m.get("retry_policy")))
                : graphDefaultRetry);

        if (m.get("rollback_action") != null) {
            builder.rollbackAction(parseRollbackAction(mapOf(m.get("rollback_action"))));
        }

        List<RePlanTrigger> triggers = new ArrayList<>();
        for (Object t : listOf(m.get("re_plan_triggers"))) {
            Map<String, Object> tm = mapOf(t);
            triggers.add(new RePlanTrigger(
                    stringOf(tm.get("artifact"), ""),
                    stringOf(tm.get("on_change"), "mark_stale"),
                    stringOf(tm.get("reason"), "")));
        }
        builder.rePlanTriggers(triggers);

        if (m.get("join_policy") != null) {
            builder.joinPolicy(mapOf(m.get("join_policy")));
        }
        if (m.get("on_persistent_failure") != null) {
            builder.onPersistentFailure(mapOf(m.get("on_persistent_failure")));
        }

        builder.timeoutSeconds(doubleOf(m.get("timeout_seconds"), graphDefaultTimeout));
        builder.metricsTags(stringListOf(m.get("metrics_tags")));

        return builder.build();
    }

    private static Gate parseGate(Object raw, GateType type) {
        if (raw == null) {
            return null;
        }
        Map<String, Object> m = mapOf(raw);
        List<GateCondition> conditions = new ArrayList<>();
        for (Object c : listOf(m.get("conditions"))) {
            Map<String, Object> cm = mapOf(c);
            String condType = stringOf(cm.get("type"), "");
            String error = stringOf(cm.get("error"), "");
            Map<String, Object> params = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : cm.entrySet()) {
                if (!e.getKey().equals("type") && !e.getKey().equals("error")) {
                    params.put(e.getKey(), e.getValue());
                }
            }
            conditions.add(new GateCondition(condType, error, params));
        }
        return new Gate(type, conditions, stringOf(m.get("description"), ""));
    }

    private static RetryPolicy parseRetryPolicy(Map<String, Object> m) {
        return new RetryPolicy(
                (int) longOf(m.get("max_attempts"), 3),
                doubleOf(m.get("initial_interval_seconds"), 2.0),
                doubleOf(m.get("backoff_coefficient"), 2.0),
                doubleOf(m.get("max_interval_seconds"), 60.0),
                Set.copyOf(stringListOf(m.get("non_retryable_errors"))));
    }

    private static RollbackAction parseRollbackAction(Map<String, Object> m) {
        Map<String, Object> params = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : m.entrySet()) {
            if (!Set.of("type", "description", "on_rollback_failure").contains(e.getKey())) {
                params.put(e.getKey(), e.getValue());
            }
        }
        return new RollbackAction(
                stringOf(m.get("type"), ""),
                stringOf(m.get("description"), ""),
                params,
                stringOf(m.get("on_rollback_failure"), "safe_stop_and_page"));
    }

    // ---- small, defensive YAML-shape coercion helpers ---------------------------
    // SnakeYAML hands back plain Map/List/String/Number/Boolean; these centralise
    // the null-safety and casting so the parsing methods above stay readable.

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapOf(Object o) {
        return o == null ? Map.of() : (Map<String, Object>) o;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> listOf(Object o) {
        return o == null ? List.of() : (List<Object>) o;
    }

    private static List<String> stringListOf(Object o) {
        List<String> result = new ArrayList<>();
        for (Object item : listOf(o)) {
            result.add(String.valueOf(item));
        }
        return result;
    }

    private static String stringOf(Object o, String fallback) {
        return o == null ? fallback : String.valueOf(o);
    }

    private static boolean boolOf(Object o, boolean fallback) {
        return o instanceof Boolean b ? b : fallback;
    }

    private static double doubleOf(Object o, double fallback) {
        return o instanceof Number n ? n.doubleValue() : fallback;
    }

    private static long longOf(Object o, long fallback) {
        return o instanceof Number n ? n.longValue() : fallback;
    }
}
