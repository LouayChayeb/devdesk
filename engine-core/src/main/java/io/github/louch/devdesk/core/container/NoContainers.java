package io.github.louch.devdesk.core.container;

import io.github.louch.devdesk.core.health.ProbeResult;
import io.github.louch.devdesk.core.process.ExitStatus;
import io.github.louch.devdesk.core.process.OutputLine;
import io.github.louch.devdesk.core.process.StopResult;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Stands in for Docker when a project has no containers, so DevDesk never even connects to Docker then. */
public final class NoContainers implements ContainerRuntime {

    @Override
    public RunningContainer start(ContainerLaunchSpec spec, Consumer<OutputLine> output) {
        throw new IllegalStateException("bug: no container services in this project");
    }

    @Override
    public CompletableFuture<ExitStatus> onExit(RunningContainer container) {
        throw new IllegalStateException("bug: no container services in this project");
    }

    @Override
    public StopResult stop(RunningContainer container, Duration grace) {
        throw new IllegalStateException("bug: no container services in this project");
    }

    @Override
    public ProbeResult health(RunningContainer container) {
        throw new IllegalStateException("bug: no container services in this project");
    }
}
