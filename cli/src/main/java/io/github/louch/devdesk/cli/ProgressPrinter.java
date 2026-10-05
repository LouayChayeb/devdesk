package io.github.louch.devdesk.cli;

import io.github.louch.devdesk.core.state.ServiceState;
import io.github.louch.devdesk.core.supervisor.ProjectEvent;
import java.io.PrintStream;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * Turns project events into terminal lines: one line per state change ("✓ db healthy (2.1s)"), and service output
 * prefixed with its name ("api  | listening on 3001"). Thread-safe: events arrive from several threads.
 */
final class ProgressPrinter {

    private final PrintStream out;
    private final int nameWidth;
    private final Map<String, Instant> waitingSince = new HashMap<>();

    ProgressPrinter(PrintStream out, Collection<String> serviceNames) {
        this.out = out;
        this.nameWidth = serviceNames.stream().mapToInt(String::length).max().orElse(4);
    }

    synchronized void print(ProjectEvent event) {
        switch (event) {
            case ProjectEvent.Output o ->
                out.println(pad(o.service()) + " | " + o.line().text());
            case ProjectEvent.Warning w -> out.println("! " + w.service() + ": " + w.message());
            case ProjectEvent.Diagnosis d -> d.findings().forEach(f -> FindingPrinter.print(out, f));
            case ProjectEvent.Resources r -> {} // numbers every few seconds: shown by the app and in history, not here
            case ProjectEvent.StateChanged c -> stateLine(c);
        }
    }

    private void stateLine(ProjectEvent.StateChanged c) {
        String name = c.service();
        switch (c.to()) {
            case ServiceState.WaitingDeps w -> waitingSince.put(name, c.at());
            case ServiceState.Starting s ->
                out.println("… " + name + " starting" + (s.restarts() > 0 ? " (" + c.reason() + ")" : ""));
            case ServiceState.Running r -> out.println("▶ " + name + " running, PID " + r.pid() + took(name, c.at()));
            case ServiceState.Healthy h ->
                out.println("✓ " + name + " healthy" + took(name, c.at()) + ": " + c.reason());
            case ServiceState.Unhealthy u -> out.println("! " + name + " unhealthy: " + u.reason());
            case ServiceState.Completed d -> out.println("✓ " + name + " completed" + took(name, c.at()));
            case ServiceState.Crashed x ->
                out.println("✗ " + name + " " + x.exit().explanation());
            case ServiceState.FailedToStart f -> out.println("✗ " + name + " failed to start: " + f.reason());
            case ServiceState.CrashLoop l -> out.println("✗ " + name + " crash loop: " + c.reason());
            case ServiceState.Blocked b -> out.println("✗ " + name + " not started, because " + b.reason());
            case ServiceState.Stopping s -> {}
            case ServiceState.Stopped s -> out.println("■ " + name + " stopped");
        }
    }

    /** Final one-line verdict after {@code up}. */
    synchronized void summary(Map<String, ServiceState> states) {
        // Unhealthy runs but doesn't work, so it doesn't count as "up".
        long up = states.values().stream()
                .filter(s -> s instanceof ServiceState.Healthy
                        || s instanceof ServiceState.Running
                        || s instanceof ServiceState.Completed)
                .count();
        boolean anyRunning = states.values().stream().anyMatch(ServiceState::isActive);
        int total = states.size();
        if (up == total) {
            out.println((total == 1 ? "The service is up." : "All " + total + " services are up.")
                    + " Press Ctrl+C to stop " + (total == 1 ? "it." : "them all."));
        } else {
            out.println((total - up) + " of " + total + (total == 1 ? " service" : " services")
                    + " did not come up (see above)."
                    + (anyRunning ? " What runs keeps running; Ctrl+C stops it." : ""));
        }
    }

    private String took(String name, Instant at) {
        Instant since = waitingSince.get(name);
        if (since == null) {
            return "";
        }
        Duration d = Duration.between(since, at);
        return String.format(" (%.1fs)", d.toMillis() / 1000.0);
    }

    private String pad(String name) {
        return name + " ".repeat(Math.max(0, nameWidth - name.length()));
    }
}
