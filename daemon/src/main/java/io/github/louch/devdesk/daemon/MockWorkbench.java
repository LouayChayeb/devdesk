package io.github.louch.devdesk.daemon;

import io.github.louch.devdesk.core.http.HttpCall;
import io.github.louch.devdesk.core.http.MockBook;
import io.github.louch.devdesk.core.http.MockRoutes;
import io.github.louch.devdesk.core.http.MockServer;
import io.github.louch.devdesk.core.project.ProjectSpec;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.random.RandomGenerator;

/**
 * The API client's mock server: the open project's fake routes, the server serving them, and the requests it got.
 * One mock server at a time; it serves whatever project is open, and stops with the engine.
 */
final class MockWorkbench {

    /** Hits kept for the log; older ones drop off. */
    static final int KEPT = 300;

    private final EngineSession session;
    private final MockServer server;
    private final Function<ProjectSpec, MockBook> books;
    private final RandomGenerator random;
    private final Deque<Map<String, Object>> hits = new ArrayDeque<>();
    private Optional<MockServer.Running> running = Optional.empty();
    private long nextHit = 1;

    MockWorkbench(
            EngineSession session, MockServer server, Function<ProjectSpec, MockBook> books, RandomGenerator random) {
        this.session = session;
        this.server = server;
        this.books = books;
        this.random = random;
    }

    /** Routes, whether the server runs and on which port, and the requests it answered after {@code after}. */
    synchronized EngineSession.Answer overview(long after) {
        Optional<ProjectSpec> p = project();
        if (p.isEmpty()) {
            return noProject();
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(
                "routes",
                books.apply(p.get()).load().stream().map(MockWorkbench::json).toList());
        m.put("port", running.map(MockServer.Running::port).orElse(null));
        m.put("hits", hits.stream().filter(h -> (long) h.get("seq") > after).toList());
        return new EngineSession.Answer(200, m);
    }

    /** Adds a route, or replaces the one with the same id. */
    synchronized EngineSession.Answer save(Map<?, ?> body) {
        Optional<ProjectSpec> p = project();
        if (p.isEmpty()) {
            return noProject();
        }
        Optional<MockRoutes.Route> route = route(body);
        if (route.isEmpty()) {
            return bad("send {\"id\"?, \"method\", \"path\", \"status\", \"headers\"?, \"body\", \"delayMs\"?}");
        }
        Optional<String> problem = MockRoutes.problem(route.get());
        if (problem.isPresent()) {
            return bad(problem.get());
        }
        MockBook book = books.apply(p.get());
        List<MockRoutes.Route> list = new ArrayList<>(book.load());
        int at = indexOf(list, route.get().id());
        if (at >= 0) {
            list.set(at, route.get());
        } else {
            list.add(route.get());
        }
        book.store(list);
        return overview(Long.MAX_VALUE);
    }

    synchronized EngineSession.Answer delete(String id) {
        Optional<ProjectSpec> p = project();
        if (p.isEmpty()) {
            return noProject();
        }
        MockBook book = books.apply(p.get());
        book.store(book.load().stream().filter(r -> !r.id().equals(id)).toList());
        return overview(Long.MAX_VALUE);
    }

    /** {port}: starts serving the open project's routes there (stopping a server already running). */
    synchronized EngineSession.Answer start(Map<?, ?> body) {
        if (!(body.get("port") instanceof Number n) || n.intValue() < 1024 || n.intValue() > 65535) {
            return bad("send {\"port\": 18900} (a port from 1024 to 65535)");
        }
        if (project().isEmpty()) {
            return noProject();
        }
        stop();
        try {
            running = Optional.of(server.start(n.intValue(), this::currentRoutes, this::hit));
        } catch (IllegalStateException e) {
            return new EngineSession.Answer(409, ApiJson.error(e.getMessage()));
        }
        return overview(Long.MAX_VALUE);
    }

    synchronized void stop() {
        running.ifPresent(MockServer.Running::stop);
        running = Optional.empty();
    }

    /** Read on each request, so edits apply at once, and a project switch serves the new project's routes. */
    private List<MockRoutes.Route> currentRoutes() {
        return project().map(p -> books.apply(p).load()).orElse(List.of());
    }

    private synchronized void hit(MockServer.Hit h) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("seq", nextHit++);
        m.put("at", h.at().toString());
        m.put("method", h.method());
        m.put("target", h.target());
        m.put("status", h.status());
        m.put("route", h.route());
        m.put("body", h.body());
        hits.addLast(m);
        if (hits.size() > KEPT) {
            hits.removeFirst();
        }
    }

    private Optional<ProjectSpec> project() {
        return session.current().map(EngineSession.Current::project);
    }

    private Optional<MockRoutes.Route> route(Map<?, ?> b) {
        if (!(b.get("method") instanceof String method)
                || !(b.get("path") instanceof String path)
                || !(b.get("status") instanceof Number status)) {
            return Optional.empty();
        }
        List<HttpCall.Header> headers = new ArrayList<>();
        if (b.get("headers") instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> h && h.get("name") instanceof String name && !name.isBlank()) {
                    headers.add(new HttpCall.Header(name.strip(), h.get("value") instanceof String v ? v : ""));
                }
            }
        }
        String id = b.get("id") instanceof String s && !s.isBlank() ? s : newId();
        long delay = b.get("delayMs") instanceof Number d ? d.longValue() : 0;
        return Optional.of(new MockRoutes.Route(
                id,
                method.strip().toUpperCase(java.util.Locale.ROOT),
                path.strip(),
                status.intValue(),
                headers,
                b.get("body") instanceof String s ? s : "",
                Duration.ofMillis(delay)));
    }

    private static Map<String, Object> json(MockRoutes.Route r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.id());
        m.put("method", r.method());
        m.put("path", r.path());
        m.put("status", r.status());
        m.put(
                "headers",
                r.headers().stream()
                        .map(h -> Map.of("name", h.name(), "value", h.value()))
                        .toList());
        m.put("body", r.body());
        m.put("delayMs", r.delay().toMillis());
        return m;
    }

    private static int indexOf(List<MockRoutes.Route> list, String id) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id().equals(id)) {
                return i;
            }
        }
        return -1;
    }

    private String newId() {
        byte[] b = new byte[6];
        random.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    private static EngineSession.Answer bad(String message) {
        return new EngineSession.Answer(400, ApiJson.error(message));
    }

    private static EngineSession.Answer noProject() {
        return new EngineSession.Answer(404, ApiJson.error("no project is open"));
    }
}
