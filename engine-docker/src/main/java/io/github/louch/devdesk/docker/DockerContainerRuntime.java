package io.github.louch.devdesk.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.HealthState;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.command.PullImageResultCallback;
import com.github.dockerjava.api.exception.DockerException;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.exception.NotModifiedException;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Ports;
import com.github.dockerjava.api.model.PullResponseItem;
import com.github.dockerjava.api.model.StreamType;
import io.github.louch.devdesk.core.container.ContainerLaunchSpec;
import io.github.louch.devdesk.core.container.ContainerRuntime;
import io.github.louch.devdesk.core.container.RunningContainer;
import io.github.louch.devdesk.core.health.ProbeResult;
import io.github.louch.devdesk.core.leftovers.Leftover;
import io.github.louch.devdesk.core.leftovers.LeftoverFinder;
import io.github.louch.devdesk.core.process.ExitStatus;
import io.github.louch.devdesk.core.process.LaunchException;
import io.github.louch.devdesk.core.process.OutputLine;
import io.github.louch.devdesk.core.process.StopResult;
import io.github.louch.devdesk.core.process.StreamKind;
import io.github.louch.devdesk.core.project.ProjectSpec;
import io.github.louch.devdesk.core.project.Workload;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Runs containers through the Docker Engine API (Podman exposes the same API).
 *
 * <p>Ownership: every container DevDesk creates carries the labels {@code devdesk.project} and {@code devdesk.service}.
 * DevDesk only ever stops or removes containers with its own labels, never anything else (CLAUDE.md principle 2).
 */
public final class DockerContainerRuntime implements ContainerRuntime {

    static final String PROJECT_LABEL = "devdesk.project";
    static final String SERVICE_LABEL = "devdesk.service";
    /** Name + folder hash: two projects with the same name never claim each other's containers. */
    static final String PROJECT_KEY_LABEL = "devdesk.project-key";

    private final DockerClient docker;
    private final Map<String, CompletableFuture<ExitStatus>> exits = new ConcurrentHashMap<>();

    public DockerContainerRuntime(DockerClient docker) {
        this.docker = docker;
    }

    /** The command override split like a shell would; a missing closing quote is a launch error, not a guess. */
    private static Optional<List<String>> arguments(Workload.Container c) throws LaunchException {
        if (c.command().isEmpty()) {
            return Optional.empty(); // the image's own default command
        }
        return Optional.of(ShellWords.split(c.command().get())
                .orElseThrow(() -> new LaunchException(
                        "the container command has a quote that is never closed: "
                                + c.command().get(),
                        null)));
    }

    /** {@code devdesk-<project>-<service>}, with only the characters Docker allows in names. */
    static String containerName(String project, String service) {
        return ("devdesk-" + project + "-" + service).replaceAll("[^a-zA-Z0-9_.-]", "_");
    }

    @Override
    public RunningContainer start(ContainerLaunchSpec spec, Consumer<OutputLine> output) throws LaunchException {
        Workload.Container c = spec.container();
        String name = containerName(spec.project(), spec.service());
        ensureImage(c.image(), output);
        removeOurLeftover(name, spec);

        String id;
        try {
            var create = docker.createContainerCmd(c.image())
                    .withName(name)
                    .withLabels(Map.of(
                            PROJECT_LABEL,
                            spec.project(),
                            PROJECT_KEY_LABEL,
                            spec.projectKey(),
                            SERVICE_LABEL,
                            spec.service()))
                    .withEnv(spec.environment().entrySet().stream()
                            .map(e -> e.getKey() + "=" + e.getValue())
                            .toList())
                    .withExposedPorts(c.ports().stream()
                            .map(p -> ExposedPort.tcp(p.containerPort()))
                            .toList())
                    .withHostConfig(HostConfig.newHostConfig()
                            .withPortBindings(portBindings(c))
                            .withBinds(c.volumes().stream().map(Bind::parse).toList()));
            // Only override the command when the user gave one; otherwise the image's own default runs.
            arguments(c).ifPresent(create::withCmd);
            id = create.exec().getId();
        } catch (DockerException e) {
            throw new LaunchException("Docker could not create " + name + ": " + clean(e), e);
        }
        try {
            docker.startContainerCmd(id).exec();
        } catch (DockerException e) {
            removeQuietly(id); // don't leave a created-but-never-started container behind
            throw new LaunchException("Docker could not start " + name + ": " + clean(e) + hint(clean(e)), e);
        }

        followLogs(id, output);
        CompletableFuture<ExitStatus> exit = new CompletableFuture<>();
        exits.put(id, exit);
        Thread.ofVirtual().name("wait-" + name).start(() -> waitForExit(id, exit));
        Long pid = docker.inspectContainerCmd(id).exec().getState().getPidLong();
        return new RunningContainer(spec.service(), id, name, pid == null ? 0 : pid);
    }

