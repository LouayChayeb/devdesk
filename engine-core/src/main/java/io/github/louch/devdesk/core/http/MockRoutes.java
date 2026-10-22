package io.github.louch.devdesk.core.http;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Fake answers for the mock server: "GET /orders/:id answers 200 with this JSON". A frontend can then be built and
 * tested before the real endpoint exists, or while it's broken. Matching and answering are here; the engine's HTTP
 * server only passes requests in.
 */
public final class MockRoutes {

    private MockRoutes() {}

    /**
     * One fake endpoint.
     *
     * @param path "/orders", "/orders/:id" (":name" matches one segment), or ending in "/*" (anything below)
     * @param body may use {@code {{id}}} for a ":id" segment of the path
     * @param delay waits this long before answering, to see how the app copes with a slow API
     */
    public record Route(
            String id,
            String method,
            String path,
            int status,
            List<HttpCall.Header> headers,
            String body,
            Duration delay) {
        public Route {
            headers = List.copyOf(headers);
        }
    }

    /** The route that answers, and what the ":name" segments held. */
    public record Match(Route route, Map<String, String> params) {}

    /** The answer to send. */
    public record Answer(int status, List<HttpCall.Header> headers, String body, Duration delay) {}

    /**
     * The first route (in the user's order) that matches. "ANY" matches every method. A query string is ignored for
     * matching.
     */
    public static Optional<Match> match(List<Route> routes, String method, String target) {
        String path = target.contains("?") ? target.substring(0, target.indexOf('?')) : target;
        for (Route r : routes) {
            if (!r.method().equalsIgnoreCase("ANY") && !r.method().equalsIgnoreCase(method)) {
                continue;
            }
            Optional<Map<String, String>> params = params(r.path(), path);
            if (params.isPresent()) {
                return Optional.of(new Match(r, params.get()));
            }
        }
        return Optional.empty();
    }

    /** What a match answers: the body with its {{name}} placeholders filled from the path. JSON unless told. */
    public static Answer answer(Match m) {
        String body = m.route().body();
        for (Map.Entry<String, String> p : m.params().entrySet()) {
            body = body.replace("{{" + p.getKey() + "}}", p.getValue());
        }
        List<HttpCall.Header> headers = new ArrayList<>(m.route().headers());
        boolean typed = headers.stream().anyMatch(h -> h.name().equalsIgnoreCase("Content-Type"));
        if (!typed && !body.isEmpty()) {
            String b = body.strip();
            headers.add(new HttpCall.Header(
                    "Content-Type",
                    b.startsWith("{") || b.startsWith("[") ? "application/json" : "text/plain; charset=utf-8"));
        }
        return new Answer(m.route().status(), headers, body, m.route().delay());
    }

    /** No route: a 404 that says which routes exist, so a typo in the app is found quickly. */
    public static Answer notFound(List<Route> routes, String method, String target) {
        StringBuilder b = new StringBuilder(
                "{\"error\": \"no mock route for " + method + " " + json(target) + "\", \"routes\": [");
        for (int i = 0; i < routes.size(); i++) {
            b.append(i == 0 ? "" : ", ")
                    .append('"')
                    .append(routes.get(i).method())
                    .append(' ')
                    .append(json(routes.get(i).path()))
                    .append('"');
        }
        b.append("]}");
        return new Answer(
                404, List.of(new HttpCall.Header("Content-Type", "application/json")), b.toString(), Duration.ZERO);
    }

    /**
     * {@code /orders/:id} against {@code /orders/7} gives {id=7}; {@code /files/*} matches anything below /files/.
     * Trailing slashes don't matter.
     */
    static Optional<Map<String, String>> params(String pattern, String path) {
        String[] want = trim(pattern).split("/", -1);
        String[] got = trim(path).split("/", -1);
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i < want.length; i++) {
            if (want[i].equals("*") && i == want.length - 1) {
                return Optional.of(out);
            }
            if (i >= got.length) {
                return Optional.empty();
            }
            if (want[i].startsWith(":") && !got[i].isEmpty()) {
                out.put(want[i].substring(1), got[i]);
            } else if (!want[i].equals(got[i])) {
                return Optional.empty();
            }
        }
        return want.length == got.length ? Optional.of(out) : Optional.empty();
    }

    /** Checks a route the user typed. */
    public static Optional<String> problem(Route r) {
        if (!List.of("ANY", "GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS")
                .contains(r.method().toUpperCase(Locale.ROOT))) {
            return Optional.of("the method must be GET, POST, PUT, PATCH, DELETE, HEAD, OPTIONS or ANY");
        }
        if (!r.path().startsWith("/")) {
            return Optional.of("the path starts with /, like /orders/:id");
        }
        if (r.status() < 100 || r.status() > 599) {
            return Optional.of("the status is a number from 100 to 599");
        }
        if (r.delay().isNegative() || r.delay().compareTo(Duration.ofSeconds(60)) > 0) {
            return Optional.of("the delay is between 0 and 60 seconds");
        }
        return Optional.empty();
    }

    private static String trim(String p) {
        String s = p.startsWith("/") ? p.substring(1) : p;
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static String json(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
