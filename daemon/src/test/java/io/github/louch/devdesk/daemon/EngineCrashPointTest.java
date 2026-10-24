package io.github.louch.devdesk.daemon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.louch.devdesk.core.crashpoint.CrashPoint;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The desktop app's engine killed with SIGKILL at every crash point (see {@link CrashPoint}), in both ownership modes.
 * A new engine then opens and starts the project: what the dead one left is reported in the history and stopped, and
 * after the app goes away (its lifeline closes) nothing remains.
 */
@Tag("docker")
class EngineCrashPointTest {

    private static String registryId;
    private static int registryPort;

    @BeforeAll
    static void startRegistry() throws Exception {
        assumeTrue(run("docker", "info").ok(), "Docker is not available");
        for (String image : List.of("alpine:3", "registry:2")) {
            if (!run("docker", "image", "inspect", image).ok()) {
                assertThat(run("docker", "pull", "-q", image).ok()).isTrue();
            }
        }
        registryPort = freePort();
        var started = run("docker", "run", "-d", "--rm", "-p", "127.0.0.1:" + registryPort + ":5000", "registry:2");
        assertThat(started.ok()).as(started.out()).isTrue();
        registryId = started.out().strip();
        for (int i = 0;
                i < 50
                        && !run("curl", "-sf", "http://127.0.0.1:" + registryPort + "/v2/")
                                .ok();
                i++) {
            Thread.sleep(200);
        }
    }

    @AfterAll
    static void stopRegistry() {
        if (registryId != null) {
            run("docker", "rm", "-f", registryId); // the container this test started, by its id
        }
    }

    static Stream<Arguments> cases() {
        List<Arguments> out = new ArrayList<>();
        boolean systemd = systemdUserSession();
        for (String point : CrashPoint.ALL) {
            out.add(Arguments.of(point, false));
            if (systemd) {
                out.add(Arguments.of(point, true));
            }
        }
        return out.stream();
    }

    @TempDir
    Path home;

    @ParameterizedTest(name = "{0}, systemd scope mode: {1}")
    @MethodSource("cases")
    void aKillAtThisPointLeavesNothingBehind(String point, boolean scope) throws Exception {
        String name = "ecrash-" + UUID.randomUUID().toString().substring(0, 8);
        long unique = 30_000 + Math.abs(name.hashCode() % 20_000);
        int dbPort = freePort();
        int apiPort = freePort();
        String image = "alpine:3";
        if (point.equals("container.pulling")) {
            image = "127.0.0.1:" + registryPort + "/" + name + ":latest";
            assertThat(run("docker", "tag", "alpine:3", image).ok()).isTrue();
            assertThat(run("docker", "push", "-q", image).ok()).isTrue();
            assertThat(run("docker", "rmi", image).ok()).isTrue(); // only the tag this test just made
        }
        Path project = Files.createDirectories(home.resolve(name));
        Files.writeString(project.resolve("devdesk.yaml"), """
                version: 1
                name: %s
                services:
                  db:
                    container:
                      image: %s
                      ports: ["%d:8080"]
                      command: sh -c "trap 'exit 0' TERM; while true; do nc -l -p 8080 >/dev/null & wait $!; done"
                    health: { type: tcp, port: %d, start_period: 20s }
                  api:
                    command: sh -c 'sleep %d & exec python3 -m http.server %d --bind 127.0.0.1'
                    depends_on: { db: { condition: healthy } }
                    ports: [%d]
                    health: { type: http, url: "http://127.0.0.1:%d/", start_period: 20s }
                """.formatted(
                        name, image, dbPort, dbPort, unique, apiPort, apiPort, apiPort));
        String open = "{\"path\":\"" + project + "\"}";
        Path reached = home.resolve("reached");

        // Engine 1: until the crash point, then SIGKILL.
        var first = TestEngine.start(
                home,
                null,
                !scope,
                List.of("-Ddevdesk.test.crashPoint=" + point, "-Ddevdesk.test.crashPointFile=" + reached));
        try {
            assertThat(first.call("POST /project/open", open).statusCode()).isEqualTo(200);
            assertThat(first.call("POST /project/trust").statusCode()).isEqualTo(200);
            // Sent from another thread: the engine may freeze inside this very request when the point is reached.
            Thread.ofVirtual().start(() -> {
                try {
                    first.call("POST /project/up");
                } catch (Exception e) {
                    // the engine was killed mid-request: expected
                }
            });
            if (point.startsWith("stop.") || point.equals("container.stop-sent")) {
                awaitAllHealthy(first);
                first.process().getOutputStream().close(); // the app goes away: the engine stops everything
            }
            awaitFile(reached, first.process());
            assertThat(Files.readString(reached)).startsWith(point);
        } finally {
            first.process().destroyForcibly(); // SIGKILL, exactly at the point
            first.process().waitFor(10, TimeUnit.SECONDS);
        }
        boolean leftSomething =
                !processes(unique).isEmpty() || !containers(name).isEmpty();

        // Engine 2: opens and starts the project; survivors are reported, then everything is up.
        try (var second = TestEngine.start(home, null, !scope)) {
            assertThat(second.call("POST /project/open", open).statusCode()).isEqualTo(200);
            second.call("POST /project/up");
            awaitAllHealthy(second);
            if (leftSomething) {
                assertThat(second.call("GET /project/history?hours=1").body())
                        .as(point + ": survivors reported")
                        .contains("left behind by a DevDesk that was killed");
            }
        } // close(): the lifeline closes, the engine stops everything and exits

        assertThat(processes(unique)).as(point + ": processes left").isEmpty();
        assertThat(containers(name)).as(point + ": containers left").isEmpty();
        assertThat(scopes(name)).as(point + ": systemd scopes left").isEmpty();
        Path records = home.resolve("state/devdesk/groups");
        if (Files.isDirectory(records)) {
            try (var files = Files.walk(records)) {
                assertThat(files.filter(f -> f.toString().endsWith(".group")).toList())
                        .as(point + ": group records left")
                        .isEmpty();
            }
        }
        if (image.startsWith("127.0.0.1:")) {
            run("docker", "rmi", image); // the copy this test's run downloaded
        }
    }

