package io.github.louch.devdesk.daemon;

import io.github.louch.devdesk.core.project.ProjectSpec;
import io.github.louch.devdesk.core.project.ServiceSpec;
import io.github.louch.devdesk.core.supervisor.ServiceEnvironment;
import io.github.louch.devdesk.core.traffic.Exchange;
import io.github.louch.devdesk.core.traffic.ReplayCheck;
import io.github.louch.devdesk.core.traffic.TrafficProxy;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The traffic recorder: puts a recording stand-in in front of one service, keeps every call that goes through it,
 * and later sends them again, in order, to see whether the service still answers the same way. Recordings are kept
 * on disk per project, with the requests exactly as sent (a replay needs the real headers); the app sees secret
 * headers hidden.
 */
final class TrafficRecorder {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    /** A recording stops keeping calls after this many: enough to replay a session, not a load test. */
    static final int MAX_CALLS = 5000;

    /** One recording, live or finished. Calls are added by the proxy's threads, so the list is synchronized on. */
    private static final class Recording {
        final String id;
        final String service;
        final int targetPort;
        final Instant started;
        volatile Instant stopped;
        volatile int proxyPort;
        volatile TrafficProxy.Running running;
        final List<Exchange> calls = new ArrayList<>();

        Recording(String id, String service, int targetPort, Instant started) {
            this.id = id;
            this.service = service;
            this.targetPort = targetPort;
            this.started = started;
        }
    }

    private final EngineSession session;
    private final TrafficProxy proxy;
    private final Path root;
    private final Clock clock;
    private final Map<String, Recording> live = new ConcurrentHashMap<>();

    TrafficRecorder(EngineSession session, TrafficProxy proxy, Path root, Clock clock) {
        this.session = session;
        this.proxy = proxy;
        this.root = root;
        this.clock = clock;
    }

    /** The services that can be recorded (those with a port) and the project's recordings, newest first. */
    EngineSession.Answer overview() {
        return withProject(p -> {
            List<Map<String, Object>> targets = new ArrayList<>();
            for (ServiceSpec s : p.services().values()) {
                if (!s.ports().isEmpty()) {
                    targets.add(Map.of("service", s.name(), "ports", s.ports()));
                }
            }
            List<Map<String, Object>> list =
                    all(p).stream().map(TrafficRecorder::summary).toList();
            return Map.of("targets", targets, "recordings", list);
        });
    }

    /** {service, port?, listen?}: starts recording calls to that service's port through a new port. */
    synchronized EngineSession.Answer start(Map<?, ?> body) {
        return withProject(p -> {
            if (!(body.get("service") instanceof String service)
                    || !p.services().containsKey(service)) {
                throw new IllegalArgumentException("send {\"service\": a service of the project with a port}");
            }
            List<Integer> ports = p.services().get(service).ports();
            int target = body.get("port") instanceof Number n ? n.intValue() : ports.isEmpty() ? -1 : ports.getFirst();
            if (target <= 0) {
                throw new IllegalArgumentException(service + " has no port in devdesk.yaml: say which one to record");
            }
            int listen = body.get("listen") instanceof Number n ? n.intValue() : 0;
            String id = STAMP.format(clock.instant().atZone(ZoneId.systemDefault())) + "_" + service;
            Recording r = new Recording(id, service, target, clock.instant());
            r.running = proxy.start(
                    listen,
                    "127.0.0.1",
                    target,
                    // The caller's port on this machine, matched against the project's own connections.
                    from -> session.serviceUsing(from.getPort(), r.proxyPort).orElse(""),
                    call -> {
                        synchronized (r.calls) {
                            if (r.calls.size() < MAX_CALLS) {
                                r.calls.add(call);
                            }
                        }
                    });
            r.proxyPort = r.running.port();
            live.put(id, r);
            return summary(r);
        });
    }

    synchronized EngineSession.Answer stop(String id) {
        return withProject(p -> {
            Recording r = live.remove(id);
            if (r == null) {
                throw new IllegalArgumentException("no recording called " + id + " is running");
            }
            r.running.stop();
            r.stopped = clock.instant();
            save(p, r);
            return summary(r);
        });
    }

    /** The calls of a recording; secret-looking headers (Authorization, Cookie, API keys) shown hidden. */
    EngineSession.Answer calls(String id) {
        return withProject(p -> {
            Recording r = find(p, id);
            List<Exchange> copy;
            synchronized (r.calls) {
                copy = List.copyOf(r.calls);
            }
            Map<String, Object> m = new LinkedHashMap<>(summary(r));
            m.put("calls", copy.stream().map(TrafficRecorder::json).toList());
            return m;
        });
    }

    /**
     * Sends every call again, in the order recorded, straight to the service, and compares each answer with the one
     * recorded. Calls that don't change anything (GET) and calls that do (POST) are all sent: it's the session that's
     * replayed, on the user's explicit click.
     */
    EngineSession.Answer replay(String id) {
        return withProject(p -> {
            Recording r = find(p, id);
            List<Exchange> copy;
            synchronized (r.calls) {
                copy = List.copyOf(r.calls);
            }
            List<Map<String, Object>> results = new ArrayList<>();
            int same = 0;
            for (Exchange e : copy) {
                TrafficProxy.Answer a = proxy.send("127.0.0.1", r.targetPort, e);
                ReplayCheck.Result check = ReplayCheck.compare(e, a.status(), a.body());
                same += check.same() ? 1 : 0;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("seq", e.seq());
                m.put("method", e.method());
                m.put("path", e.path());
                m.put("was", e.status());
                m.put("now", a.status());
                m.put("ms", a.ms());
                m.put("same", check.same());
                m.put("differences", check.differences());
                results.add(m);
            }
            return Map.of("results", results, "same", same, "total", copy.size());
        });
    }

