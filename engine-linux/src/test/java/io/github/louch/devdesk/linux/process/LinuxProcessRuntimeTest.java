package io.github.louch.devdesk.linux.process;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.louch.devdesk.core.process.ExitStatus;
import io.github.louch.devdesk.core.process.GracefulStop;
import io.github.louch.devdesk.core.process.OutputLine;
import io.github.louch.devdesk.core.process.ProcessLaunchSpec;
import io.github.louch.devdesk.core.process.RunningProcess;
import io.github.louch.devdesk.core.process.StopResult;
import io.github.louch.devdesk.core.process.StreamKind;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Queue;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Real processes on the real kernel, in both ownership modes (true = systemd scope, false = process group only). */
class LinuxProcessRuntimeTest {

    private static final boolean SYSTEMD =
            LinuxProcessRuntime.detect(Clock.systemUTC(), new Random()).usesSystemdScope();
    private static final Duration GRACE = Duration.ofSeconds(2);

    private final Queue<OutputLine> lines = new ConcurrentLinkedQueue<>();

    private LinuxProcessRuntime runtime(boolean scope) {
        assumeTrue(!scope || SYSTEMD, "no systemd user session on this machine");
        // Unseeded on purpose: a fixed seed gives every test the same systemd unit name, and systemd refuses
        // duplicates.
        return new LinuxProcessRuntime(scope, Clock.systemUTC(), new Random());
    }

    private RunningProcess start(LinuxProcessRuntime runtime, String command) throws Exception {
        var spec = new ProcessLaunchSpec("test", command, Path.of("/tmp"), System.getenv());
        return runtime.start(spec, lines::add);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void capturesStdoutAndStderrSeparately(boolean scope) throws Exception {
        var runtime = runtime(scope);
        var p = start(runtime, "echo hello; echo oops >&2");

        assertThat(runtime.onExit(p).get(5, TimeUnit.SECONDS)).isEqualTo(ExitStatus.of(0));
        awaitTrue(() -> lines.size() == 2);
        assertThat(lines)
                .contains(new OutputLine(StreamKind.STDOUT, "hello"), new OutputLine(StreamKind.STDERR, "oops"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void missingCommandIsExit127(boolean scope) throws Exception {
        var runtime = runtime(scope);
        var p = start(runtime, "no-such-command-xyz");

        ExitStatus exit = runtime.onExit(p).get(5, TimeUnit.SECONDS);
        assertThat(exit.code()).isEqualTo(127);
        assertThat(exit.explanation()).contains("command not found");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void stopLeavesNoChildOrGrandchildBehind(boolean scope) throws Exception {
        var runtime = runtime(scope);
        var p = start(runtime, "sleep 120 & (sleep 120 & wait) & wait");
        // sh, its sleep, the ( ... ) subshell, and the subshell's sleep: a grandchild
        awaitTrue(() -> runtime.members(p).size() == 4);
        Set<Long> family = runtime.members(p);

        var result = new GracefulStop(runtime).stop(p, GRACE);

        assertThat(result).isEqualTo(new StopResult.Stopped(ExitStatus.of(143), false));
        assertThat(runtime.members(p)).isEmpty();
        family.forEach(
                pid -> assertThat(running(pid)).as("PID %d still running", pid).isFalse());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void childIgnoringSigtermIsForceKilledAfterGracePeriod(boolean scope) throws Exception {
        var runtime = runtime(scope);
        // trap '' TERM = ignore SIGTERM; ignored signals stay ignored in children too (kept across exec).
        var p = start(runtime, "trap '' TERM; sleep 120 & sleep 120 & wait");
        awaitTrue(() -> runtime.members(p).size() == 3);

        var result = new GracefulStop(runtime).stop(p, Duration.ofMillis(300));

        assertThat(result).isEqualTo(new StopResult.Stopped(ExitStatus.of(137), true));
        assertThat(runtime.members(p)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void tenMegabytesOfOutputNeverStall(boolean scope) throws Exception {
        var runtime = runtime(scope);
        AtomicLong chars = new AtomicLong();
        var spec = new ProcessLaunchSpec(
                "big", "head -c 10000000 /dev/zero | tr '\\0' x | fold -w 99", Path.of("/tmp"), System.getenv());
        var p = runtime.start(spec, line -> chars.addAndGet(line.text().length()));

        assertThat(runtime.onExit(p).get(30, TimeUnit.SECONDS)).isEqualTo(ExitStatus.of(0));
        awaitTrue(() -> chars.get() == 10_000_000);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void commandThatExitsAtOnceButLeavesAChildIsStillOwned(boolean scope) throws Exception {
        var runtime = runtime(scope);
        var p = start(runtime, "sleep 120 & exit 0");
        assertThat(runtime.onExit(p).get(5, TimeUnit.SECONDS)).isEqualTo(ExitStatus.of(0));
        awaitTrue(() -> runtime.members(p).size() == 1); // the top shell is gone, its sleep is not
        Set<Long> leftover = runtime.members(p);

        var result = new GracefulStop(runtime).stop(p, GRACE);

        assertThat(result).isInstanceOf(StopResult.Stopped.class);
        leftover.forEach(
                pid -> assertThat(running(pid)).as("PID %d still running", pid).isFalse());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true})
    void scopeCatchesAProcessThatEscapesItsGroup(boolean scope) throws Exception {
        var runtime = runtime(scope);
        var p = start(runtime, "setsid sleep 120 & wait");
        awaitTrue(() -> runtime.members(p).size() == 2);
        Set<Long> family = runtime.members(p);

        new GracefulStop(runtime).stop(p, GRACE);

        family.forEach(
                pid -> assertThat(running(pid)).as("PID %d still running", pid).isFalse());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false})
    void withoutSystemdAnEscapedProcessSurvives_knownWeakness(boolean scope) throws Exception {
        var runtime = runtime(scope);
        var p = start(runtime, "setsid sleep 120 & wait");
        awaitTrue(() -> ProcessHandle.of(p.pid()).orElseThrow().children().count() == 1);
        ProcessHandle escaper =
                ProcessHandle.of(p.pid()).orElseThrow().children().findFirst().orElseThrow();
        awaitTrue(() -> ProcStat.read(escaper.pid()).orElseThrow().processGroup() == escaper.pid());

        new GracefulStop(runtime).stop(p, GRACE);

        // Documented in ADR 0002: process groups can't hold a process that calls setsid itself.
        assertThat(escaper.isAlive()).isTrue();
        escaper.destroyForcibly(); // clean up: we started it, so we may kill it by its exact PID
    }

    /**
     * Not {@code ProcessHandle.isAlive()}: Java calls a zombie (exited, exit code not yet collected) "alive". A zombie
     * is dead and harmless, and its parent collects it a moment later.
     */
    private static boolean running(long pid) {
        return ProcStat.read(pid).filter(s -> !s.isZombie()).isPresent();
    }

    private static void awaitTrue(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            assertThat(System.nanoTime()).as("condition not met within 10 s").isLessThan(deadline);
            Thread.sleep(10);
        }
    }
}
