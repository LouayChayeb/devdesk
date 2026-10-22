package io.github.louch.devdesk.core.history;

import io.github.louch.devdesk.core.project.Dependency;
import io.github.louch.devdesk.core.project.HealthCheckSpec;
import io.github.louch.devdesk.core.project.ProjectSpec;
import io.github.louch.devdesk.core.project.ServiceSpec;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * "Why does my stack take 90 s to start?", answered from the recorded state changes: for each service, how long it
 * waited for others, how long its process took to start, and how long its health check took to pass. Plus the chain
 * of waits that decided when the whole stack was ready (the slowest chain).
 */
public final class StartupTimeline {

    /** Start attempts closer together than this belong to the same startup ("Start all" starts them within ms). */
    static final Duration SAME_STARTUP = Duration.ofSeconds(5);

    /** Leaving one of these and becoming waiting/starting is a new start attempt (not a restart while running). */
    private static final Set<String> INACTIVE = Set.of("stopped", "completed", "crashed", "failed", "blocked");

    private static final Set<String> FAILED = Set.of("crashed", "failed", "blocked", "crash-loop");

    private StartupTimeline() {}

    /**
     * One startup.
     *
     * @param took from the first start request until the last service was ready; empty while one is still starting
     *     or when one failed
     */
    public record Startup(Instant began, Optional<Duration> took, List<Row> services) {}

    /**
     * One service in a startup. Times are offsets from the startup's beginning; empty = it never got there.
     *
     * @param waitedFor the service it waited for last, the one that actually held it back
     * @param outcome ready, failed, or starting (still going)
     * @param slowestChain on the chain of waits that ends at the last service to be ready
     * @param detail the reason of its last state change, e.g. "GET http://127.0.0.1:8080/health → HTTP 200"
     */
    public record Row(
            String service,
            Duration requested,
            Optional<Duration> starting,
            Optional<Duration> running,
            Optional<Duration> ready,
            Optional<Duration> ended,
            Optional<String> waitedFor,
            List<String> dependsOn,
            String outcome,
            boolean slowestChain,
            String detail) {}

    /**
     * @param entries the recorded history, oldest first (other kinds than state changes are ignored)
     * @return the most recent startups first, at most {@code max}
     */
    public static List<Startup> of(List<HistoryEntry> entries, ProjectSpec project, int max) {
        List<HistoryEntry> states = entries.stream()
                .filter(e -> e.kind() == HistoryEntry.Kind.STATE
                        && project.services().containsKey(e.service()))
                .toList();
        // Group start attempts into startups: attempts within SAME_STARTUP of the previous one belong together.
        List<List<HistoryEntry>> groups = new ArrayList<>();
        Instant last = null;
        for (HistoryEntry e : states) {
            if (!isAttempt(e)) {
                continue;
            }
            if (last == null || Duration.between(last, e.at()).compareTo(SAME_STARTUP) > 0) {
                groups.add(new ArrayList<>());
            }
            groups.getLast().add(e);
            last = e.at();
        }
        List<Startup> out = new ArrayList<>();
        for (int i = groups.size() - 1; i >= 0 && out.size() < max; i--) {
            Instant until = i + 1 < groups.size() ? groups.get(i + 1).getFirst().at() : Instant.MAX;
            out.add(startup(groups.get(i), states, until, project));
        }
        return out;
    }

    private static boolean isAttempt(HistoryEntry e) {
        Object from = e.data().get("from");
        Object to = e.data().get("to");
        return INACTIVE.contains(String.valueOf(from)) && ("waiting".equals(to) || "starting".equals(to));
    }

    private static Startup startup(
            List<HistoryEntry> attempts, List<HistoryEntry> states, Instant until, ProjectSpec project) {
        Instant began = attempts.getFirst().at();
        Map<String, Row> rows = new LinkedHashMap<>();
        for (HistoryEntry attempt : attempts) {
            if (rows.containsKey(attempt.service())) {
                continue; // a second attempt in the same burst (a quick retry): the first one tells the story
            }
            ServiceSpec spec = project.services().get(attempt.service());
            rows.put(attempt.service(), row(attempt, states, until, began, spec));
        }
        // What each one waited for: the dependency that was ready last before it could start.
        Map<String, Row> withWaits = new LinkedHashMap<>();
        rows.forEach(
                (name, r) -> withWaits.put(name, withWait(r, project.services().get(name), rows)));
        return finish(began, withWaits);
    }

