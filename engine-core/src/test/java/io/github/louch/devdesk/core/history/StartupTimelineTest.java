package io.github.louch.devdesk.core.history;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.louch.devdesk.core.config.ConfigException;
import io.github.louch.devdesk.core.config.ConfigParser;
import io.github.louch.devdesk.core.project.ProjectSpec;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class StartupTimelineTest {

    private static final Instant T0 = Instant.parse("2026-10-13T09:00:00Z");

    private final ProjectSpec project = parse("""
            version: 1
            name: shop
            services:
              db:
                container: { image: postgres:16 }
                health: { type: tcp, port: 5432 }
              migrate:
                command: ./migrate
                depends_on:
                  db: { condition: healthy }
              cache:
                container: { image: redis:7 }
              api:
                command: ./api
                depends_on:
                  migrate: { condition: completed_successfully }
                  cache: { condition: started }
                health: { type: tcp, port: 8080 }
            """);

    private final List<HistoryEntry> history = new ArrayList<>();

    private void state(double seconds, String service, String from, String to, String reason) {
        history.add(new HistoryEntry(
                T0.plusMillis((long) (seconds * 1000)),
                service,
                HistoryEntry.Kind.STATE,
                to + ": " + reason,
                Map.of("from", from, "to", to)));
    }

    private void normalStartup(double at) {
        state(at, "db", "stopped", "starting", "dependencies ready");
        state(at, "cache", "stopped", "starting", "dependencies ready");
        state(at, "migrate", "stopped", "waiting", "start requested");
        state(at, "api", "stopped", "waiting", "start requested");
        state(at + 1, "cache", "starting", "running", "started");
        state(at + 2, "db", "starting", "running", "started");
        state(at + 30, "db", "running", "healthy", "port 5432 accepts connections");
        state(at + 30, "migrate", "waiting", "starting", "dependencies ready");
        state(at + 30.5, "migrate", "starting", "running", "started");
        state(at + 50, "migrate", "running", "completed", "exited normally (code 0)");
        state(at + 50, "api", "waiting", "starting", "dependencies ready");
        state(at + 51, "api", "starting", "running", "started");
        state(at + 60, "api", "running", "healthy", "port 8080 accepts connections");
    }

    @Test
    void eachServiceSaysHowLongItWaitedStartedAndGotHealthy() {
        normalStartup(0);
        var startup = StartupTimeline.of(history, project, 5).getFirst();

        assertThat(startup.began()).isEqualTo(T0);
        assertThat(startup.took()).contains(Duration.ofSeconds(60));
        var api = row(startup, "api");
        assertThat(api.starting()).contains(Duration.ofSeconds(50)); // waited 50 s
        assertThat(api.running()).contains(Duration.ofSeconds(51));
        assertThat(api.ready()).contains(Duration.ofSeconds(60)); // health check took 9 s
        assertThat(api.waitedFor()).contains("migrate"); // not cache: cache was up after 1 s
        assertThat(api.detail()).isEqualTo("port 8080 accepts connections");
        // cache has no health check: running is ready.
        assertThat(row(startup, "cache").ready()).contains(Duration.ofSeconds(1));
        assertThat(row(startup, "migrate").ready()).contains(Duration.ofSeconds(50));
    }

    @Test
    void theSlowestChainIsWhatHeldTheStackBack() {
        normalStartup(0);
        var startup = StartupTimeline.of(history, project, 5).getFirst();
        assertThat(startup.services().stream().filter(StartupTimeline.Row::slowestChain))
                .extracting(StartupTimeline.Row::service)
                .containsExactlyInAnyOrder("api", "migrate", "db");
        assertThat(row(startup, "cache").slowestChain()).isFalse();
    }

    @Test
    void aFailureEndsThatServicesBarAndTheStartupHasNoTotal() {
        state(0, "db", "stopped", "starting", "dependencies ready");
        state(0, "migrate", "stopped", "waiting", "start requested");
        state(2, "db", "starting", "running", "started");
        state(4, "db", "running", "crashed", "exited with error code 1");
        state(4, "migrate", "waiting", "blocked", "db crashed");
        var startup = StartupTimeline.of(history, project, 5).getFirst();
        assertThat(startup.took()).isEmpty();
        assertThat(row(startup, "db").outcome()).isEqualTo("failed");
        assertThat(row(startup, "db").ended()).contains(Duration.ofSeconds(4));
        assertThat(row(startup, "migrate").outcome()).isEqualTo("failed");
        assertThat(row(startup, "migrate").detail()).isEqualTo("db crashed");
    }

    @Test
    void separateStartupsNewestFirstAndRestartsWhileRunningAreNotStartups() {
        normalStartup(0);
        state(300, "api", "healthy", "stopping", "stop requested");
        state(301, "api", "stopping", "stopped", "stopped");
        normalStartup(1000);
        var startups = StartupTimeline.of(history, project, 5);
        assertThat(startups).hasSize(2);
        assertThat(startups.get(0).began()).isEqualTo(T0.plusSeconds(1000));
        assertThat(startups.get(1).took()).contains(Duration.ofSeconds(60));
        assertThat(StartupTimeline.of(history, project, 1)).hasSize(1);
    }

    @Test
    void stillStartingHasNoReadyTime() {
        state(0, "db", "stopped", "starting", "dependencies ready");
        state(2, "db", "starting", "running", "started");
        var db = row(StartupTimeline.of(history, project, 5).getFirst(), "db");
        assertThat(db.outcome()).isEqualTo("starting");
        assertThat(db.ready()).isEmpty();
    }

    private static StartupTimeline.Row row(StartupTimeline.Startup s, String service) {
        return s.services().stream()
                .filter(r -> r.service().equals(service))
                .findFirst()
                .orElseThrow();
    }

    private static ProjectSpec parse(String yaml) {
        try {
            return ConfigParser.parse(yaml, Path.of("/work/shop"), Map.of(), f -> Optional.empty());
        } catch (ConfigException e) {
            throw new AssertionError(e);
        }
    }
}
