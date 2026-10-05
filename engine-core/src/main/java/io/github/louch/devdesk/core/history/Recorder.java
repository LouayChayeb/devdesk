package io.github.louch.devdesk.core.history;

import io.github.louch.devdesk.core.diagnosis.Finding;
import io.github.louch.devdesk.core.resources.ResourceSample;
import io.github.louch.devdesk.core.supervisor.ProjectEvent;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Listens to a project's events and writes the ones worth remembering. Plug it in next to the CLI or app listener.
 *
 * <p>Kept: state changes with their reason, findings, warnings, and resources every {@link #RESOURCE_EVERY} per service.
 * Not kept: output lines (too many; crash findings already carry the important last lines) and environment values.
 */
public final class Recorder implements Consumer<ProjectEvent> {

    static final Duration RESOURCE_EVERY = Duration.ofSeconds(10);

    private final HistoryStore store;
    private final Clock clock;
    private final Map<String, Instant> lastResources = new HashMap<>();

    public Recorder(HistoryStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    /** Call when DevDesk starts watching the project: gaps between sessions show when nobody was watching. */
    public void sessionStarted(String how) {
        store.append(new HistoryEntry(
                clock.instant(),
                "",
                HistoryEntry.Kind.SESSION,
                "DevDesk started (" + how + ")",
                Map.of("event", "started")));
    }

    public void sessionEnded(String reason) {
        store.append(new HistoryEntry(
                clock.instant(),
                "",
                HistoryEntry.Kind.SESSION,
                "DevDesk stopped (" + reason + ")",
                Map.of("event", "ended")));
    }

    @Override
    public synchronized void accept(ProjectEvent event) {
        switch (event) {
            case ProjectEvent.StateChanged c -> {
                // "stopped: stopped" says nothing twice: drop a reason that only repeats the state.
                String reason = c.reason().isBlank() || c.reason().equals(c.to().label()) ? "" : ": " + c.reason();
                store.append(new HistoryEntry(
                        c.at(),
                        c.service(),
                        HistoryEntry.Kind.STATE,
                        c.to().label() + reason,
                        Map.of("from", c.from().label(), "to", c.to().label())));
            }
            case ProjectEvent.Diagnosis d -> {
                for (Finding f : d.findings()) {
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("rule", f.ruleId());
                    data.put("severity", f.severity().name().toLowerCase());
                    data.put("explanation", f.explanation());
                    store.append(
                            new HistoryEntry(clock.instant(), d.service(), HistoryEntry.Kind.FINDING, f.title(), data));
                }
            }
            case ProjectEvent.Warning w ->
                store.append(new HistoryEntry(
                        clock.instant(), w.service(), HistoryEntry.Kind.WARNING, w.message(), Map.of()));
            case ProjectEvent.Resources r -> r.services().forEach(this::resources);
            case ProjectEvent.Output o -> {} // not kept: see the class comment
        }
    }

    private void resources(String service, ResourceSample s) {
        Instant last = lastResources.get(service);
        if (last != null && s.at().isBefore(last.plus(RESOURCE_EVERY))) {
            return; // the supervisor samples every 2 s; every 10 s is plenty for a timeline
        }
        lastResources.put(service, s.at());
        store.append(new HistoryEntry(
                s.at(),
                service,
                HistoryEntry.Kind.RESOURCES,
                "",
                Map.of(
                        "cpu",
                        Math.round(s.cpuPercent() * 10) / 10.0,
                        "memory",
                        s.memoryBytes(),
                        "processes",
                        s.processes(),
                        "sockets",
                        s.sockets())));
    }
}