    synchronized EngineSession.Answer delete(String id) {
        return withProject(p -> {
            Recording r = find(p, id);
            if (live.containsKey(r.id)) {
                throw new IllegalArgumentException("stop the recording first");
            }
            try {
                Files.deleteIfExists(folder(p).resolve(r.id + ".json"));
            } catch (IOException e) {
                throw new IllegalStateException("couldn't delete it: " + e.getMessage(), e);
            }
            return Map.of("deleted", r.id);
        });
    }

    void stopAll() {
        live.values().forEach(r -> r.running.stop());
        live.clear();
    }

    // ---- helpers ----

    private static Map<String, Object> summary(Recording r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.id);
        m.put("service", r.service);
        m.put("targetPort", r.targetPort);
        m.put("proxyPort", r.proxyPort);
        m.put("started", r.started.toString());
        m.put("stopped", r.stopped == null ? null : r.stopped.toString());
        m.put("live", r.stopped == null);
        synchronized (r.calls) {
            m.put("count", r.calls.size());
        }
        return m;
    }

    private static Map<String, Object> json(Exchange e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("seq", e.seq());
        m.put("at", e.at().toString());
        m.put("caller", e.caller());
        m.put("method", e.method());
        m.put("path", e.path());
        m.put("requestHeaders", masked(e.requestHeaders()));
        m.put("requestBody", e.requestBody());
        m.put("status", e.status());
        m.put("responseHeaders", masked(e.responseHeaders()));
        m.put("responseBody", e.responseBody());
        m.put("ms", e.ms());
        return m;
    }

    private static Map<String, List<String>> masked(Map<String, List<String>> headers) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        headers.forEach((k, v) -> out.put(k, ServiceEnvironment.looksSecret(k) ? List.of("(hidden)") : v));
        return out;
    }

    private Recording find(ProjectSpec p, String id) {
        Recording r = live.get(id);
        if (r != null) {
            return r;
        }
        return all(p).stream()
                .filter(x -> x.id.equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no recording called " + id));
    }

    /** Live ones first, then the saved ones, newest first. */
    private List<Recording> all(ProjectSpec p) {
        List<Recording> out = new ArrayList<>(live.values());
        Path dir = folder(p);
        if (Files.isDirectory(dir)) {
            try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "*.json")) {
                for (Path f : files) {
                    load(f).filter(r -> !live.containsKey(r.id)).ifPresent(out::add);
                }
            } catch (IOException e) {
                // unreadable folder: only the live ones
            }
        }
        out.sort(Comparator.comparing((Recording r) -> r.started).reversed());
        return out;
    }

    private Path folder(ProjectSpec p) {
        return root.resolve(p.key());
    }

    private void save(ProjectSpec p, Recording r) {
        Map<String, Object> m = new LinkedHashMap<>(summary(r));
        List<Map<String, Object>> calls = new ArrayList<>();
        synchronized (r.calls) {
            for (Exchange e : r.calls) {
                Map<String, Object> c = json(e);
                // Kept as sent: replaying needs the real Authorization. The file stays in DevDesk's own folder.
                c.put("requestHeaders", e.requestHeaders());
                c.put("responseHeaders", e.responseHeaders());
                calls.add(c);
            }
        }
        m.put("calls", calls);
        try {
            Files.createDirectories(folder(p));
            Files.writeString(folder(p).resolve(r.id + ".json"), ApiJson.write(m), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("couldn't save the recording: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Optional<Recording> load(Path f) {
        try {
            Map<String, Object> m = ApiJson.MAPPER.readValue(Files.readString(f), Map.class);
            Recording r = new Recording(
                    (String) m.get("id"),
                    (String) m.get("service"),
                    ((Number) m.get("targetPort")).intValue(),
                    Instant.parse((String) m.get("started")));
            r.proxyPort = ((Number) m.get("proxyPort")).intValue();
            r.stopped = m.get("stopped") == null ? null : Instant.parse((String) m.get("stopped"));
            for (Object o : (List<Object>) m.get("calls")) {
                Map<String, Object> c = (Map<String, Object>) o;
                r.calls.add(new Exchange(
                        ((Number) c.get("seq")).intValue(),
                        Instant.parse((String) c.get("at")),
                        (String) c.get("caller"),
                        (String) c.get("method"),
                        (String) c.get("path"),
                        (Map<String, List<String>>) c.get("requestHeaders"),
                        (String) c.get("requestBody"),
                        ((Number) c.get("status")).intValue(),
                        (Map<String, List<String>>) c.get("responseHeaders"),
                        (String) c.get("responseBody"),
                        ((Number) c.get("ms")).longValue()));
            }
            return Optional.of(r);
        } catch (IOException | RuntimeException e) {
            return Optional.empty(); // a broken file: not listed
        }
    }

    private EngineSession.Answer withProject(java.util.function.Function<ProjectSpec, Object> call) {
        Optional<ProjectSpec> p = session.current().map(EngineSession.Current::project);
        if (p.isEmpty()) {
            return new EngineSession.Answer(404, ApiJson.error("no project is open"));
        }
        try {
            return new EngineSession.Answer(200, call.apply(p.get()));
        } catch (IllegalArgumentException e) {
            return new EngineSession.Answer(400, ApiJson.error(e.getMessage()));
        } catch (IllegalStateException e) {
            return new EngineSession.Answer(409, ApiJson.error(e.getMessage()));
        }
    }
}
