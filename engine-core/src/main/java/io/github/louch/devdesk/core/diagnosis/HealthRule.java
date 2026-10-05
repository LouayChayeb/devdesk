package io.github.louch.devdesk.core.diagnosis;

import io.github.louch.devdesk.core.health.ProbeResult;
import io.github.louch.devdesk.core.network.Listener;
import io.github.louch.devdesk.core.project.HealthCheckSpec;
import io.github.louch.devdesk.core.project.ServiceSpec;
import io.github.louch.devdesk.core.state.ServiceState;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The service runs but its health check fails. The most useful extra fact: which ports the service really listens on.
 * A service listening on 3000 while the check waits on 3001 is a very common mistake.
 */
public final class HealthRule implements DiagnosisRule {

    @Override
    public String id() {
        return "health";
    }

    @Override
    public List<Finding> evaluate(DiagnosisContext ctx, String service) {
        if (!(ctx.stateOf(service) instanceof ServiceState.Unhealthy u)) {
            return List.of();
        }
        ServiceSpec spec = ctx.project().services().get(service);
        ServiceHistory h = ctx.historyOf(service);
        List<String> evidence = new ArrayList<>();
        // The same failure repeated is noise: show each distinct result once, with how often it happened.
        Map<String, Integer> seen = new LinkedHashMap<>();
        h.probes()
                .forEach(p -> seen.merge(
                        "check " + (p instanceof ProbeResult.Passed ? "passed" : "failed") + ": " + p.detail(),
                        1,
                        Integer::sum));
        seen.forEach((line, times) -> evidence.add(line + (times > 1 ? "  (" + times + " times)" : "")));
        List<Listener> listening = ctx.system().listenersOf(h.pids());
        List<Integer> ports =
                listening.stream().map(Listener::port).distinct().sorted().toList();
        evidence.add(
                h.pids().isEmpty()
                        ? "no running processes found for " + service
                        : service + " (PIDs " + h.pids() + ") listens on "
                                + (ports.isEmpty() ? "no TCP port" : "ports " + ports));

        List<String> actions = new ArrayList<>();
        String explanation = h.everHealthy()
                ? service + " was healthy before, and its check now fails: " + u.reason() + "."
                : service + " started, but its health check never passed: " + u.reason() + ".";
        Optional<Integer> checked = checkedPort(spec.health());
        if (checked.isPresent() && !ports.isEmpty() && !ports.contains(checked.get())) {
            explanation += " It listens on " + ports + ", but the check waits on port " + checked.get() + ".";
            actions.add("make the health check use port " + ports.getFirst() + ", or make " + service + " listen on "
                    + checked.get());
        } else if (checked.isPresent() && ports.isEmpty() && !h.pids().isEmpty()) {
            explanation += " It isn't listening on any port yet: it may still be starting, or it crashed inside.";
            actions.add("look at its output for errors; if it starts slowly, raise health.start_period");
        }
        actions.add("open the health URL or port yourself to see what it answers");
        return List.of(new Finding(
                id(),
                Finding.Severity.ERROR,
                service,
                h.everHealthy() ? service + " became unhealthy" : service + " never became healthy",
                explanation,
                evidence,
                actions));
    }

    private static Optional<Integer> checkedPort(HealthCheckSpec check) {
        return switch (check) {
            case HealthCheckSpec.Tcp t -> Optional.of(t.port());
            case HealthCheckSpec.Http h ->
                Optional.of(h.url().getPort() > 0 ? h.url().getPort() : 80);
            default -> Optional.empty();
        };
    }
}
