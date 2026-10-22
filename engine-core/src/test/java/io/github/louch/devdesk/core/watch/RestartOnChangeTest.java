package io.github.louch.devdesk.core.watch;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RestartOnChangeTest {

    /** A clock the test moves by hand. */
    private static final class Hand extends Clock {
        Instant now = Instant.parse("2026-10-13T10:00:00Z");

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }

        void advance(long millis) {
            now = now.plusMillis(millis);
        }
    }

    private final Hand clock = new Hand();
    private final RestartOnChange watcher = new RestartOnChange(clock, Duration.ofMillis(500));
    private final Path a = Path.of("/p/api/a.js");
    private final Path b = Path.of("/p/api/b.js");

    @Test
    void theFirstLookIsTheStartingPointNotAChange() {
        assertThat(watcher.tick(Map.of("api", Map.of(a, 1L)))).isEmpty();
        clock.advance(2000);
        assertThat(watcher.tick(Map.of("api", Map.of(a, 1L)))).isEmpty();
    }

    @Test
    void aBurstOfSavesIsOneRestartAfterItsQuiet() {
        watcher.tick(Map.of("api", Map.of(a, 1L, b, 1L)));
        clock.advance(100);
        assertThat(watcher.tick(Map.of("api", Map.of(a, 2L, b, 1L)))).isEmpty(); // changed, but not quiet yet
        clock.advance(200);
        assertThat(watcher.tick(Map.of("api", Map.of(a, 2L, b, 3L)))).isEmpty(); // still saving
        clock.advance(300);
        assertThat(watcher.tick(Map.of("api", Map.of(a, 2L, b, 3L)))).isEmpty(); // 300 ms quiet: not enough
        clock.advance(300);

        assertThat(watcher.tick(Map.of("api", Map.of(a, 2L, b, 3L))))
                .containsExactly(new RestartOnChange.Restart("api", java.util.List.of(a, b)));
        clock.advance(1000);
        assertThat(watcher.tick(Map.of("api", Map.of(a, 2L, b, 3L)))).isEmpty(); // once only
    }

    @Test
    void newAndDeletedFilesCountAndServicesAreSeparate() {
        watcher.tick(Map.of("api", Map.of(a, 1L), "web", Map.of(b, 1L)));
        clock.advance(100);
        watcher.tick(Map.of("api", Map.of(), "web", Map.of(b, 1L, Path.of("/p/web/new.css"), 5L)));
        clock.advance(600);

        assertThat(watcher.tick(Map.of("api", Map.of(), "web", Map.of(b, 1L, Path.of("/p/web/new.css"), 5L))))
                .containsExactlyInAnyOrder(
                        new RestartOnChange.Restart("api", java.util.List.of(a)),
                        new RestartOnChange.Restart("web", java.util.List.of(Path.of("/p/web/new.css"))));
    }

    @Test
    void dependenciesBuildOutputAndEditorTempFilesNeverCount() {
        assertThat(WatchRules.ignored(Path.of("node_modules/express/index.js"))).isTrue();
        assertThat(WatchRules.ignored(Path.of("src/__pycache__/x.cpython-312.pyc")))
                .isTrue();
        assertThat(WatchRules.ignored(Path.of("src/.server.js.swp"))).isTrue();
        assertThat(WatchRules.ignored(Path.of("app.log"))).isTrue();
        assertThat(WatchRules.ignored(Path.of("src/server.js"))).isFalse();
        assertThat(WatchRules.ignored(Path.of("templates/build.html"))).isFalse(); // a file called build is fine
    }
}
