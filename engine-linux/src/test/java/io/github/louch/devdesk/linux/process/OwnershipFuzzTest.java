package io.github.louch.devdesk.linux.process;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.louch.devdesk.core.leftovers.Leftover;
import io.github.louch.devdesk.core.process.GracefulStop;
import io.github.louch.devdesk.core.process.ProcessLaunchSpec;
import io.github.louch.devdesk.core.process.RunningProcess;
import io.github.louch.devdesk.core.process.Signal;
import io.github.louch.devdesk.core.project.ProjectSpec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Random;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Randomized: DevDesk starts, signals and stops processes of many shapes (children, grandchildren, ones that ignore
 * SIGTERM, ones that exit at once leaving a child), in both ownership modes, while "bystander" processes it didn't
 * start run next to them. Some bystanders even share the test's own process group, and forged leftover records point
 * at bystanders' groups. Every bystander records any signal it receives: at the end, none may have received one.
 *
 * <p>The seed is printed in a failure message, so a failing run can be replayed exactly.
 */
class OwnershipFuzzTest {

    private static final List<String> SHAPES = List.of(
            "sleep 30",
            "sh -c 'sleep 30 & sleep 30'",
            "sh -c 'trap \"\" TERM; sleep 30'",
            "true",
            "sh -c 'sleep 30 & exit 0'",
            "sh -c 'sleep 30 & sleep 30 & wait'");

    @TempDir
    Path dir;

    private final List<Process> bystanders = new ArrayList<>();

    @AfterEach
    void stopBystanders() {
        // The test's own children: stopped by the handle Java gave us, never by searching.
        bystanders.forEach(p -> p.descendants().forEach(ProcessHandle::destroyForcibly));
        bystanders.forEach(Process::destroyForcibly);
    }

    /** A process DevDesk didn't start: writes the name of any signal it gets into its own file. */
    private Process bystander(int n, boolean ownSession) throws IOException {
        Path log = dir.resolve("bystander-" + n + ".log");
        Files.writeString(log, "");
        String script = "for s in TERM INT HUP USR1 USR2; do trap \"echo $s >> " + log + "\" $s; done;"
                + " while true; do sleep 0.1; done";
        List<String> cmd = new ArrayList<>(ownSession ? List.of("setsid") : List.of());
        cmd.addAll(List.of("sh", "-c", script));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        bystanders.add(p);
        return p;
    }

    @Test
    void groupMode() throws Exception {
        fuzz(false, new Random().nextLong());
    }

    @Test
    void systemdScopeModeWhenAvailable() throws Exception {
        // Scope mode needs a user systemd; CI provides one, so this runs there (no skip).
        boolean scope =
                LinuxProcessRuntime.detect(Clock.systemUTC(), new Random()).usesSystemdScope();
        fuzz(scope, new Random().nextLong());
    }

    private void fuzz(boolean scope, long seed) throws Exception {
        Random random = new Random(seed);
        String why = "seed " + seed + (scope ? " (scope mode)" : " (group mode)");
        List<Process> others = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            others.add(bystander(i, i % 2 == 0)); // half in their own group, half in the test's own group
        }
        Thread.sleep(300); // let their traps be set
        Path records = dir.resolve("groups");
        var groups = new GroupLeftovers(records);
        var runtime = new LinuxProcessRuntime(scope, Clock.systemUTC(), random, Optional.of(groups));
        var stopper = new GracefulStop(runtime);
        ProjectSpec project = new ProjectSpec("fuzz", dir, Map.of(), Map.of());
        List<RunningProcess> ours = new ArrayList<>();

        for (int step = 0; step < 40; step++) {
            int action = random.nextInt(10);
            if (action < 4 || ours.isEmpty()) {
                String shape = SHAPES.get(random.nextInt(SHAPES.size()));
                ours.add(runtime.start(
                        new ProcessLaunchSpec(project.key(), "svc" + step, shape, dir, Map.of("PATH", "/usr/bin:/bin")),
                        line -> {}));
            } else if (action < 7) {
                RunningProcess p = ours.get(random.nextInt(ours.size()));
                runtime.signal(p, Signal.values()[random.nextInt(Signal.values().length)]);
            } else if (action < 9) {
                RunningProcess p = ours.remove(random.nextInt(ours.size()));
                stopper.stop(p, Duration.ofMillis(200 + random.nextInt(300)));
            } else {
                forgeRecordFor(records, project.key(), others.get(random.nextInt(others.size())), random);
                // A DevDesk starting again: finds leftovers and removes them. Forged records must find nothing.
                for (Leftover l : groups.find(project)) {
                    for (Process b : others) {
                        assertThat(l.pids())
                                .as(why + ": a bystander was claimed as a leftover")
                                .doesNotContain(b.pid());
                    }
                }
            }
        }
        for (RunningProcess p : ours) {
            stopper.stop(p, Duration.ofMillis(300));
            assertThat(runtime.members(p))
                    .as(why + ": " + p.name() + " left something")
                    .isEmpty();
        }
        Thread.sleep(300); // a signal sent at the very end must still reach its trap before we look
        for (int i = 0; i < others.size(); i++) {
            assertThat(others.get(i).isAlive())
                    .as(why + ": bystander " + i + " died")
                    .isTrue();
            assertThat(Files.readString(dir.resolve("bystander-" + i + ".log")))
                    .as(why + ": bystander " + i + " received a signal")
                    .isEmpty();
        }
        assertThat(groups.find(project))
                .as(why + ": records left after a clean stop")
                .isEmpty();
    }

    /**
     * A record that names a bystander's process group, as if the group id had been reused after a crash: either from
     * now (the bystander started before it, so it isn't ours) or from another boot.
     */
    private static void forgeRecordFor(Path records, String owner, Process bystander, Random random)
            throws IOException {
        long group = ProcStat.read(bystander.pid()).orElseThrow().processGroup();
        Properties p = new Properties();
        p.setProperty("service", "forged");
        p.setProperty("group", Long.toString(group));
        boolean otherBoot = random.nextBoolean();
        p.setProperty("notBefore", Long.toString(otherBoot ? 0 : LinuxProcessRuntime.ticksSinceBoot() + 1));
        p.setProperty("boot", otherBoot ? "another-boot" : GroupLeftovers.bootId());
        p.setProperty("command", "forged");
        p.setProperty("marker", Long.toHexString(random.nextLong())); // a marker no bystander carries
        Path d = Files.createDirectories(records.resolve(owner));
        try (var w = Files.newBufferedWriter(d.resolve(group + ".group"))) {
            p.store(w, null);
        }
    }

    @Test
    void aRecordNamingABystandersGroupFindsNothingAndRemovesNothing() throws Exception {
        Process b = bystander(0, true);
        Thread.sleep(300);
        Path records = dir.resolve("groups");
        ProjectSpec project = new ProjectSpec("p", dir, Map.of(), Map.of());
        for (int i = 0; i < 2; i++) { // once from this boot (too late to be ours), once from another boot
            forgeRecordFor(records, project.key(), b, new Random(i));
            var groups = new GroupLeftovers(records);

            assertThat(groups.find(project)).isEmpty();
        }
        assertThat(b.isAlive()).isTrue();
        assertThat(Files.readString(dir.resolve("bystander-0.log"))).isEmpty();
    }
}