    @Override
    public CompletableFuture<ExitStatus> onExit(RunningContainer container) {
        return exits.get(container.id());
    }

    @Override
    public StopResult stop(RunningContainer container, Duration grace) throws InterruptedException {
        try {
            // Docker sends the image's stop signal (SIGTERM by default), waits, then SIGKILLs: the same sequence as
            // ours.
            docker.stopContainerCmd(container.id())
                    .withTimeout(Math.toIntExact(Math.max(0, grace.toSeconds())))
                    .exec();
        } catch (NotModifiedException e) {
            // Already stopped.
        } catch (NotFoundException e) {
            return new StopResult.Stopped(onExit(container).getNow(ExitStatus.of(0)), false);
        }
        ExitStatus exit = onExit(container).join();
        removeQuietly(container.id());
        if (exists(container.id())) {
            return new StopResult.LeftBehind(Optional.of(exit), Set.of(container.pid()));
        }
        return new StopResult.Stopped(exit, exit.code() == 137);
    }

    @Override
    public ProbeResult health(RunningContainer container) {
        HealthState health;
        try {
            health =
                    docker.inspectContainerCmd(container.id()).exec().getState().getHealth();
        } catch (DockerException e) {
            return new ProbeResult.Failed("could not ask Docker about " + container.name() + ": " + clean(e));
        }
        if (health == null) {
            return new ProbeResult.Failed("the image of " + container.name() + " has no HEALTHCHECK. "
                    + "Use another check, e.g. health: { type: tcp, port: 5432 }");
        }
        String last = health.getLog() == null || health.getLog().isEmpty()
                ? ""
                : ": " + health.getLog().getLast().getOutput().strip();
        String detail = "Docker reports " + container.name() + " " + health.getStatus() + last;
        return "healthy".equals(health.getStatus()) ? new ProbeResult.Passed(detail) : new ProbeResult.Failed(detail);
    }

    // ---- helpers ----

