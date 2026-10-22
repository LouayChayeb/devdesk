package io.github.louch.devdesk.daemon;

import io.github.louch.devdesk.core.resources.ResourceSample;
import io.github.louch.devdesk.core.stress.LoadGenerator;
import io.github.louch.devdesk.core.stress.LoadPlan;
import io.github.louch.devdesk.core.stress.LoadSecond;
import io.github.louch.devdesk.core.stress.StressVerdict;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The stress lab: one run at a time against this machine. While the load climbs, it notes every second what each
 * service of the project is doing (state, CPU, memory), so the result says which one gave up first.
 */
final class StressLab {

    private final EngineSession session;
    private final LoadGenerator load;

    private volatile LoadPlan plan;
    private volatile boolean running;
    private volatile boolean cancelled;
    private final List<LoadSecond> seconds = new ArrayList<>();
    private final List<StressVerdict.ServicePoint> points = new ArrayList<>();

    StressLab(EngineSession session, LoadGenerator load) {
        this.session = session;
        this.load = load;
    }

    /** {method, url, headers?, body?, maxConcurrency, seconds}: starts a run, if none is running. */
    synchronized EngineSession.Answer start(Map<?, ?> body) {
        if (running) {
            return new EngineSession.Answer(409, ApiJson.error("a stress run is already going: stop it first"));
        }
        Map<String, String> headers = new LinkedHashMap<>();
        if (body.get("headers") instanceof Map<?, ?> h) {
            h.forEach((k, v) -> headers.put(String.valueOf(k), String.valueOf(v)));
        }
        LoadPlan p = new LoadPlan(
                body.get("method") instanceof String m && !m.isBlank() ? m.toUpperCase() : "GET",
                body.get("url") instanceof String u ? u.strip() : "",
                headers,
                body.get("body") instanceof String b ? b : "",
                body.get("maxConcurrency") instanceof Number n ? n.intValue() : 50,
                body.get("seconds") instanceof Number n ? n.intValue() : 60);
        Optional<String> problem = p.problem();
        if (problem.isPresent()) {
            return new EngineSession.Answer(400, ApiJson.error(problem.get()));
        }
        plan = p;
        seconds.clear();
        points.clear();
        cancelled = false;
        running = true;
        Thread.ofVirtual().name("stress").start(() -> {
            try {
                load.run(p, this::record, () -> cancelled);
            } finally {
                running = false;
            }
        });
        return new EngineSession.Answer(202, status());
    }

    synchronized EngineSession.Answer cancel() {
        cancelled = true;
        return new EngineSession.Answer(200, status());
    }

    synchronized EngineSession.Answer current() {
        return new EngineSession.Answer(200, status());
    }

    /** One second ended: keep it, with what every service was doing right then. */
    private synchronized void record(LoadSecond s) {
        seconds.add(s);
        Map<String, ResourceSample> res = session.latestResources();
        session.current()
                .ifPresent(c -> c.states().forEach((service, state) -> {
                    ResourceSample r = res.get(service);
                    points.add(new StressVerdict.ServicePoint(
                            s.second(),
                            service,
                            state.label(),
                            r == null ? 0 : r.cpuPercent(),
                            r == null ? 0 : r.memoryBytes()));
                }));
    }

    private Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("running", running);
        m.put(
                "plan",
                plan == null
                        ? null
                        : Map.of(
                                "method",
                                plan.method(),
                                "url",
                                plan.url(),
                                "maxConcurrency",
                                plan.maxConcurrency(),
                                "seconds",
                                plan.seconds()));
        m.put(
                "seconds",
                seconds.stream()
                        .map(s -> {
                            Map<String, Object> x = new LinkedHashMap<>();
                            x.put("second", s.second());
                            x.put("concurrency", s.concurrency());
                            x.put("requests", s.requests());
                            x.put("errors", s.errors());
                            x.put("p50", s.p50Ms());
                            x.put("p95", s.p95Ms());
                            x.put("max", s.maxMs());
                            x.put("errorKinds", s.errorKinds());
                            return x;
                        })
                        .toList());
        Map<String, List<Map<String, Object>>> perService = new LinkedHashMap<>();
        for (StressVerdict.ServicePoint p : points) {
            perService
                    .computeIfAbsent(p.service(), k -> new ArrayList<>())
                    .add(Map.of(
                            "second",
                            p.second(),
                            "label",
                            p.label(),
                            "cpu",
                            Math.round(p.cpuPercent()),
                            "memory",
                            p.memoryBytes()));
        }
        m.put("services", perService);
        StressVerdict v = StressVerdict.of(List.copyOf(seconds), List.copyOf(points));
        Map<String, Object> verdict = new LinkedHashMap<>();
        verdict.put("text", v.text());
        verdict.put("breakingSecond", v.breakingSecond().orElse(null));
        verdict.put("firstToGiveUp", v.firstToGiveUp().orElse(null));
        m.put("verdict", seconds.isEmpty() ? null : verdict);
        return m;
    }
}