    private static Row row(
            HistoryEntry attempt, List<HistoryEntry> states, Instant until, Instant began, ServiceSpec spec) {
        Map<String, Duration> firstTime = new HashMap<>();
        String outcome = "starting";
        String detail = summaryReason(attempt);
        boolean noCheck = spec.health() instanceof HealthCheckSpec.ProcessAlive;
        boolean justStarted = false;
        for (HistoryEntry e : states) {
            if (!e.service().equals(spec.name()) || e.at().isBefore(attempt.at()) || !e.at().isBefore(until)) {
                continue;
            }
            String to = String.valueOf(e.data().get("to"));
            if (justStarted && !to.equals("completed") && !FAILED.contains(to)) {
                break; // it kept running: a normal service, ready since it started
            }
            firstTime.putIfAbsent(to, Duration.between(began, e.at()));
            detail = summaryReason(e);
            if (to.equals("healthy") || to.equals("completed")) {
                outcome = "ready";
                break;
            }
            if (FAILED.contains(to)) {
                outcome = "failed";
                break;
            }
            if (noCheck && to.equals("running")) {
                // No health check: running counts as ready. But look at its next change: a one-off job (migrate)
                // is only done when it completes, and one that dies right away didn't really start.
                outcome = "ready";
                justStarted = true;
                continue;
            }
            if (to.equals("stopped") || to.equals("stopping")) {
                outcome = "stopped"; // stopped by hand before it was ready
                break;
            }
        }
        Optional<Duration> ready = Optional.ofNullable(firstTime.get("healthy"))
                .or(() -> Optional.ofNullable(firstTime.get("completed")))
                .or(() -> noCheck ? Optional.ofNullable(firstTime.get("running")) : Optional.empty());
        Optional<Duration> ended = outcome.equals("ready")
                ? ready
                : firstTime.entrySet().stream()
                        .filter(t -> FAILED.contains(t.getKey()) || t.getKey().equals("stopped"))
                        .map(Map.Entry::getValue)
                        .min(Duration::compareTo);
        return new Row(
                spec.name(),
                Duration.between(began, attempt.at()),
                Optional.ofNullable(firstTime.get("starting")),
                Optional.ofNullable(firstTime.get("running")),
                outcome.equals("ready") ? ready : Optional.empty(),
                ended,
                Optional.empty(),
                spec.dependsOn().stream().map(Dependency::service).toList(),
                outcome,
                false,
                detail);
    }

    private static Row withWait(Row r, ServiceSpec spec, Map<String, Row> rows) {
        if (r.starting().isEmpty()) {
            return r;
        }
        Optional<String> waited = Optional.empty();
        Duration latest = Duration.ZERO;
        for (Dependency d : spec.dependsOn()) {
            Row dep = rows.get(d.service());
            if (dep == null) {
                continue; // it was already up before this startup: nothing to wait for
            }
            // When the dependency was good enough for this one: running, healthy, or finished, as depends_on says.
            Optional<Duration> satisfied = switch (d.condition()) {
                case STARTED -> dep.running();
                case HEALTHY -> dep.ready();
                case COMPLETED_SUCCESSFULLY -> dep.ended();
            };
            if (satisfied.isPresent()
                    && satisfied.get().compareTo(latest) >= 0
                    && satisfied.get().compareTo(r.starting().get()) <= 0) {
                latest = satisfied.get();
                waited = Optional.of(d.service());
            }
        }
        return new Row(
                r.service(),
                r.requested(),
                r.starting(),
                r.running(),
                r.ready(),
                r.ended(),
                waited,
                r.dependsOn(),
                r.outcome(),
                false,
                r.detail());
    }

    /** Marks the slowest chain: from the last service to be ready, back through what each one waited for. */
    private static Startup finish(Instant began, Map<String, Row> rows) {
        boolean allReady = rows.values().stream().allMatch(r -> r.outcome().equals("ready"));
        Optional<Row> lastReady = rows.values().stream()
                .filter(r -> r.ready().isPresent())
                .max((a, b) -> a.ready().get().compareTo(b.ready().get()));
        List<String> chain = new ArrayList<>();
        Optional<Row> at = lastReady;
        while (at.isPresent() && !chain.contains(at.get().service())) {
            chain.add(at.get().service());
            at = at.get().waitedFor().map(rows::get);
        }
        List<Row> out = rows.values().stream()
                .map(r -> new Row(
                        r.service(),
                        r.requested(),
                        r.starting(),
                        r.running(),
                        r.ready(),
                        r.ended(),
                        r.waitedFor(),
                        r.dependsOn(),
                        r.outcome(),
                        chain.contains(r.service()),
                        r.detail()))
                .toList();
        Optional<Duration> took = allReady ? lastReady.flatMap(Row::ready) : Optional.empty();
        return new Startup(began, took, out);
    }

    /** "healthy: GET … → HTTP 200" → "GET … → HTTP 200". */
    private static String summaryReason(HistoryEntry e) {
        int colon = e.summary().indexOf(": ");
        return colon < 0 ? "" : e.summary().substring(colon + 2);
    }
}