    // ---- helpers ----

    @SuppressWarnings("unchecked")
    private static void awaitAllHealthy(TestEngine engine) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
        String last = "";
        while (System.nanoTime() < deadline) {
            last = engine.call("GET /project").body();
            Map<String, Object> view = ApiJson.MAPPER.readValue(last, Map.class);
            List<Map<String, Object>> services = (List<Map<String, Object>>) view.get("services");
            if (services != null
                    && !services.isEmpty()
                    && services.stream()
                            .allMatch(s -> "healthy".equals(((Map<String, Object>) s.get("state")).get("label")))) {
                return;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("never all healthy: " + last);
    }

    private static void awaitFile(Path file, Process p) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
        while (System.nanoTime() < deadline && !Files.exists(file) && p.isAlive()) {
            Thread.sleep(50);
        }
        assertThat(Files.exists(file)).as("crash point never reached").isTrue();
    }

    private static List<Long> processes(long unique) throws IOException {
        List<Long> out = new ArrayList<>();
        try (var dirs = Files.list(Path.of("/proc"))) {
            for (Path d :
                    dirs.filter(d -> d.getFileName().toString().matches("\\d+")).toList()) {
                try {
                    String cmd = Files.readString(d.resolve("cmdline"))
                            .replace('\0', ' ')
                            .strip();
                    String stat = Files.readString(d.resolve("stat"));
                    boolean zombie = stat.substring(stat.lastIndexOf(')') + 2).startsWith("Z");
                    if (!zombie && cmd.equals("sleep " + unique)) {
                        out.add(Long.parseLong(d.getFileName().toString()));
                    }
                } catch (IOException e) {
                    // exited while we looked
                }
            }
        }
        return out;
    }

    private static List<String> containers(String project) {
        return run("docker", "ps", "-a", "--filter", "label=devdesk.project=" + project, "--format", "{{.Names}}")
                .out()
                .lines()
                .filter(l -> !l.isBlank())
                .toList();
    }

    private static List<String> scopes(String project) {
        return run("systemctl", "--user", "list-units", "--all", "--plain", "--no-legend", "devdesk-" + project + "-*")
                .out()
                .lines()
                .filter(l -> l.startsWith("devdesk-"))
                .toList();
    }

    private static boolean systemdUserSession() {
        String s = run("systemctl", "--user", "is-system-running").out().strip();
        return s.equals("running") || s.equals("degraded");
    }

    private static int freePort() throws IOException {
        try (var s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private record Result(boolean ok, String out) {}

    private static Result run(String... command) {
        try {
            Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return new Result(p.waitFor(120, TimeUnit.SECONDS) && p.exitValue() == 0, out);
        } catch (IOException e) {
            return new Result(false, e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(false, "interrupted");
        }
    }
}
