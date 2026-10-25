package io.github.louch.devdesk.core.process;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * The cases where the top process and its children don't go together: the stop must wait for the whole family, not
 * only the top PID, and must wait the grace period (not less) before SIGKILL.
 */
class GracefulStopFamilyTest {

    private static final ProcessLaunchSpec SPEC = new ProcessLaunchSpec("api", "npm start", Path.of("/tmp"), Map.of());

    /** The top process obeys SIGTERM at once; a child leaves {@code childDelay} later, or never (null). */
    private static final class Family implements ProcessRuntime {
        final List<Signal> received = new ArrayList<>();
        final CompletableFuture<ExitStatus> exit = new CompletableFuture<>();
        final Duration childDelay;
        final boolean topIgnoresEverything;
        volatile Instant childLeavesAt = Instant.MAX;
        volatile boolean childKilled;

        Family(Duration childDelay, boolean topIgnoresEverything) {
            this.childDelay = childDelay;
            this.topIgnoresEverything = topIgnoresEverything;
        }

        @Override
        public RunningProcess start(ProcessLaunchSpec spec, Consumer<OutputLine> output) {
            return new RunningProcess(spec.name(), 100L, Instant.EPOCH);
        }

        @Override
        public void signal(RunningProcess process, Signal signal) {
            received.add(signal);
            if (topIgnoresEverything) {
                return;
            }
            if (signal == Signal.TERM) {
                exit.complete(ExitStatus.of(143));
                if (childDelay != null) {
                    childLeavesAt = Instant.now().plus(childDelay);
                }
            } else if (signal == Signal.KILL) {
                exit.complete(ExitStatus.of(137));
                childKilled = true;
            }
        }

        @Override
        public CompletableFuture<ExitStatus> onExit(RunningProcess process) {
            return exit;
        }

        @Override
        public Set<Long> members(RunningProcess process) {
            if (topIgnoresEverything) {
                return Set.of(100L);
            }
            boolean childAlive = !childKilled && Instant.now().isBefore(childLeavesAt);
            return childAlive ? Set.of(101L) : Set.of();
        }
    }

    @Test
    void aChildThatLeavesWithinTheGracePeriodNeedsNoSigkill() throws Exception {
        var family = new Family(Duration.ofMillis(80), false);

        var result = new GracefulStop(family).stop(family.start(SPEC, l -> {}), Duration.ofSeconds(2));

        assertThat(family.received).containsExactly(Signal.TERM);
        assertThat(result).isEqualTo(new StopResult.Stopped(ExitStatus.of(143), false));
    }

    @Test
    void aChildIgnoringSigtermGetsSigkillOnlyAfterTheWholeGracePeriod() throws Exception {
        var family = new Family(null, false); // the top leaves on SIGTERM, the child never does
        Duration grace = Duration.ofMillis(300);

        long started = System.nanoTime();
        var result = new GracefulStop(family).stop(family.start(SPEC, l -> {}), grace);
        Duration took = Duration.ofNanos(System.nanoTime() - started);

        assertThat(family.received).containsExactly(Signal.TERM, Signal.KILL);
        assertThat(took).isGreaterThanOrEqualTo(grace); // never SIGKILL before the app had its grace period
        assertThat(result).isEqualTo(new StopResult.Stopped(ExitStatus.of(143), true));
    }

    @Test
    void aFamilyThatSurvivesEverythingIsReportedWithItsPidsAndNoExitCode() throws Exception {
        var family = new Family(null, true);

        var result = new GracefulStop(family).stop(family.start(SPEC, l -> {}), Duration.ofMillis(50));

        assertThat(family.received).containsExactly(Signal.TERM, Signal.KILL);
        assertThat(result).isEqualTo(new StopResult.LeftBehind(java.util.Optional.empty(), Set.of(100L)));
    }
}
