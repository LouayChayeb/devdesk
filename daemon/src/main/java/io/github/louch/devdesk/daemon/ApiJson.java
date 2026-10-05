package io.github.louch.devdesk.daemon;

import io.github.louch.devdesk.core.config.ConfigProblem;
import io.github.louch.devdesk.core.diagnosis.Finding;
import io.github.louch.devdesk.core.diagnosis.Ghosts;
import io.github.louch.devdesk.core.history.HistoryEntry;
import io.github.louch.devdesk.core.history.Timeline;
import io.github.louch.devdesk.core.network.Links;
import io.github.louch.devdesk.core.project.Dependency;
import io.github.louch.devdesk.core.project.HealthCheckSpec;
import io.github.louch.devdesk.core.project.ProjectSpec;
import io.github.louch.devdesk.core.project.ServiceSpec;
import io.github.louch.devdesk.core.project.Workload;
import io.github.louch.devdesk.core.state.ServiceState;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/**
 * The exact JSON shapes the desktop app receives, built by hand from engine types. Written out explicitly (no
 * annotations, no reflection on domain classes) so the API is visible in one file and can't change by accident.
 */
final class ApiJson {

    static final JsonMapper MAPPER = JsonMapper.builder().build();

    private ApiJson() {}

    static String write(Object value) {
        return MAPPER.writeValueAsString(value);
    }

    static Map<String, Object> project(
            ProjectSpec project,
            String file,
            boolean trusted,
            List<String> commands,
            Map<String, ServiceState> states) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", project.name());
        m.put("root", project.root().toString());
        m.put("file", file);
        m.put("trusted", trusted);
        m.put("commands", commands);
        m.put(
                "services",
                project.services().values().stream()
                        .map(s -> service(s, states.getOrDefault(s.name(), new ServiceState.Stopped())))
                        .toList());
        return m;
    }

    static Map<String, Object> service(ServiceSpec s, ServiceState state) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", s.name());
        m.put("kind", s.workload() instanceof Workload.Container ? "container" : "process");
        m.put("workload", s.workload().describe());
        m.put("directory", s.directory().toString());
        m.put("ports", s.ports());
        m.put(
                "dependsOn",
                s.dependsOn().stream()
                        .map(d -> Map.of("service", d.service(), "condition", condition(d.condition())))
                        .toList());
        m.put("health", healthType(s.health()));
        m.put("restart", s.restart().name().toLowerCase().replace('_', '-'));
        m.put("state", state(state));
        return m;
    }

    /** {label, detail, pid, restarts}: label is what the UI colours by, detail is the human reason. */
    static Map<String, Object> state(ServiceState state) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("label", state.label());
        m.put(
                "detail",
                switch (state) {
                    case ServiceState.Blocked b -> b.reason();
                    case ServiceState.Crashed c -> c.exit().explanation();
                    case ServiceState.CrashLoop c ->
                        "keeps crashing, last: " + c.lastExit().explanation();
                    case ServiceState.FailedToStart f -> f.reason();
                    case ServiceState.Unhealthy u -> u.reason();
                    default -> "";
                });
        switch (state) {
            case ServiceState.Running r -> {
                m.put("pid", r.pid());
                m.put("restarts", r.restarts());
            }
            case ServiceState.Healthy h -> {
                m.put("pid", h.pid());
                m.put("restarts", h.restarts());
            }
            case ServiceState.Unhealthy u -> {
                m.put("pid", u.pid());
                m.put("restarts", u.restarts());
            }
            case ServiceState.Blocked b -> m.put("cause", b.cause());
            default -> {}
        }
        return m;
    }

    static Map<String, Object> finding(Finding f) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rule", f.ruleId());
        m.put("severity", f.severity().name().toLowerCase());
        m.put("service", f.service());
        m.put("title", f.title());
        m.put("explanation", f.explanation());
        m.put("evidence", f.evidence());
        m.put("actions", f.actions());
        return m;
    }

    static Map<String, Object> problem(ConfigProblem p) {
        return Map.of(
                "line", p.location().line(), "column", p.location().column(), "path", p.path(), "message", p.message());
    }

    static Map<String, Object> error(String message) {
        return Map.of("error", message);
    }

    private static String condition(Dependency.Condition c) {
        return c.name().toLowerCase();
    }

    private static String healthType(HealthCheckSpec h) {
        return switch (h) {
            case HealthCheckSpec.ProcessAlive p -> "none";
            case HealthCheckSpec.Tcp t -> "tcp " + t.host() + ":" + t.port();
            case HealthCheckSpec.Http x -> "http " + x.url();
            case HealthCheckSpec.Command c -> "command " + c.command();
            case HealthCheckSpec.Docker d -> "docker healthcheck";
        };
    }

    static Map<String, Object> historyItem(Timeline.Item item) {
        HistoryEntry e = item.entry();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("at", e.at().toString());
        m.put("service", e.service());
        m.put("kind", e.kind().name().toLowerCase());
        m.put("summary", e.summary());
        m.put("data", e.data());
        m.put("routine", item.routine());
        m.put("afterUncleanStop", item.afterUncleanStop());
        return m;
    }

    static Map<String, Object> link(Links.Link l) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("from", l.from());
        m.put("to", l.to().orElse(null)); // null: outside the project
        m.put("target", l.target());
        m.put("open", l.open());
        return m;
    }

    static Map<String, Object> ghost(Ghosts.Ghost g, ProjectSpec project) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("pid", g.process().pid());
        m.put("command", g.process().command());
        m.put(
                "folder",
                g.process()
                        .workingDirectory()
                        .map(d -> project.root().relativize(d).toString())
                        .orElse(null));
        m.put("startedAt", g.process().startedAt().map(Object::toString).orElse(null));
        m.put("ports", List.copyOf(g.ports()));
        m.put("children", g.children());
        m.put("blocks", g.blocks(project));
        return m;
    }
}
