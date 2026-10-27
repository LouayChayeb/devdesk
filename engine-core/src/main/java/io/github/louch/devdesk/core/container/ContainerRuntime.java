package io.github.louch.devdesk.core.container;

import io.github.louch.devdesk.core.health.ProbeResult;
import io.github.louch.devdesk.core.process.ExitStatus;
import io.github.louch.devdesk.core.process.LaunchException;
import io.github.louch.devdesk.core.process.OutputLine;
import io.github.louch.devdesk.core.process.StopResult;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** The port core uses to run containers. Docker implements it in engine-docker; tests use a fake. */
public interface ContainerRuntime {

    /**
     * {@code devdesk-<project>-<service>}, with only the characters Docker allows in names. Health checks written by
     * the setup wizard ({@code docker exec <name> ...}) rely on it, so every runtime must name containers this way.
     */
    static String containerName(String project, String service) {
        return ("devdesk-" + project + "-" + service).replaceAll("[^a-zA-Z0-9_.-]", "_");
    }

    /**
     * Pulls the image if needed (progress goes to {@code output}), creates and starts the container, and streams its
     * logs to {@code output}. Fails with a plain-words reason (image not found, port taken, Docker not running...).
     */
    RunningContainer start(ContainerLaunchSpec spec, Consumer<OutputLine> output) throws LaunchException;

    /** Completes when the container's main process has exited. */
    CompletableFuture<ExitStatus> onExit(RunningContainer container);

    /** Stops it (SIGTERM, then SIGKILL after {@code grace}), removes it (named volumes are kept), and checks it's gone. */
    StopResult stop(RunningContainer container, Duration grace) throws InterruptedException;

    /** The image's own HEALTHCHECK verdict, for {@code health: { type: docker }}. */
    ProbeResult health(RunningContainer container);

    /**
     * Where the container itself listens for {@code hostPort}, one of its published ports: its own address and the
     * port inside. A check must connect there, not to the published port: Docker's port forwarder accepts a
     * connection on the host even when nothing listens inside the container. Empty when the port isn't published by
     * this container or its address can't be reached from here.
     */
    default Optional<InetSocketAddress> inside(RunningContainer container, int hostPort) {
        return Optional.empty();
    }
}
