package io.github.louch.devdesk.core.http;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The one JSON shape of a request, its checks and its captures: the same in the saved-requests file and in the
 * engine's API, so the two can't drift apart. Reading validates (it's a boundary): anything malformed is empty.
 *
 * <pre>{"method", "url", "headers": [{"name", "value"}], "bodyType": "none|text|form|multipart", "body": "...",
 *  "form": [{"name", "value", "file"}], "auth": {"type": "none|bearer|basic", "token", "user", "password"}}</pre>
 */
public final class HttpCallJson {

    private static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS");

    private HttpCallJson() {}

    public static Map<String, Object> write(HttpCall c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("method", c.method());
        m.put("url", c.url());
        m.put(
                "headers",
                c.headers().stream()
                        .map(h -> pair("name", h.name(), "value", h.value()))
                        .toList());
        switch (c.body()) {
            case HttpCall.Body.None n -> m.put("bodyType", "none");
            case HttpCall.Body.Text t -> m.put("bodyType", "text");
            case HttpCall.Body.Form f -> m.put("bodyType", "form");
            case HttpCall.Body.Multipart p -> m.put("bodyType", "multipart");
            case HttpCall.Body.GraphQl g -> m.put("bodyType", "graphql");
        }
        m.put(
                "body",
                c.body() instanceof HttpCall.Body.GraphQl g
                        ? g.query()
                        : c.text().orElse(null));
        if (c.body() instanceof HttpCall.Body.GraphQl g) {
            m.put("variables", g.variables());
        }
        List<HttpCall.Field> fields = switch (c.body()) {
            case HttpCall.Body.Form f -> f.fields();
            case HttpCall.Body.Multipart p -> p.fields();
            default -> List.of();
        };
        m.put(
                "form",
                fields.stream()
                        .map(f -> {
                            Map<String, Object> x = pair("name", f.name(), "value", f.value());
                            x.put("file", f.file());
                            return x;
                        })
                        .toList());
        m.put(
                "auth",
                switch (c.auth()) {
                    case HttpCall.Auth.None n -> Map.of("type", "none");
                    case HttpCall.Auth.Bearer b -> Map.of("type", "bearer", "token", b.token());
                    case HttpCall.Auth.Basic b -> Map.of("type", "basic", "user", b.user(), "password", b.password());
                });
        return m;
    }

    public static Optional<HttpCall> read(Map<?, ?> b) {
        if (!(b.get("method") instanceof String method)
                || !METHODS.contains(method.toUpperCase(Locale.ROOT))
                || !(b.get("url") instanceof String url)) {
            return Optional.empty();
        }
        List<HttpCall.Header> headers = new ArrayList<>();
        if (b.get("headers") instanceof List<?> list) {
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> h)
                        || !(h.get("name") instanceof String name)
                        || !(h.get("value") instanceof String value)) {
                    return Optional.empty();
                }
                if (!name.isBlank()) {
                    headers.add(new HttpCall.Header(name.strip(), value));
                }
            }
        }
        Optional<String> text = b.get("body") instanceof String s && !s.isEmpty() ? Optional.of(s) : Optional.empty();
        List<HttpCall.Field> fields = new ArrayList<>();
        if (b.get("form") instanceof List<?> list) {
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> f) || !(f.get("name") instanceof String name)) {
                    return Optional.empty();
                }
                if (!name.isBlank()) {
                    String value = f.get("value") instanceof String v ? v : "";
                    fields.add(new HttpCall.Field(name.strip(), value, Boolean.TRUE.equals(f.get("file"))));
                }
            }
        }
        // Files written before body types existed have only "body": text when there is one.
        String type = b.get("bodyType") instanceof String t ? t : text.isPresent() ? "text" : "none";
        HttpCall.Body body = switch (type) {
            case "text" -> text.<HttpCall.Body>map(HttpCall.Body.Text::new).orElse(new HttpCall.Body.None());
            case "form" -> new HttpCall.Body.Form(fields);
            case "multipart" -> new HttpCall.Body.Multipart(fields);
            case "graphql" ->
                new HttpCall.Body.GraphQl(text.orElse(""), b.get("variables") instanceof String v ? v : "");
            default -> new HttpCall.Body.None();
        };
        HttpCall.Auth auth = new HttpCall.Auth.None();
        if (b.get("auth") instanceof Map<?, ?> a) {
            String token = a.get("token") instanceof String s ? s : "";
            String user = a.get("user") instanceof String s ? s : "";
            String password = a.get("password") instanceof String s ? s : "";
            auth = switch (a.get("type") instanceof String s ? s : "none") {
                case "bearer" -> new HttpCall.Auth.Bearer(token);
                case "basic" -> new HttpCall.Auth.Basic(user, password);
                default -> new HttpCall.Auth.None();
            };
        }
        return Optional.of(new HttpCall(method.toUpperCase(Locale.ROOT), url.strip(), headers, body, auth));
    }

    public static List<Map<String, Object>> writeChecks(List<ResponseRules.Check> checks) {
        return checks.stream()
                .map(c -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("part", c.part().name().toLowerCase(Locale.ROOT));
                    m.put("path", c.path());
                    m.put("op", c.op().name().toLowerCase(Locale.ROOT));
                    m.put("expected", c.expected());
                    return m;
                })
                .toList();
    }

    public static List<Map<String, Object>> writeCaptures(List<ResponseRules.Capture> captures) {
        return captures.stream()
                .map(c -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("variable", c.variable());
                    m.put("part", c.part().name().toLowerCase(Locale.ROOT));
                    m.put("path", c.path());
                    return m;
                })
                .toList();
    }

    /** Checks that don't parse are left out (a typo must not lose the request). */
    public static List<ResponseRules.Check> readChecks(Object o) {
        List<ResponseRules.Check> out = new ArrayList<>();
        if (o instanceof List<?> list) {
            for (Object x : list) {
                if (x instanceof Map<?, ?> m) {
                    Optional<ResponseRules.Part> part = part(m.get("part"));
                    Optional<ResponseRules.Op> op = op(m.get("op"));
                    if (part.isPresent() && op.isPresent()) {
                        out.add(new ResponseRules.Check(
                                part.get(), text(m.get("path")), op.get(), text(m.get("expected"))));
                    }
                }
            }
        }
        return out;
    }

    public static List<ResponseRules.Capture> readCaptures(Object o) {
        List<ResponseRules.Capture> out = new ArrayList<>();
        if (o instanceof List<?> list) {
            for (Object x : list) {
                if (x instanceof Map<?, ?> m
                        && m.get("variable") instanceof String v
                        && v.matches("[A-Za-z0-9_.-]+")
                        && part(m.get("part")).isPresent()) {
                    out.add(new ResponseRules.Capture(v, part(m.get("part")).get(), text(m.get("path"))));
                }
            }
        }
        return out;
    }

    private static Optional<ResponseRules.Part> part(Object o) {
        try {
            return o instanceof String s
                    ? Optional.of(ResponseRules.Part.valueOf(s.toUpperCase(Locale.ROOT)))
                    : Optional.empty();
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static Optional<ResponseRules.Op> op(Object o) {
        try {
            return o instanceof String s
                    ? Optional.of(
                            ResponseRules.Op.valueOf(s.toUpperCase(Locale.ROOT).replace('-', '_')))
                    : Optional.empty();
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static String text(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static Map<String, Object> pair(String k1, Object v1, String k2, Object v2) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(k1, v1);
        m.put(k2, v2);
        return m;
    }
}
