package io.github.louch.devdesk.core.project;

import java.net.URI;
import java.time.Duration;

/** How DevDesk decides a service is really ready, not just "started". */
public sealed interface HealthCheckSpec {

    Timing timing();

    /**
     * When and how often to probe.
     *
     * @param retries consecutive failures before the service counts as unhealthy
     * @param startPeriod failures during this time after start don't count (apps need time to boot)
     */
    record Timing(Duration interval, Duration timeout, int retries, Duration startPeriod) {
        public static final Timing DEFAULT =
                new Timing(Duration.ofSeconds(2), Duration.ofSeconds(3), 3, Duration.ofSeconds(60));
    }

    /** No real check: "running" only, never shown as "healthy". The default. */
    record ProcessAlive(Timing timing) implements HealthCheckSpec {}

    /** Healthy when a TCP connection to host:port succeeds. */
    record Tcp(String host, int port, Timing timing) implements HealthCheckSpec {}

    /** Healthy when GET url answers with a status in [minStatus, maxStatus]. */
    record Http(URI url, int minStatus, int maxStatus, Timing timing) implements HealthCheckSpec {}

    /** Uses the container image's own HEALTHCHECK, as Docker reports it ("healthy", "unhealthy", "starting"). */
    record Docker(Timing timing) implements HealthCheckSpec {}

    /** Healthy when the command exits with 0, e.g. {@code pg_isready -h 127.0.0.1}. */
    record Command(String command, Timing timing) implements HealthCheckSpec {}
}
