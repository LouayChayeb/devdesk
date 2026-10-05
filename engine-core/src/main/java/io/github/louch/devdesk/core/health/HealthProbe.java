package io.github.louch.devdesk.core.health;

import io.github.louch.devdesk.core.project.HealthCheckSpec;
import java.nio.file.Path;
import java.util.Map;

/** Runs one health check once. Implemented outside core, because it opens sockets or runs commands. */
public interface HealthProbe {

    /**
     * Probes once and never throws: every failure, including a timeout, becomes {@link ProbeResult.Failed}.
     *
     * @param directory and {@code environment} are the service's, for command checks
     */
    ProbeResult probe(HealthCheckSpec check, Path directory, Map<String, String> environment);
}
