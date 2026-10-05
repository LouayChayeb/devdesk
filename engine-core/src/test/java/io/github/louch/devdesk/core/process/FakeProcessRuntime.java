package io.github.louch.devdesk.core.process;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** In-memory ProcessRuntime for tests: a "family" of fake PIDs that reacts to signals the way we configure it. */
final class FakeProcessRuntime implements ProcessRuntime {

    final List<Signal> received = new ArrayList<>();
    private final CompletableFuture<ExitStatus> exit = new CompletableFuture<>();
    private final Set<Long> alive = new HashSet<>(Set.of(100L, 101L));
    private final boolean ignoresTerm;
    private final boolean survivesKill;

    FakeProcessRuntime(boolean ignoresTerm, boolean survivesKill) {
        this.ignoresTerm = ignoresTerm;
        this.survivesKill = survivesKill;
    }

    @Override
    public RunningProcess start(ProcessLaunchSpec spec, Consumer<OutputLine> output) {
        return new RunningProcess(spec.name(), 100L, Instant.EPOCH);
    }

    @Override
    public void signal(RunningProcess process, Signal signal) {
        received.add(signal);
        if (signal == Signal.TERM && !ignoresTerm) {
            die(143);
        } else if (signal == Signal.KILL) {
            die(137);
            if (survivesKill) {
                alive.add(999L); // an "escaped" process the kill couldn't reach
            }
        }
    }

    private void die(int code) {
        alive.clear();
        exit.complete(ExitStatus.of(code));
    }

    @Override
    public CompletableFuture<ExitStatus> onExit(RunningProcess process) {
        return exit;
    }

    @Override
    public Set<Long> members(RunningProcess process) {
        return Set.copyOf(alive);
    }
}
