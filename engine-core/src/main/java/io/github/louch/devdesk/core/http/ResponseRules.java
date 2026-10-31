package io.github.louch.devdesk.core.http;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * What to do with an answer: checks ("status is 200", "json data.id exists") and captures ("put json access_token in
 * {{token}}", how a log-in request hands its token to the next ones). Both read the same parts of an answer.
 */
public final class ResponseRules {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private ResponseRules() {}

    /** Which part of the answer a rule reads. */
    public enum Part {
        STATUS,
        /** A header, by name (any case). */
        HEADER,
        /** A value inside a JSON body, by path: {@code data.items[0].id}. */
        JSON,
        /** The whole body as text. */
        BODY,
        /** How long the answer took, in milliseconds. */
        TIME
    }

    /** How a check compares. */
    public enum Op {
        EQUALS,
        NOT_EQUALS,
        CONTAINS,
        EXISTS,
        LESS,
        GREATER
    }

    /** "status equals 200", "json data.id exists", "time less 500". {@code path} is used by HEADER and JSON. */
    public record Check(Part part, String path, Op op, String expected) {}

    /** "set {{token}} from json access_token". */
    public record Capture(String variable, Part part, String path) {}

    /** @param actual what the answer had, for showing next to the check ("404", "missing") */
    public record Result(Check check, boolean passed, String actual) {}

    /** The value a rule reads from the answer; empty when it isn't there (no such header, not JSON, no such path). */
    public static Optional<String> read(HttpSender.Outcome.Answered a, Part part, String path) {
        return switch (part) {
            case STATUS -> Optional.of(String.valueOf(a.status()));
            case TIME -> Optional.of(String.valueOf(a.took().toMillis()));
            case BODY -> Optional.of(a.body());
            case HEADER ->
                a.headers().stream()
                        .filter(h -> h.name().equalsIgnoreCase(path.strip()))
                        .map(HttpCall.Header::value)
                        .findFirst();
            case JSON -> json(a.body(), path);
        };
    }

    /** Runs one check on the answer. The actual value is cut at 200 characters, and is "missing" when not found. */
    public static Result check(HttpSender.Outcome.Answered a, Check c) {
        Optional<String> value = read(a, c.part(), c.path());
        String actual =
                value.map(v -> v.length() > 200 ? v.substring(0, 200) + "…" : v).orElse("missing");
        String expected = c.expected() == null ? "" : c.expected();
        boolean passed = switch (c.op()) {
            case EXISTS -> value.isPresent();
            case EQUALS -> value.isPresent() && value.get().equals(expected);
            case NOT_EQUALS -> value.isEmpty() || !value.get().equals(expected);
            case CONTAINS -> value.isPresent() && value.get().contains(expected);
            case LESS, GREATER -> {
                Optional<Double> left = number(value);
                Optional<Double> right = number(Optional.of(expected));
                yield left.isPresent()
                        && right.isPresent()
                        && (c.op() == Op.LESS ? left.get() < right.get() : left.get() > right.get());
            }
        };
        return new Result(c, passed, actual);
    }

    /** Runs every check on the answer, in order. */
    public static List<Result> checkAll(HttpSender.Outcome.Answered a, List<Check> checks) {
        return checks.stream().map(c -> check(a, c)).toList();
    }

    /** "status equals 200": how a check reads in a list of results. */
    public static String describe(Check c) {
        String what = switch (c.part()) {
            case STATUS -> "status";
            case TIME -> "time (ms)";
            case BODY -> "body";
            case HEADER -> "header " + c.path();
            case JSON -> "json " + c.path();
        };
        String op = c.op().name().toLowerCase(Locale.ROOT).replace('_', ' ');
        return c.op() == Op.EXISTS ? what + " exists" : what + " " + op + " " + c.expected();
    }

    /**
     * {@code data.items[0].id} (a leading {@code $.} is allowed). Strings come back without quotes; numbers,
     * booleans as written; objects and arrays as JSON text.
     */
    static Optional<String> json(String body, String path) {
        JsonNode node;
        try {
            node = JSON.readTree(body);
        } catch (JacksonException e) {
            return Optional.empty();
        }
        String p = path.strip().replaceFirst("^\\$\\.?", "");
        if (!p.isEmpty()) {
            for (String step : p.replace("[", ".[").split("\\.")) {
                if (step.isEmpty()) {
                    continue;
                }
                if (node == null) {
                    return Optional.empty();
                }
                if (step.startsWith("[") && step.endsWith("]")) {
                    String index = step.substring(1, step.length() - 1);
                    node = index.matches("\\d+") ? node.get(Integer.parseInt(index)) : null;
                } else {
                    node = node.get(step);
                }
            }
        }
        if (node == null || node.isMissingNode()) {
            return Optional.empty();
        }
        return Optional.of(node.isString() ? node.asString() : node.toString());
    }

    private static Optional<Double> number(Optional<String> s) {
        try {
            return s.map(String::strip).map(Double::parseDouble);
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }
}