    private void ensureImage(String image, Consumer<OutputLine> output) throws LaunchException {
        try {
            docker.inspectImageCmd(image).exec();
            return; // already here: no download
        } catch (NotFoundException e) {
            output.accept(new OutputLine(StreamKind.STDERR, "[devdesk] pulling image " + image + " ..."));
        }
        try {
            docker.pullImageCmd(image)
                    .exec(new PullImageResultCallback() {
                        @Override
                        public void onNext(PullResponseItem item) {
                            super.onNext(item);
                            // One line per finished layer, not per progress tick: enough to see it's moving.
                            if ("Pull complete".equals(item.getStatus())) {
                                output.accept(
                                        new OutputLine(StreamKind.STDERR, "[devdesk] layer " + item.getId() + " done"));
                            }
                        }
                    })
                    .awaitCompletion();
        } catch (DockerException e) {
            throw new LaunchException(
                    "could not pull image " + image + ": " + clean(e)
                            + ". Check the name and tag, and that you're online (try: docker pull " + image + ")",
                    e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LaunchException("interrupted while pulling " + image, e);
        }
    }

    /**
     * A container with our name may exist after a crash. If it carries our labels it's ours, and it's removed. If
     * not, it belongs to someone else, so DevDesk refuses to touch it.
     */
    private void removeOurLeftover(String name, ContainerLaunchSpec spec) throws LaunchException {
        InspectContainerResponse existing;
        try {
            existing = docker.inspectContainerCmd(name).exec();
        } catch (NotFoundException e) {
            return;
        }
        Map<String, String> labels = existing.getConfig().getLabels();
        boolean ours = labels != null
                && spec.project().equals(labels.get(PROJECT_LABEL))
                && spec.service().equals(labels.get(SERVICE_LABEL));
        if (!ours) {
            throw new LaunchException(
                    "a container named " + name + " already exists and wasn't created by DevDesk, "
                            + "so DevDesk won't touch it. Rename or remove it yourself: docker rm -f " + name,
                    null);
        }
        docker.removeContainerCmd(existing.getId()).withForce(true).exec();
    }

    private static Ports portBindings(Workload.Container c) {
        Ports ports = new Ports();
        for (Workload.PortMapping p : c.ports()) {
            ports.bind(ExposedPort.tcp(p.containerPort()), Ports.Binding.bindIpAndPort(p.hostAddress(), p.hostPort()));
        }
        return ports;
    }

    private void followLogs(String id, Consumer<OutputLine> output) {
        FrameLines lines = new FrameLines(output);
        docker.logContainerCmd(id)
                .withStdOut(true)
                .withStdErr(true)
                .withFollowStream(true)
                .exec(new ResultCallback.Adapter<Frame>() {
                    @Override
                    public void onNext(Frame frame) {
                        lines.accept(
                                frame.getStreamType() == StreamType.STDERR ? StreamKind.STDERR : StreamKind.STDOUT,
                                frame.getPayload());
                    }

                    @Override
                    public void onComplete() {
                        lines.flush();
                        super.onComplete();
                    }
                });
    }

    private void waitForExit(String id, CompletableFuture<ExitStatus> exit) {
        try {
            int code = docker.waitContainerCmd(id)
                    .exec(new com.github.dockerjava.api.command.WaitContainerResultCallback())
                    .awaitStatusCode();
            exit.complete(ExitStatus.of(code));
        } catch (DockerException e) {
            exit.complete(ExitStatus.of(137)); // container vanished (removed or Docker restarted)
        }
    }

    private boolean exists(String id) {
        try {
            docker.inspectContainerCmd(id).exec();
            return true;
        } catch (NotFoundException e) {
            return false;
        }
    }

    private void removeQuietly(String id) {
        try {
            // Named volumes stay (they hold the database's data); only the container itself goes.
            docker.removeContainerCmd(id)
                    .withForce(true)
                    .withRemoveVolumes(false)
                    .exec();
        } catch (NotFoundException e) {
            // already gone
        }
    }

    /** Docker error bodies look like {"message":"..."}: keep only the message. */
    private static String clean(DockerException e) {
        String m = e.getMessage() == null ? "" : e.getMessage();
        int start = m.indexOf("\"message\":\"");
        if (start >= 0) {
            int end = m.indexOf('"', start + 11);
            return m.substring(start + 11, end > 0 ? end : m.length());
        }
        return m.strip();
    }

    private static String hint(String dockerMessage) {
        return dockerMessage.contains("port is already allocated") || dockerMessage.contains("address already in use")
                ? ". Another container or program uses that port (check: docker ps)"
                : "";
    }

    /** Finds containers a previous DevDesk left running for a project (by the project-key label only). */
    public LeftoverFinder leftovers() {
        return new LeftoverFinder() {
            @Override
            public List<Leftover> find(ProjectSpec project) {
                List<Leftover> found = new ArrayList<>();
                docker.listContainersCmd()
                        .withShowAll(true)
                        .withLabelFilter(Map.of(PROJECT_KEY_LABEL, project.key()))
                        .exec()
                        .forEach(c -> {
                            Long pid = docker.inspectContainerCmd(c.getId())
                                    .exec()
                                    .getState()
                                    .getPidLong();
                            found.add(new Leftover(
                                    c.getLabels().getOrDefault(SERVICE_LABEL, "?"),
                                    Leftover.Kind.CONTAINER,
                                    c.getId(),
                                    pid == null || pid == 0 ? Set.of() : Set.of(pid),
                                    "container " + c.getNames()[0].substring(1) + " (" + c.getImage() + ", "
                                            + c.getStatus() + ")",
                                    Optional.of(java.time.Instant.ofEpochSecond(c.getCreated()))));
                        });
                return found;
            }

            @Override
            public String remove(Leftover leftover) {
                try {
                    docker.stopContainerCmd(leftover.id()).withTimeout(10).exec();
                } catch (NotModifiedException | NotFoundException e) {
                    // already stopped or gone
                }
                removeQuietly(leftover.id());
                return exists(leftover.id()) ? "could not remove container " + leftover.id() : "stopped and removed";
            }
        };
    }

    /** The underlying client, for this module's tests. */
    DockerClient client() {
        return docker;
    }

    /** Lists DevDesk's own containers, for tests and cleanup. Only containers with our project label. */
    List<String> ownContainerNames(String project) {
        List<String> names = new ArrayList<>();
        docker.listContainersCmd()
                .withShowAll(true)
                .withLabelFilter(Map.of(PROJECT_LABEL, project))
                .exec()
                .forEach(c -> names.add(c.getNames()[0].substring(1)));
        return names;
    }
}
