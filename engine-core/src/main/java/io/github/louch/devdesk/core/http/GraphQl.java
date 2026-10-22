package io.github.louch.devdesk.core.http;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * GraphQL over HTTP: one POST whose JSON body holds the query and its variables. Also reads a server's schema (the
 * standard introspection query) into a list of operations, each with a ready-to-send example query.
 */
public final class GraphQl {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** Nested object fields are followed this deep in an example; deeper ones are left out. */
    private static final int EXAMPLE_DEPTH = 2;

    /** The standard question every GraphQL server answers (unless introspection was turned off). */
    public static final String INTROSPECTION = """
            query DevDeskSchema {
              __schema {
                queryType { name }
                mutationType { name }
                types {
                  kind name
                  fields { name args { name type { ...T } } type { ...T } }
                }
              }
            }
            fragment T on __Type { kind name ofType { kind name ofType { kind name ofType { kind name } } } }
            """;

    private GraphQl() {}

    /**
     * {@code {"query": ..., "variables": {...}}}. Blank variables are left out.
     *
     * @throws IllegalArgumentException when the variables aren't a JSON object
     */
    public static String payload(String query, String variables) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("query", query);
        if (!variables.isBlank()) {
            JsonNode v;
            try {
                v = JSON.readTree(variables);
            } catch (JacksonException e) {
                throw new IllegalArgumentException(
                        "the GraphQL variables aren't valid JSON: " + e.getOriginalMessage());
            }
            if (v == null || !v.isObject()) {
                throw new IllegalArgumentException("the GraphQL variables must be a JSON object, like {\"id\": 1}");
            }
            m.put("variables", v);
        }
        return JSON.writeValueAsString(m);
    }

    /** One query or mutation the server offers. */
    public record Operation(String kind, String name, List<String> args, String returns, String example) {}

    /**
     * The operations in an introspection answer, queries first.
     *
     * @throws IllegalArgumentException when the answer isn't a schema (introspection off, or not GraphQL)
     */
    public static List<Operation> operations(String answer) {
        JsonNode schema;
        try {
            schema = JSON.readTree(answer).path("data").path("__schema");
        } catch (JacksonException e) {
            throw new IllegalArgumentException("the answer isn't JSON: is this a GraphQL endpoint?");
        }
        if (schema.isMissingNode()) {
            throw new IllegalArgumentException("no schema in the answer: the server may have introspection turned off"
                    + " (common in production settings)");
        }
        Map<String, JsonNode> types = new HashMap<>();
        for (JsonNode t : schema.path("types")) {
            types.put(t.path("name").asString(""), t);
        }
        List<Operation> out = new ArrayList<>();
        for (String kind : List.of("query", "mutation")) {
            String root = schema.path(kind + "Type").path("name").asString("");
            JsonNode type = types.get(root);
            if (type == null) {
                continue;
            }
            for (JsonNode f : type.path("fields")) {
                List<String> args = new ArrayList<>();
                List<String> declared = new ArrayList<>();
                List<String> passed = new ArrayList<>();
                for (JsonNode a : f.path("args")) {
                    String name = a.path("name").asString();
                    String t = typeName(a.path("type"));
                    args.add(name + ": " + t);
                    declared.add("$" + name + ": " + t);
                    passed.add(name + ": $" + name);
                }
                String name = f.path("name").asString();
                String selection = selection(f.path("type"), types, 1);
                String example = kind + " " + capitalize(name)
                        + (declared.isEmpty() ? "" : "(" + String.join(", ", declared) + ")") + " {\n  " + name
                        + (passed.isEmpty() ? "" : "(" + String.join(", ", passed) + ")") + selection + "\n}";
                out.add(new Operation(kind, name, args, typeName(f.path("type")), example));
            }
        }
        return out;
    }

    /** "[Order!]!" from the nested NON_NULL / LIST wrappers. */
    static String typeName(JsonNode t) {
        return switch (t.path("kind").asString("")) {
            case "NON_NULL" -> typeName(t.path("ofType")) + "!";
            case "LIST" -> "[" + typeName(t.path("ofType")) + "]";
            default -> t.path("name").asString("?");
        };
    }

    /** " { id total user { id name } }": the scalar fields of an object result, nested objects to a small depth. */
    private static String selection(JsonNode type, Map<String, JsonNode> types, int depth) {
        JsonNode named = type;
        while (named.has("ofType")
                && !named.path("ofType").isNull()
                && !named.path("ofType").isMissingNode()) {
            named = named.path("ofType");
        }
        JsonNode t = types.get(named.path("name").asString(""));
        if (t == null || !List.of("OBJECT", "INTERFACE").contains(t.path("kind").asString(""))) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        for (JsonNode f : t.path("fields")) {
            if (!f.path("args").isEmpty()) {
                continue; // fields that need arguments are left for the user to add
            }
            String sub = selection(f.path("type"), types, depth + 1);
            JsonNode inner = f.path("type");
            while (inner.has("ofType")
                    && !inner.path("ofType").isNull()
                    && !inner.path("ofType").isMissingNode()) {
                inner = inner.path("ofType");
            }
            boolean object = Optional.ofNullable(types.get(inner.path("name").asString("")))
                    .map(x -> List.of("OBJECT", "INTERFACE")
                            .contains(x.path("kind").asString("")))
                    .orElse(false);
            if (!object) {
                parts.add(f.path("name").asString());
            } else if (depth < EXAMPLE_DEPTH && !sub.isEmpty()) {
                parts.add(f.path("name").asString() + sub);
            }
        }
        return parts.isEmpty() ? "" : " { " + String.join(" ", parts) + " }";
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
