package io.github.louch.devdesk.linux.process;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.louch.devdesk.core.process.GracefulStop;
import io.github.louch.devdesk.core.process.ProcessLaunchSpec;
import io.github.louch.devdesk.core.project.ProjectSpec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Group mode with records: the DEVDESK_GROUP marker makes the family complete, even past setsid. */
class GroupMarkerTest {

    @TempDir
    Path dir;

    private final ProjectSpec project = new ProjectSpec("marker", Path.of("/tmp/marker-test"), Map.of(), Map.of());

    private LinuxProcessRuntime runtime(GroupLeftovers records) {
        return new LinuxProcessRuntime(false, Clock.systemUTC(), new Random(), Optional.of(records));
    }

    @Test
    void aChildThatEscapedItsGroupWithSetsidIsStillStopped() throws Exception {
        var records = new GroupLeftovers(dir.resolve("groups"));
        var runtime = runtime(records);
        // The grandchild calls setsid: a new process group, which a plain group signal would miss.
        var p = runtime.start(
                new ProcessLaunchSpec(
                        project.key(), "api", "setsid sleep 300 & sleep 300", dir, Map.of("PATH", "/usr/bin:/bin")),
                line -> {});
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (runtime.members(p).size() < 3 && System.nanoTime() < deadline) {
            Thread.sleep(20); // sh, the escaped sleep, the other sleep
        }
        assertThat(runtime.members(p)).hasSizeGreaterThanOrEqualTo(3);

        new GracefulStop(runtime).stop(p, Duration.ofSeconds(2));

        assertThat(runtime.members(p)).isEmpty();
        assertThat(records.find(project)).isEmpty();
    }

    @Test
    void theRecordExistsBeforeTheProcessDoes() throws Exception {
        var records = new GroupLeftovers(dir.resolve("groups"));
        var runtime = runtime(records);

        var p = runtime.start(
                new ProcessLaunchSpec(project.key(), "api", "sleep 300", dir, Map.of("PATH", "/usr/bin:/bin")),
                line -> {});
        try {
            // Written before the fork; found by the marker every member carries.
            try (var files = Files.list(dir.resolve("groups").resolve(project.key()))) {
                assertThat(files.filter(f -> f.toString().endsWith(".group")).count())
                        .isEqualTo(1);
            }
            // Members first, then the record: sh may fork its child between the two looks, and what already ran must
            // be found (a child in the middle of exec too, whose environment the kernel briefly shows as empty).
            var members = runtime.members(p);
            assertThat(records.find(project))
                    .singleElement()
                    .satisfies(l -> assertThat(l.pids()).containsAll(members));
        } finally {
            new GracefulStop(runtime).stop(p, Duration.ofSeconds(2));
        }
        assertThat(records.find(project)).isEmpty();
    }
}
