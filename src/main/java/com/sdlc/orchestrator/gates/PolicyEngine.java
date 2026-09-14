package com.sdlc.orchestrator.gates;

import com.sdlc.orchestrator.model.NodeSpec;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * DEC-0006 classification: which structural DEC-0006 criteria a node meets, and
 * whether its declared {@code requires_human_approval} agrees with that.
 *
 * <p><b>This is a narrower classifier than DEC-0006's full five criteria</b> — see
 * decision log entry DEC-0014, which this class fulfils the "consequences" of:
 * {@link Criterion#SCHEMA_CHANGE} and {@link Criterion#RELEASE_READINESS} are the
 * only two with an unambiguous structural signal on {@link NodeSpec} today.
 * {@code IRREVERSIBLE}, {@code DELETION} and {@code PRODUCTION_SURFACE} are
 * deliberately not modelled as {@link Criterion} values: there is no field on
 * {@code NodeSpec} from which any of them could be structurally derived without
 * guessing, and a classifier that sometimes fires wrongly is worse than one that
 * plainly does not cover a case yet. Extending this enum (and the corresponding
 * structural signal) is the natural next step once such a field exists — e.g. an
 * explicit {@code high_impact_criteria} list on the node model.
 *
 * <p>{@link com.sdlc.orchestrator.graph.GraphValidator}'s check 8 delegates here
 * rather than keeping its own copy of this logic, so the two cannot drift apart.
 */
public final class PolicyEngine {

    public enum Criterion { SCHEMA_CHANGE, RELEASE_READINESS }

    private PolicyEngine() {}

    public static Set<Criterion> classify(NodeSpec node) {
        Set<Criterion> criteria = EnumSet.noneOf(Criterion.class);

        boolean rollbackIsSql = node.rollbackAction() != null && "execute_sql".equals(node.rollbackAction().type());
        if (node.produces().contains("migration_applied") || rollbackIsSql) {
            criteria.add(Criterion.SCHEMA_CHANGE);
        }
        if ("release_readiness_agent".equals(node.agentRole())) {
            criteria.add(Criterion.RELEASE_READINESS);
        }
        return criteria;
    }

    public static boolean isHighImpact(NodeSpec node) {
        return !classify(node).isEmpty();
    }

    /** @return failure messages if a met criterion isn't matched by
     *  {@code requires_human_approval: true}; empty if consistent. */
    public static List<String> validateDeclaration(NodeSpec node) {
        List<String> failures = new ArrayList<>();
        if (node.requiresHumanApproval()) {
            return failures;
        }
        for (Criterion criterion : classify(node)) {
            failures.add("Node '" + node.id() + "' meets the DEC-0006 " + criterion.name().toLowerCase()
                    + " criterion but declares requires_human_approval: false.");
        }
        return failures;
    }
}
