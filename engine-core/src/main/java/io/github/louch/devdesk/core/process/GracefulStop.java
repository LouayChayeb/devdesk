package io.github.louch.devdesk.core.process;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Stops a process family politely first: SIGTERM, wait the grace period, SIGKILL what's left, then verify. */
public final class GracefulStop {

    // After SIGKILL the kernel ends processes almost instantly; 5 s only matters if something is badly wrong.
    private static final Duration KILL_TIMEOUT = Duration.ofSeconds(5);
    private static final long POLL_MILLIS = 20;

    private final ProcessRuntime runtime;

    public GracefulStop(ProcessRuntime runtime) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    /** Runs the full stop sequence and reports exactly what happened. Blocks until done. */
    public StopResult stop(RunningProcess process, Duration gracePeriod) throws InterruptedException {
        // SIGTERM first so apps can close DB connections and flush files; SIGKILL only after the grace period.
        runtime.signal(process, Signal.TERM);
        if (awaitAllGone(process, gracePeriod)) {
            return new StopResult.Stopped(exitStatus(process).orElseThrow(), false);
        }
        // The top process may have exited while a child ignored SIGTERM, so check members, not only the top PID.
        runtime.signal(process, Signal.KILL);
        if (awaitAllGone(process, KILL_TIMEOUT)) {
            return new StopResult.Stopped(exitStatus(process).orElseThrow(), true);
        }
        return new StopResult.LeftBehind(exitStatus(process), runtime.members(process));
    }

    private boolean awaitAllGone(RunningProcess process, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        if (awaitExit(process, timeout).isEmpty()) {
            return false;
        }
        while (!runtime.members(process).isEmpty()) {
            if (System.nanoTime() >= deadline) {
                return false;
            }
            Thread.sleep(POLL_MILLIS);
        }
        return true;
    }

    private Optional<ExitStatus> awaitExit(RunningProcess process, Duration timeout) throws InterruptedException {
        try {
            return Optional.of(runtime.onExit(process).get(timeout.toNanos(), TimeUnit.NANOSECONDS));
        } catch (TimeoutException e) {
            return Optional.empty();
        } catch (ExecutionException e) {
            throw new IllegalStateException("Waiting for '" + process.name() + "' to exit failed", e.getCause());
        }
    }

    private Optional<ExitStatus> exitStatus(RunningProcess process) {
        var future = runtime.onExit(process);
        return future.isDone() && !future.isCompletedExceptionally() ? Optional.of(future.join()) : Optional.empty();
    }
}
