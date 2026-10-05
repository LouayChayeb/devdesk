package io.github.louch.devdesk.core.project;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * One service: what it runs (a command or a container), plus how it relates to the others.
 *
 * @param environment extra variables for this service only, on top of the project environment
 * @param directory where a command runs; containers ignore it
 * @param ports host ports the service is expected to listen on; checked for conflicts before starting
 */
public record ServiceSpec(
        String name,
        Workload workload,
        Path directory,
        Map<String, String> environment,
        List<Dependency> dependsOn,
        List<Integer> ports,
        HealthCheckSpec health,
        RestartPolicy restart,
        Duration stopGracePeriod) {

    public ServiceSpec {
        environment = Map.copyOf(environment);
        dependsOn = List.copyOf(dependsOn);
        ports = List.copyOf(ports);
    }
}
