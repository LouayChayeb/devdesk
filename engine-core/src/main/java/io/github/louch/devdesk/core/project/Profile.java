package io.github.louch.devdesk.core.project;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A named set of setting overrides in devdesk.yaml ({@code profiles: demo: ...}): the same project with a demo
 * database, or mails going to the inbox only. Only settings change: the services and commands stay the same.
 *
 * @param environment for every service, after the project's env_files (its own env files are already read in)
 * @param services per service, on top of the service's own environment
 */
public record Profile(String name, Map<String, String> environment, Map<String, Map<String, String>> services) {

    public Profile {
        environment = Map.copyOf(environment);
        Map<String, Map<String, String>> copy = new LinkedHashMap<>();
        services.forEach((k, v) -> copy.put(k, Map.copyOf(v)));
        services = Collections.unmodifiableMap(copy);
    }

    /**
     * {@code project} as it runs with this profile: the profile's values win over the env files and over each
     * service's own environment, because choosing a profile is choosing those values.
     */
    public ProjectSpec applyTo(ProjectSpec project) {
        Map<String, String> env = new LinkedHashMap<>(project.environment());
        env.putAll(environment);
        Map<String, ServiceSpec> next = new LinkedHashMap<>();
        project.services().forEach((name, s) -> {
            Map<String, String> own = new LinkedHashMap<>(s.environment());
            // Project-wide profile values must beat the service's own block too, or "demo" wouldn't apply everywhere.
            environment.forEach((k, v) -> {
                if (own.containsKey(k)) {
                    own.put(k, v);
                }
            });
            own.putAll(services.getOrDefault(name, Map.of()));
            next.put(
                    name,
                    new ServiceSpec(
                            s.name(),
                            s.workload(),
                            s.directory(),
                            own,
                            s.dependsOn(),
                            s.ports(),
                            s.health(),
                            s.restart(),
                            s.stopGracePeriod(),
                            s.watch(),
                            s.test(),
                            s.perBranch(),
                            s.sleepAfter()));
        });
        // A task gets the profile's project-wide values too: "seed" in the demo profile seeds the demo database.
        Map<String, TaskSpec> tasks = new LinkedHashMap<>();
        project.tasks().forEach((name, t) -> {
            Map<String, String> own = new LinkedHashMap<>(t.environment());
            environment.forEach((k, v) -> {
                if (own.containsKey(k)) {
                    own.put(k, v);
                }
            });
            tasks.put(
                    name,
                    new TaskSpec(t.name(), t.command(), t.directory(), own, t.needs(), t.description(), t.confirm()));
        });
        return new ProjectSpec(project.name(), project.root(), env, next, project.profiles(), tasks);
    }
}
