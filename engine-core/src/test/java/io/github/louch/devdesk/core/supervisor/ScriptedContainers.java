package io.github.louch.devdesk.core.supervisor;

import io.github.louch.devdesk.core.container.ContainerLaunchSpec;
import io.github.louch.devdesk.core.container.ContainerRuntime;
import io.github.louch.devdesk.core.container.RunningContainer;
import io.github.louch.devdesk.core.health.ProbeResult;
import io.github.louch.devdesk.core.process.ExitStatus;
import io.github.louch.devdesk.core.process.LaunchException;
import io.github.louch.devdesk.core.process.OutputLine;
import io.github.louch.devdesk.core.process.StopResult;
import io.github.louch.devdesk.core.process.StreamKind;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * A fake ContainerRuntime: image "missing/image" can't be pulled, "crashing/image" exits with 1 right away; every other
 * container runs until stopped.
 */
final class ScriptedContainers implements ContainerRuntime {

    final List<String> started = new CopyOnWriteArrayList<>();
    final List<String> stopped = new CopyOnWriteArrayList<>();
    final Map<String, Map<String, String>> environments = new ConcurrentHashMap<>();
    private final Map<RunningContainer, CompletableFuture<ExitStatus>> exits = new ConcurrentHashMap<>();

    @Override
    public RunningContainer start(ContainerLaunchSpec spec, Consumer<OutputLine> output) throws LaunchException {
        if (spec.container().image().equals("buggy/adapter")) {
            throw new NullPointerException("cmd was not specified"); // like the real docker-java bug we hit
        }
        if (spec.container().image().equals("missing/image")) {
            throw new LaunchException("image missing/image not found: check the name and tag", null);
        }
        output.accept(new OutputLine(StreamKind.STDOUT, "database system is ready"));
        started.add(spec.service());
        environments.put(spec.service(), spec.environment());
        var c = new RunningContainer(
                spec.service(),
                "c0ffee" + started.size(),
                "devdesk-" + spec.project() + "-" + spec.service(),
                5000 + started.size());
        exits.put(c, new CompletableFuture<>());
        if (spec.container().image().equals("crashing/image")) {
            exits.get(c).complete(ExitStatus.of(1));
        }
        return c;
    }

    @Override
    public CompletableFuture<ExitStatus> onExit(RunningContainer c) {
        return exits.get(c);
    }

    @Override
    public StopResult stop(RunningContainer c, Duration grace) {
        stopped.add(c.service());
        exits.get(c).complete(ExitStatus.of(143));
        return new StopResult.Stopped(ExitStatus.of(143), false);
    }

    @Override
    public ProbeResult health(RunningContainer c) {
        return new ProbeResult.Passed("Docker reports " + c.name() + " healthy");
    }
}
