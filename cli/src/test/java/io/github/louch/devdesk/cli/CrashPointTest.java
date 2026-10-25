package io.github.louch.devdesk.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.louch.devdesk.core.crashpoint.CrashPoint;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
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
 * Kills `devdesk up` with SIGKILL at every crash point (see {@link CrashPoint}), in both ownership modes, then starts
 * it again and proves: what the dead one left was reported and stopped, and after a normal stop nothing at all
 * remains (no process, no systemd scope, no container, no group record).
 *
 * <p>The project: a container (alpine answering TCP, health-checked) and, depending on it, a native service with a
 * background child. For the image-download point the container's image comes from a throwaway local registry, so
 * the download is real and nothing on the machine is deleted.
 */
@Tag("docker")
class CrashPointTest {

    private static String registryId;
    private static int registryPort;

    /** Starts a local registry and pushes a copy of alpine:3 into it, for the "container.pulling" point. */
    @BeforeAll
    static void startRegistry() throws Exception {
        assumeTrue(run("docker", "info").ok(), "Docker is not available");
        if (!run("docker", "image", "inspect", "alpine:3").ok()) {
            assertThat(run("docker", "pull", "-q", "alpine:3").ok()).isTrue();
        }
        if (!run("docker", "image", "inspect", "registry:2").ok()) {
            assertThat(run("docker", "pull", "-q", "registry:2").ok()).isTrue();
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
        for (String point : CrashPoint.ALL) {
            out.add(Arguments.of(point, false));
            if (systemdUserSession()) {
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
        String name = "crash-" + UUID.randomUUID().toString().substring(0, 8);
        String tag = "sleep-" + Math.abs(UUID.randomUUID().getLeastSignificantBits() % 100000);
        int dbPort = freePort();
        int apiPort = freePort();
        String image = "alpine:3";
        if (point.equals("container.pulling")) {
            // A fresh name in our registry, not on this machine: starting must download it.
            image = "127.0.0.1:" + registryPort + "/" + name + ":latest";
            assertThat(run("docker", "tag", "alpine:3", image).ok()).isTrue();
            assertThat(run("docker", "push", "-q", image).ok()).isTrue();
            assertThat(run("docker", "rmi", image).ok()).isTrue(); // only the tag this test just made
        }
        Path project = Files.createDirectories(home.resolve(name));
        // A unique number in the native service's sleep: how the test finds its processes, never by guessing.
        long unique = 30_000 + Math.abs(tag.hashCode() % 20_000);
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
        Process trust = cli(scope, project, null, "trust", "--yes");
        assertThat(trust.waitFor(60, TimeUnit.SECONDS)).isTrue();

        // Run 1: until the crash point, then SIGKILL.
        Path reached = home.resolve("reached");
        Process first = cli(scope, project, point, "up");
        var firstOut = drain(first);
        boolean stopPoint = point.startsWith("stop.") || point.equals("container.stop-sent");
        if (stopPoint) {
            awaitLine(firstOut, "are up.", first);
            first.destroy(); // SIGTERM, like Ctrl+C: the stop runs until it reaches the point
        }
        awaitFile(reached, first, firstOut);
        assertThat(Files.readString(reached)).startsWith(point);
        first.destroyForcibly(); // SIGKILL, exactly at the point
        assertThat(first.waitFor(10, TimeUnit.SECONDS)).isTrue();

        boolean leftSomething =
                !processes(unique).isEmpty() || !containers(name).isEmpty();

        // Run 2: a normal start. What run 1 left is reported and stopped, then everything comes up.
        Process second = cli(scope, project, null, "up");
        var secondOut = drain(second);
        try {
            awaitLine(secondOut, "are up.", second);
            String text = String.join("\n", secondOut);
            if (leftSomething) {
                assertThat(text)
                        .as(point + ": survivors reported")
                        .contains("A previous DevDesk was killed or crashed");
            }
        } finally {
            second.destroy(); // SIGTERM: ordered stop
            assertThat(second.waitFor(60, TimeUnit.SECONDS)).isTrue();
        }

        // Nothing remains: processes, scopes, containers, records.
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

    /** Builds a local image from a one-line Dockerfile body, tagged {@code tag} (a tag this test owns). */
    private static void build(String tag, String body) throws Exception {
        Process p = new ProcessBuilder("docker", "build", "-q", "-t", tag, "-")
                .redirectErrorStream(true)
                .start();
        p.getOutputStream().write(("FROM alpine:3\n" + body + "\n").getBytes(StandardCharsets.UTF_8));
        p.getOutputStream().close();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.waitFor()).as(out).isZero();
    }

    private static List<String> proofs() {
        String c = run("docker", "ps", "-a", "--filter", "label=devdesk.proof", "--format", "{{.Names}}")
                .out();
        String n = run("docker", "network", "ls", "--filter", "label=devdesk.proof", "--format", "{{.Name}}")
                .out();
        return Stream.concat(c.lines(), n.lines()).filter(l -> !l.isBlank()).toList();
    }

    /** "Prove it" killed while its replicas run: the next DevDesk removes them (and only them). */
    @org.junit.jupiter.api.Test
    void aKillDuringProveItLeavesNoReplicas() throws Exception {
        String name = "crash-proof-" + UUID.randomUUID().toString().substring(0, 8);
        String image = name + ":latest";
        int port = freePort();
        Path project = Files.createDirectories(home.resolve(name));
        Files.writeString(project.resolve("devdesk.yaml"), """
                version: 1
                name: %s
                services:
                  greeter:
                    container: { image: "%s", ports: ["%d:8080"] }
                    health: { type: tcp, port: %d, start_period: 20s }
                """.formatted(name, image, port, port));
        try {
            build(
                    image,
                    "CMD [\"sh\", \"-c\", \"trap 'exit 0' TERM; while true; do nc -l -p 8080 >/dev/null & wait $!; done\"]");
            assertThat(cli(false, project, null, "trust", "--yes").waitFor(60, TimeUnit.SECONDS))
                    .isTrue();
            // A clean start, so DevDesk remembers the image id.
            Process up = cli(false, project, null, "up");
            var upOut = drain(up);
            awaitLine(upOut, "is up.", up);
            up.destroy();
            assertThat(up.waitFor(60, TimeUnit.SECONDS)).isTrue();
            // The tag now names a broken image: the verdict has an image change to prove.
            build(image, "LABEL devdesk.test=v2\nCMD [\"sh\", \"-c\", \"exit 1\"]");

            Path reached = home.resolve("reached");
            Process why = cli(false, project, "proof.started", "why", "--prove");
            var whyOut = drain(why);
            awaitFile(reached, why, whyOut);
            why.destroyForcibly(); // SIGKILL with a replica running
            assertThat(why.waitFor(10, TimeUnit.SECONDS)).isTrue();
            assertThat(proofs()).as("the killed proof left its replicas").isNotEmpty();

            // The next DevDesk, here a normal `why --prove`, removes them before its own proof, and leaves none.
            Process again = cli(false, project, null, "why", "--prove");
            var againOut = drain(again);
            assertThat(again.waitFor(120, TimeUnit.SECONDS)).isTrue();
            assertThat(String.join("\n", againOut)).contains("PROVEN");
            assertThat(proofs()).as("replicas left after the next proof").isEmpty();
        } finally {
            run("docker", "rmi", "-f", image); // the image this test built
        }
    }

    private static boolean nonEmpty(Path file) {
        try {
            return Files.exists(file) && Files.size(file) > 0;
        } catch (IOException e) {
            return false;
        }
    }

    // ---- helpers ----

    private Process cli(boolean scope, Path dir, String point, String... args) throws IOException {
        List<String> cmd = new ArrayList<>(List.of("java", "--enable-native-access=ALL-UNNAMED"));
        if (point != null) {
            cmd.add("-Ddevdesk.test.crashPoint=" + point);
            cmd.add("-Ddevdesk.test.crashPointFile=" + home.resolve("reached"));
        }
        cmd.addAll(List.of("-cp", System.getProperty("java.class.path"), Main.class.getName()));
        cmd.addAll(List.of(args));
        var b = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true);
        Map<String, String> env = b.environment();
        env.put("HOME", home.toString());
        env.put("XDG_STATE_HOME", home.resolve("state").toString());
        if (!scope) {
            env.remove("XDG_RUNTIME_DIR");
            env.remove("DBUS_SESSION_BUS_ADDRESS");
        }
        return b.start();
    }

    private static List<String> drain(Process p) {
        List<String> lines = new java.util.concurrent.CopyOnWriteArrayList<>();
        Thread.ofVirtual().start(() -> {
            try (var r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                r.lines().forEach(lines::add);
            } catch (IOException e) {
                // the process is gone
            }
        });
        return lines;
    }

    private static void awaitLine(List<String> lines, String text, Process p) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
        while (System.nanoTime() < deadline) {
            if (lines.stream().anyMatch(l -> l.contains(text))) {
                return;
            }
            if (!p.isAlive()) {
                break;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("never printed \"" + text + "\":\n" + String.join("\n", lines));
    }

    private static void awaitFile(Path file, Process p, List<String> out) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
        while (System.nanoTime() < deadline && !nonEmpty(file)) {
            if (!p.isAlive()) {
                break;
            }
            Thread.sleep(50);
        }
        assertThat(nonEmpty(file))
                .as("crash point never reached:\n" + String.join("\n", out))
                .isTrue();
    }

    /** Live processes of the native service: its sleep carries a number unique to this test case. */
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
        String out = run("docker", "ps", "-a", "--filter", "label=devdesk.project=" + project, "--format", "{{.Names}}")
                .out();
        return out.lines().filter(l -> !l.isBlank()).toList();
    }

    private static List<String> scopes(String project) {
        String out = run(
                        "systemctl",
                        "--user",
                        "list-units",
                        "--all",
                        "--plain",
                        "--no-legend",
                        "devdesk-" + project + "-*")
                .out();
        return out.lines().filter(l -> l.startsWith("devdesk-")).toList();
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
