package io.github.louch.devdesk.core.supervisor;

import io.github.louch.devdesk.core.process.ExitStatus;
import io.github.louch.devdesk.core.process.LaunchException;
import io.github.louch.devdesk.core.process.OutputLine;
import io.github.louch.devdesk.core.process.ProcessLaunchSpec;
import io.github.louch.devdesk.core.process.ProcessRuntime;
import io.github.louch.devdesk.core.process.RunningProcess;
import io.github.louch.devdesk.core.process.Signal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * A fake ProcessRuntime whose processes do what their command says: "serve" runs until signalled, "exit N" ends at
 * once with code N, "no-launch" can't be started. Records the order of starts and stops.
 */
final class ScriptedRuntime implements ProcessRuntime {

    final List<String> started = new CopyOnWriteArrayList<>();
    final List<String> stopped = new CopyOnWriteArrayList<>();
    private final AtomicLong nextPid = new AtomicLong(1000);
    private final Map<RunningProcess, CompletableFuture<ExitStatus>> exits = new ConcurrentHashMap<>();

    @Override
    public RunningProcess start(ProcessLaunchSpec spec, Consumer<OutputLine> output) throws LaunchException {
        if (spec.command().equals("no-launch")) {
            throw new LaunchException("working directory /nope does not exist", null);
        }
        started.add(spec.name());
        var p = new RunningProcess(spec.name(), nextPid.incrementAndGet(), Instant.EPOCH);
        var exit = new CompletableFuture<ExitStatus>();
        exits.put(p, exit);
        if (spec.command().startsWith("exit ")) {
            exit.complete(ExitStatus.of(Integer.parseInt(spec.command().substring(5))));
        }
        return p;
    }

    @Override
    public void signal(RunningProcess p, Signal signal) {
        if (signal == Signal.TERM) {
            stopped.add(p.name());
        }
        exits.get(p).complete(ExitStatus.of(signal == Signal.KILL ? 137 : 143));
    }

    @Override
    public CompletableFuture<ExitStatus> onExit(RunningProcess p) {
        return exits.get(p);
    }

    @Override
    public Set<Long> members(RunningProcess p) {
        return exits.get(p).isDone() ? Set.of() : Set.of(p.pid());
    }
}
