package io.github.louch.devdesk.core.project;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * One service: what it runs (a command or a container), plus how it relates to the others.
 *
 * @param environment extra variables for this service only, on top of the project environment
 * @param directory where a command runs; containers ignore it
 * @param ports host ports the service is expected to listen on; checked for conflicts before starting
 * @param watch folders or files (absolute) whose changes restart the service; empty = not watched
 * @param test the command that runs this service's tests, if devdesk.yaml says ({@code test: npm test})
 * @param perBranch its data volumes are kept apart per git branch ({@code per_branch: true})
 * @param sleepAfter stop it when nobody used it this long, and start it on the next connection ({@code sleep_after})
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
        Duration stopGracePeriod,
        List<Path> watch,
        Optional<String> test,
        boolean perBranch,
        Optional<Duration> sleepAfter) {

    public ServiceSpec {
        environment = Map.copyOf(environment);
        dependsOn = List.copyOf(dependsOn);
        ports = List.copyOf(ports);
        watch = List.copyOf(watch);
    }

    /** A service that never goes to sleep by itself. */
    public ServiceSpec(
            String name,
            Workload workload,
            Path directory,
            Map<String, String> environment,
            List<Dependency> dependsOn,
            List<Integer> ports,
            HealthCheckSpec health,
            RestartPolicy restart,
            Duration stopGracePeriod,
            List<Path> watch,
            Optional<String> test,
            boolean perBranch) {
        this(
                name,
                workload,
                directory,
                environment,
                dependsOn,
                ports,
                health,
                restart,
                stopGracePeriod,
                watch,
                test,
                perBranch,
                Optional.empty());
    }

    /** A service whose data isn't kept per git branch. */
    public ServiceSpec(
            String name,
            Workload workload,
            Path directory,
            Map<String, String> environment,
            List<Dependency> dependsOn,
            List<Integer> ports,
            HealthCheckSpec health,
            RestartPolicy restart,
            Duration stopGracePeriod,
            List<Path> watch,
            Optional<String> test) {
        this(
                name,
                workload,
                directory,
                environment,
                dependsOn,
                ports,
                health,
                restart,
                stopGracePeriod,
                watch,
                test,
                false);
    }

    /** A service without a test command of its own. */
    public ServiceSpec(
            String name,
            Workload workload,
            Path directory,
            Map<String, String> environment,
            List<Dependency> dependsOn,
            List<Integer> ports,
            HealthCheckSpec health,
            RestartPolicy restart,
            Duration stopGracePeriod,
            List<Path> watch) {
        this(
                name,
                workload,
                directory,
                environment,
                dependsOn,
                ports,
                health,
                restart,
                stopGracePeriod,
                watch,
                Optional.empty());
    }

    /** A service that isn't watched for file changes. */
    public ServiceSpec(
            String name,
            Workload workload,
            Path directory,
            Map<String, String> environment,
            List<Dependency> dependsOn,
            List<Integer> ports,
            HealthCheckSpec health,
            RestartPolicy restart,
            Duration stopGracePeriod) {
        this(name, workload, directory, environment, dependsOn, ports, health, restart, stopGracePeriod, List.of());
    }
}
