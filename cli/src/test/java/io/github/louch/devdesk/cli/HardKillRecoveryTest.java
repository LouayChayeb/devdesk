package io.github.louch.devdesk.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * DevDesk itself is SIGKILLed while a project runs (no shutdown hook, no cleanup). Its services survive, as they must
 * (they're in their own session). The next `devdesk up` gets the project's lock (the kernel dropped the dead one's),
 * finds exactly those survivors, says so, and stops them before starting again. Both ownership modes.
 */
class HardKillRecoveryTest {

    private static final Pattern PID = Pattern.compile("PID (\\d+)");

    @TempDir
    Path home;

    @Test
    void groupMode() throws Exception {
        recoversAfterAHardKill(false);
    }

    @Test
    void systemdScopeMode() throws Exception {
        assumeTrue(systemdUserSession(), "no systemd user session on this machine");
        recoversAfterAHardKill(true);
    }

    private void recoversAfterAHardKill(boolean scope) throws Exception {
        Path project = Files.createDirectories(home.resolve("project"));
        // A child and a grandchild: both must be found again and stopped.
        Files.writeString(project.resolve("devdesk.yaml"), """
                version: 1
                name: hardkill
                services:
                  worker:
                    command: sh -c 'sleep 300 & sleep 300'
                """);
        Process trust = cli(scope, project, "trust");
        trust.getOutputStream().write("y\n".getBytes(StandardCharsets.UTF_8));
        trust.getOutputStream().close();
        assertThat(trust.waitFor(30, TimeUnit.SECONDS)).isTrue();

        Process first = cli(scope, project, "up");
        var firstOut = lines(first);
        long pid = waitForPid(firstOut, "worker running");
        awaitUp(firstOut);
        Set<Long> family = group(pid);
        assertThat(family).as("worker, its sh and both sleeps").hasSizeGreaterThanOrEqualTo(2);

        first.destroyForcibly(); // SIGKILL: no shutdown hook runs, nothing gets cleaned up
        assertThat(first.waitFor(10, TimeUnit.SECONDS)).isTrue();
        assertThat(group(pid)).as("the services outlive the killed DevDesk").isEqualTo(family);

        Process second = cli(scope, project, "up");
        var secondOut = lines(second);
        try {
            List<String> seen = awaitUp(secondOut);
            assertThat(String.join("\n", seen))
                    .contains("A previous DevDesk was killed or crashed")
                    .contains("worker")
                    .contains(String.valueOf(pid));
            assertThat(group(pid)).as("the survivors were stopped").isEmpty();
        } finally {
            second.destroy(); // SIGTERM: like Ctrl+C, its shutdown hook stops everything
            assertThat(second.waitFor(30, TimeUnit.SECONDS)).isTrue();
        }
        long newPid = 0;
        // The second run's own worker is gone too after its normal stop.
        for (String l : List.copyOf(secondOut)) {
            Matcher m = PID.matcher(l);
            if (l.contains("worker running") && m.find()) {
                newPid = Long.parseLong(m.group(1));
            }
        }
        assertThat(newPid).isPositive();
        assertThat(group(newPid)).isEmpty();
    }

    private Process cli(boolean scope, Path dir, String command) throws IOException {
        var b = new ProcessBuilder(
                        "java",
                        "--enable-native-access=ALL-UNNAMED",
                        "-cp",
                        System.getProperty("java.class.path"),
                        Main.class.getName(),
                        command)
                .directory(dir.toFile())
                .redirectErrorStream(true);
        Map<String, String> env = b.environment();
        env.put("HOME", home.toString());
        env.put("XDG_STATE_HOME", home.resolve("state").toString());
        if (!scope) {
            // Without these, `systemctl --user` can't reach the user's systemd: DevDesk falls back to process groups.
            env.remove("XDG_RUNTIME_DIR");
            env.remove("DBUS_SESSION_BUS_ADDRESS");
        }
        return b.start();
    }

    /** Every output line, as it comes. */
    private static LinkedBlockingQueue<String> lines(Process p) {
        var q = new LinkedBlockingQueue<String>();
        Thread.ofVirtual().start(() -> {
            try (var r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                r.lines().forEach(q::add);
            } catch (IOException e) {
                // the process is gone
            }
        });
        return q;
    }

    private static long waitForPid(LinkedBlockingQueue<String> q, String marker) throws InterruptedException {
        List<String> seen = new ArrayList<>();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            String l = q.poll(1, TimeUnit.SECONDS);
            if (l == null) {
                continue;
            }
            seen.add(l);
            Matcher m = PID.matcher(l);
            if (l.contains(marker) && m.find()) {
                return Long.parseLong(m.group(1));
            }
        }
        throw new AssertionError("no \"" + marker + "\" line; got: " + seen);
    }

    /** Lines until "is up" (the summary), kept in the queue's place for later checks too. */
    private static List<String> awaitUp(LinkedBlockingQueue<String> q) throws InterruptedException {
        List<String> seen = new ArrayList<>();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (System.nanoTime() < deadline) {
            String l = q.poll(1, TimeUnit.SECONDS);
            if (l == null) {
                continue;
            }
            seen.add(l);
            if (l.contains("is up.") || l.contains("are up.")) {
                q.addAll(seen); // keep them readable for the PID lookup afterwards
                return seen;
            }
        }
        throw new AssertionError("never came up; got: " + seen);
    }

    /** Live (non-zombie) processes whose process group is {@code group}. */
    private static Set<Long> group(long group) throws IOException {
        try (Stream<Path> entries = Files.list(Path.of("/proc"))) {
            return entries.map(p -> p.getFileName().toString())
                    .filter(n -> n.chars().allMatch(Character::isDigit))
                    .filter(n -> {
                        try {
                            String stat = Files.readString(Path.of("/proc", n, "stat"));
                            String[] f =
                                    stat.substring(stat.lastIndexOf(')') + 2).split(" ");
                            return !f[0].equals("Z") && Long.parseLong(f[2]) == group;
                        } catch (IOException e) {
                            return false; // exited while we looked
                        }
                    })
                    .map(Long::parseLong)
                    .collect(Collectors.toUnmodifiableSet());
        }
    }

    private static boolean systemdUserSession() {
        try {
            Process p = new ProcessBuilder("systemctl", "--user", "is-system-running")
                    .redirectErrorStream(true)
                    .start();
            String s = new String(p.getInputStream().readAllBytes()).strip();
            return s.equals("running") || s.equals("degraded");
        } catch (IOException e) {
            return false;
        }
    }
}
