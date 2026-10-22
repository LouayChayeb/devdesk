package io.github.louch.devdesk.daemon;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The app's engine is SIGKILLed while a project runs (process-group mode: no systemd to remember anything). The next
 * engine to open the project holds its lock, finds the survivors from the group records, stops them, and says so in
 * the project's history.
 */
class EngineHardKillTest {

    @TempDir
    Path home;

    @Test
    void theNextEngineStopsAndReportsWhatAKilledOneLeft() throws Exception {
        Path project = Files.createDirectories(home.resolve("project"));
        Files.writeString(project.resolve("devdesk.yaml"), """
                version: 1
                name: enginekill
                services:
                  worker:
                    command: sh -c 'sleep 300 & sleep 300'
                """);
        String open = "{\"path\":\"" + project + "\"}";

        long pid;
        var first = TestEngine.start(home, null, true);
        try {
            assertThat(first.call("POST /project/open", open).statusCode()).isEqualTo(200);
            assertThat(first.call("POST /project/trust").statusCode()).isEqualTo(200);
            first.call("POST /project/up");
            pid = waitForWorkerPid(first);
            assertThat(group(pid)).hasSizeGreaterThanOrEqualTo(2);
        } finally {
            first.process().destroyForcibly(); // SIGKILL: no shutdown hook, nothing stopped
            first.process().waitFor(10, TimeUnit.SECONDS);
        }
        assertThat(group(pid)).as("the services outlive the killed engine").isNotEmpty();

        try (var second = TestEngine.start(home, null, true)) {
            assertThat(second.call("POST /project/open", open).statusCode()).isEqualTo(200);
            second.call("POST /project/up"); // takes the lock, then cleans up survivors before starting
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (!group(pid).isEmpty() && System.nanoTime() < deadline) {
                Thread.sleep(200);
            }
            assertThat(group(pid)).as("the survivors were stopped").isEmpty();
            assertThat(second.call("GET /project/history?hours=1").body())
                    .contains("left behind by a DevDesk that was killed")
                    .contains(String.valueOf(pid));
        }
    }

    private static long waitForWorkerPid(TestEngine engine) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            Map<?, ?> view =
                    ApiJson.MAPPER.readValue(engine.call("GET /project").body(), Map.class);
            for (Object s : (Iterable<?>) view.get("services")) {
                Map<?, ?> state = (Map<?, ?>) ((Map<?, ?>) s).get("state");
                if (state != null && state.get("pid") instanceof Number n && n.longValue() > 0) {
                    return n.longValue();
                }
            }
            Thread.sleep(200);
        }
        throw new AssertionError("worker never ran");
    }

    /** Live (non-zombie) processes whose process group is {@code group}. */
    private static Set<Long> group(long group) throws Exception {
        try (Stream<Path> entries = Files.list(Path.of("/proc"))) {
            return entries.map(p -> p.getFileName().toString())
                    .filter(n -> n.chars().allMatch(Character::isDigit))
                    .filter(n -> {
                        try {
                            String stat = Files.readString(Path.of("/proc", n, "stat"));
                            String[] f =
                                    stat.substring(stat.lastIndexOf(')') + 2).split(" ");
                            return !f[0].equals("Z") && Long.parseLong(f[2]) == group;
                        } catch (java.io.IOException e) {
                            return false;
                        }
                    })
                    .map(Long::parseLong)
                    .collect(Collectors.toUnmodifiableSet());
        }
    }
}
