package com.sdlc.orchestrator.gates;

import com.sdlc.orchestrator.model.Run;

import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A deliberately tiny, restricted expression evaluator for {@code conditional_required}
 * guards and {@code allow_empty_if} clauses — field lookup, {@code ==}/{@code !=},
 * and literals only.
 *
 * <p><b>This is not a scripting engine, and must never become one.</b> The graph
 * YAML is a data file defining a sandbox (entry/exit gates, retry budgets, approval
 * requirements); wiring in Nashorn, Groovy, or full SpEL evaluation here would let a
 * graph edit execute arbitrary code and escape the very sandbox the graph exists to
 * define. If a future condition genuinely needs more than field-compare-to-literal,
 * that is a new, equally-restricted grammar rule to add here — never a general
 * evaluator swapped in underneath.
 */
public final class ExpressionEvaluator {

    private static final Pattern EXPRESSION =
            Pattern.compile("^\\s*(?<field>[\\w.]+)\\s*(?<op>==|!=)\\s*(?<literal>.+?)\\s*$");

    private ExpressionEvaluator() {}

    public static boolean evaluate(Run run, String expression) {
        Matcher m = EXPRESSION.matcher(expression);
        if (!m.matches()) {
            throw new IllegalArgumentException(
                    "Unsupported expression (only '<field.path> == <literal>' / '!=' are allowed): " + expression);
        }

        Object fieldValue = RunFieldResolver.resolve(run, m.group("field"));
        Object literal = parseLiteral(m.group("literal"));
        boolean equal = Objects.equals(normalise(fieldValue), normalise(literal));

        return "==".equals(m.group("op")) == equal;
    }

    private static Object parseLiteral(String token) {
        if (token.equals("true")) return Boolean.TRUE;
        if (token.equals("false")) return Boolean.FALSE;
        if (token.equals("[]")) return List.of();
        if (token.length() >= 2 && (token.charAt(0) == '\'' || token.charAt(0) == '"')
                && token.charAt(token.length() - 1) == token.charAt(0)) {
            return token.substring(1, token.length() - 1);
        }
        try {
            return Double.parseDouble(token);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Unsupported literal in expression: " + token);
        }
    }

    /** Numbers may come back as Integer/Long/Double depending on origin (Jackson,
     *  SnakeYAML, hand-built test fixtures); compare by value, not by boxed type. */
    private static Object normalise(Object value) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        return value;
    }
}
