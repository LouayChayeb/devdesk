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
import com.github.dockerjava.api.model.ContainerPort;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Ports;
import com.github.dockerjava.api.model.PullResponseItem;
import com.github.dockerjava.api.model.StreamType;
import io.github.louch.devdesk.core.container.ContainerLaunchSpec;
import io.github.louch.devdesk.core.container.ContainerRuntime;
import io.github.louch.devdesk.core.container.RunningContainer;
import io.github.louch.devdesk.core.crashpoint.CrashPoint;
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
import io.github.louch.devdesk.core.setup.FolderScan;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
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

    /** Container ID → its project key, so removing the last container can remove the project's network too. */
    private final Map<String, String> keys = new ConcurrentHashMap<>();

    private final ProjectNetwork network;

    /** Container ID → published host port → where the container itself listens. */
    private final Map<String, Map<Integer, InetSocketAddress>> addresses = new ConcurrentHashMap<>();

    /** Whether this machine reaches container addresses directly; null until Docker was asked. */
    private volatile Boolean reachable;

    private final ImageBuilder builder;

    public DockerContainerRuntime(DockerClient docker) {
        this(docker, System.getenv());
    }

    private final PullLog pulls;

    /** @param environment where the docker command is found (PATH) when an image must be built */
    public DockerContainerRuntime(DockerClient docker, Map<String, String> environment) {
        this(docker, environment, Optional.empty());
    }

    /** @param pullLog where downloaded images are recorded per project, so Remove can tell them from the user's */
    public DockerContainerRuntime(DockerClient docker, Map<String, String> environment, Optional<Path> pullLog) {
        this.docker = docker;
        this.network = new ProjectNetwork(docker);
        this.builder = new ImageBuilder(environment);
        this.pulls = new PullLog(pullLog);
    }

    /**
     * Deletes everything inside {@code folder} as root, from a short-lived container that sees only that folder:
     * containers write their data (a database's files) as their own users, which DevDesk's user can't delete. Used
     * only for a clone DevDesk made. Empty when done, otherwise why not.
     */
    public Optional<String> deleteInside(java.nio.file.Path folder) {
        String helper = "alpine:3";
        try {
            ensureImage(helper, line -> {});
            var create = docker.createContainerCmd(helper)
                    .withLabels(Map.of(PROJECT_LABEL, "devdesk-cleanup", SERVICE_LABEL, "cleanup"))
                    // -mindepth 1: the folder itself stays (it's the mount point); -delete never follows links.
                    .withCmd("find", "/clone", "-mindepth", "1", "-delete")
                    .withHostConfig(HostConfig.newHostConfig()
                            .withBinds(Bind.parse(folder.toAbsolutePath().normalize() + ":/clone")));
            String id = create.exec().getId();
            try {
                docker.startContainerCmd(id).exec();
                Integer code =
                        docker.waitContainerCmd(id).start().awaitStatusCode(120, java.util.concurrent.TimeUnit.SECONDS);
                return code != null && code == 0
                        ? Optional.empty()
                        : Optional.of("the cleanup container ended with code " + code);
            } finally {
                removeContainer(id);
            }
        } catch (LaunchException | DockerException e) {
            return Optional.of("Docker couldn't delete them: " + e.getMessage());
        } catch (RuntimeException e) {
            return Optional.of("Docker couldn't delete them: " + e);
        }
    }

    /** "Remove" for this Docker: what DevDesk made for a project, found by its labels and records. */
    public DockerRemoval removal() {
        return new DockerRemoval(docker, pulls);
    }

    /** Database backups through the same Docker connection (dump tools run inside the database's container). */
    public DockerDatabaseBackups backups() {
        return new DockerDatabaseBackups(docker);
    }

    /** The command override split like a shell would; a missing closing quote is a launch error, not a guess. */
    static Optional<List<String>> arguments(Workload.Container c) throws LaunchException {
        if (c.command().isEmpty()) {
            return Optional.empty(); // the image's own default command
        }
        return Optional.of(ShellWords.split(c.command().get())
                .orElseThrow(() -> new LaunchException(
                        "the container command has a quote that is never closed: "
                                + c.command().get(),
                        null)));
    }

    static String containerName(String project, String service) {
        return ContainerRuntime.containerName(project, service);
    }

    @Override
    public RunningContainer start(ContainerLaunchSpec spec, Consumer<OutputLine> output) throws LaunchException {
        Workload.Container c = spec.container();
        String name = containerName(spec.project(), spec.service());
        if (c.build().isPresent()) {
            ensureBuilt(c.image(), c.build().get(), spec, output);
        } else if (ensureImage(c.image(), output)) {
            pulls.record(c.image(), spec.projectKey());
        }
        ensureVolumes(c, spec);
        removeOurLeftover(name, spec);
        if (Thread.currentThread().isInterrupted()) {
            throw new LaunchException("stopped before it was started", null); // a stop arrived while pulling
        }

        String net = network.ensure(spec.project(), spec.projectKey());
        String id;
        try {
            var create = docker.createContainerCmd(c.image())
                    .withName(name)
                    // Reachable from the project's other containers by its service name, as in compose.
                    .withAliases(spec.service())
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
                            .map(p -> p.udp() ? ExposedPort.udp(p.containerPort()) : ExposedPort.tcp(p.containerPort()))
                            .toList())
                    .withHostConfig(HostConfig.newHostConfig()
                            .withNetworkMode(net)
                            .withPortBindings(portBindings(c))
                            .withBinds(c.volumes().stream().map(Bind::parse).toList()));
            // Only override the command when the user gave one; otherwise the image's own default runs.
            arguments(c).ifPresent(create::withCmd);
            if (c.entrypoint().isPresent()) {
                create.withEntrypoint(ShellWords.split(c.entrypoint().get())
                        .orElseThrow(() -> new LaunchException(
                                "the container entrypoint has a quote that is never closed: "
                                        + c.entrypoint().get(),
                                null)));
            }
            id = create.exec().getId();
            keys.put(id, spec.projectKey());
        } catch (DockerException e) {
            throw new LaunchException("Docker could not create " + name + ": " + clean(e), e);
        }
        CrashPoint.reach("container.created");
        try {
            docker.startContainerCmd(id).exec();
        } catch (DockerException e) {
            removeAfterFailedStart(id); // don't leave a created-but-never-started container behind
            throw new LaunchException("Docker could not start " + name + ": " + clean(e) + hint(clean(e)), e);
        }
        CrashPoint.reach("container.started");
        try {
            followLogs(id, output);
            CompletableFuture<ExitStatus> exit = new CompletableFuture<>();
            exits.put(id, exit);
            Thread.ofVirtual().name("wait-" + name).start(() -> waitForExit(id, exit));
            Long pid = docker.inspectContainerCmd(id).exec().getState().getPidLong();
            return new RunningContainer(spec.service(), id, name, pid == null ? 0 : pid);
        } catch (RuntimeException e) {
            // It runs, but nobody would own it: remove it rather than leave it behind (e.g. a stop interrupted us).
            removeAfterFailedStart(id);
            throw new LaunchException(
                    "Docker started " + name + " but DevDesk lost track of it, so it was removed: " + e.getMessage(),
                    e);
        }
    }

    /** Removes a container whose start failed. A pending interrupt (a stop) must not make this cleanup fail too. */
    private void removeAfterFailedStart(String id) {
        boolean interrupted = Thread.interrupted();
        try {
            removeQuietly(id);
        } catch (RuntimeException e) {
            // found by the leftover check next time (it carries our labels)
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
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
        CrashPoint.reach("container.stop-sent");
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

    @Override
    public Optional<InetSocketAddress> inside(RunningContainer container, int hostPort) {
        if (!containersReachable()) {
            return Optional.empty();
        }
        // A container keeps its address and ports while it lives: ask Docker once, not on every probe.
        Map<Integer, InetSocketAddress> known =
                addresses.computeIfAbsent(container.id(), id -> new ConcurrentHashMap<>());
        InetSocketAddress address = known.get(hostPort);
        if (address == null) {
            address = lookUpInside(container.id(), hostPort).orElse(null);
            if (address != null) {
                known.put(hostPort, address);
            }
        }
        return Optional.ofNullable(address);
    }

    private Optional<InetSocketAddress> lookUpInside(String id, int hostPort) {
        try {
            var settings = docker.inspectContainerCmd(id).exec().getNetworkSettings();
            Optional<String> ip = settings.getNetworks().values().stream()
                    .map(n -> n.getIpAddress())
                    .filter(a -> a != null && !a.isEmpty())
                    .findFirst();
            Optional<Integer> port = settings.getPorts().getBindings().entrySet().stream()
                    .filter(e -> e.getValue() != null)
                    .filter(e -> Arrays.stream(e.getValue())
                            .anyMatch(b -> String.valueOf(hostPort).equals(b.getHostPortSpec())))
                    .map(e -> e.getKey().getPort())
                    .findFirst();
            return ip.flatMap(a -> port.map(p -> new InetSocketAddress(a, p)));
        } catch (DockerException e) {
            return Optional.empty(); // gone or Docker busy: the probe uses the published port this time
        }
    }

    /**
     * Container addresses are reachable from the host with a rootful Docker on Linux. Docker Desktop runs containers
     * in a VM and rootless Docker in its own network namespace: there, only published ports reach them.
     */
    private boolean containersReachable() {
        Boolean known = reachable;
        if (known == null) {
            try {
                var info = docker.infoCmd().exec();
                String os = Optional.ofNullable(info.getOperatingSystem()).orElse("");
                List<String> security =
                        Optional.ofNullable(info.getSecurityOptions()).orElse(List.of());
                known = !os.contains("Docker Desktop") && security.stream().noneMatch(o -> o.contains("rootless"));
            } catch (DockerException e) {
                return false; // ask again next time
            }
            reachable = known;
        }
        return known;
    }

    // ---- helpers ----

    /** Builds the image from the project's Dockerfile the first time; later starts reuse it, like compose up. */
    private void ensureBuilt(String image, Workload.Build build, ContainerLaunchSpec spec, Consumer<OutputLine> output)
            throws LaunchException {
        try {
            docker.inspectImageCmd(image).exec();
            return;
        } catch (NotFoundException e) {
            // not built yet
        }
        builder.build(
                image,
                build,
                Map.of(
                        PROJECT_LABEL,
                        spec.project(),
                        PROJECT_KEY_LABEL,
                        spec.projectKey(),
                        SERVICE_LABEL,
                        spec.service()),
                output);
    }

    /**
     * Named volumes the project uses that don't exist yet are created with its labels: Remove then deletes exactly
     * those. One that exists already (the user's, or compose's) is used as it is and never labelled or removed.
     */
    private void ensureVolumes(Workload.Container c, ContainerLaunchSpec spec) throws LaunchException {
        for (String v : c.volumes()) {
            String source = v.substring(0, Math.max(0, v.indexOf(':')));
            if (source.isEmpty() || source.startsWith("/")) {
                continue; // a folder of this machine, not a volume
            }
            try {
                docker.inspectVolumeCmd(source).exec();
            } catch (NotFoundException e) {
                try {
                    docker.createVolumeCmd()
                            .withName(source)
                            .withLabels(Map.of(PROJECT_LABEL, spec.project(), PROJECT_KEY_LABEL, spec.projectKey()))
                            .exec();
                } catch (DockerException creating) {
                    throw new LaunchException(
                            "Docker could not create the volume " + source + ": " + clean(creating), creating);
                }
            }
        }
    }

    /** True when it was downloaded now, false when it was already here. */
    private boolean ensureImage(String image, Consumer<OutputLine> output) throws LaunchException {
        try {
            docker.inspectImageCmd(image).exec();
            return false; // already here: no download
        } catch (NotFoundException e) {
            output.accept(new OutputLine(StreamKind.STDERR, "[devdesk] pulling image " + image + " ..."));
        }
        PullImageResultCallback pull = null;
        try {
            pull = docker.pullImageCmd(image).exec(new PullImageResultCallback() {
                @Override
                public void onNext(PullResponseItem item) {
                    super.onNext(item);
                    CrashPoint.reach("container.pulling");
                    // One line per finished layer, not per progress tick: enough to see it's moving.
                    if ("Pull complete".equals(item.getStatus())) {
                        output.accept(new OutputLine(StreamKind.STDERR, "[devdesk] layer " + item.getId() + " done"));
                    }
                }
            });
            pull.awaitCompletion();
        } catch (DockerException e) {
            throw new LaunchException(
                    "could not pull image " + image + ": " + clean(e)
                            + ". Check the name and tag, and that you're online (try: docker pull " + image + ")",
                    e);
        } catch (InterruptedException e) {
            // A stop arrived while pulling: stop reading the download, so nothing keeps going in the background.
            try {
                pull.close();
            } catch (java.io.IOException closing) {
                // already closed
            }
            Thread.currentThread().interrupt();
            throw new LaunchException("stopped while pulling " + image, e);
        }
        return true;
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
        // The key (name + folder hash) too: two projects with the same name in different folders get the same
        // container name, and one must never remove the other's container.
        boolean ours = labels != null
                && spec.project().equals(labels.get(PROJECT_LABEL))
                && spec.projectKey().equals(labels.get(PROJECT_KEY_LABEL))
                && spec.service().equals(labels.get(SERVICE_LABEL));
        if (!ours && labels != null && spec.project().equals(labels.get(PROJECT_LABEL))) {
            throw new LaunchException(
                    "a container named " + name + " already runs for another DevDesk project also called \""
                            + spec.project() + "\" (in another folder), so DevDesk won't touch it. Stop that project"
                            + " first, or give one of them another name: in devdesk.yaml",
                    null);
        }
        if (!ours) {
            throw new LaunchException(
                    "a container named " + name + " already exists and wasn't created by DevDesk, "
                            + "so DevDesk won't touch it. Rename or remove it yourself: docker rm -f " + name,
                    null);
        }
        try {
            docker.removeContainerCmd(existing.getId())
                    .withForce(true)
                    .withRemoveVolumes(true) // anonymous volumes only, as in removeQuietly
                    .exec();
        } catch (NotFoundException e) {
            // removed meanwhile (the cleanup after its previous run exited): fine
        }
    }

    private static Ports portBindings(Workload.Container c) {
        Ports ports = new Ports();
        for (Workload.PortMapping p : c.ports()) {
            ports.bind(
                    p.udp() ? ExposedPort.udp(p.containerPort()) : ExposedPort.tcp(p.containerPort()),
                    Ports.Binding.bindIpAndPort(p.hostAddress(), p.hostPort()));
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
        addresses.remove(id);
        String key = Optional.ofNullable(keys.remove(id)).orElseGet(() -> projectKeyOf(id));
        removeContainer(id);
        if (key != null) {
            network.removeIfUnused(key);
        }
    }

    private String projectKeyOf(String id) {
        try {
            Map<String, String> labels =
                    docker.inspectContainerCmd(id).exec().getConfig().getLabels();
            return labels == null ? null : labels.get(PROJECT_KEY_LABEL);
        } catch (NotFoundException e) {
            return null;
        }
    }

    private void removeContainer(String id) {
        try {
            // RemoveVolumes removes only the container's anonymous volumes (the ones an image's VOLUME line creates
            // on every start: Postgres leaves ~200 MB each time). Named volumes hold the data and are never removed.
            docker.removeContainerCmd(id)
                    .withForce(true)
                    .withRemoveVolumes(true)
                    .exec();
        } catch (NotFoundException e) {
            // already gone
        }
    }

    /** Docker error bodies look like {"message":"..."}: keep only the message. */
    static String clean(DockerException e) {
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

    /** Labels docker compose puts on its containers: the folder it was started in. */
    private static final String COMPOSE_FOLDER_LABEL = "com.docker.compose.project.working_dir";

    /**
     * What Docker runs right now, for the setup wizard's scan: a database someone started by hand can become a card
     * with the same settings and data. Only reads; nothing is stopped.
     */
    public List<FolderScan.RunningContainer> running() {
        List<FolderScan.RunningContainer> found = new ArrayList<>();
        docker.listContainersCmd().exec().forEach(c -> {
            Map<String, String> labels = c.getLabels() == null ? Map.of() : c.getLabels();
            List<String> ports = new ArrayList<>();
            for (var p : c.getPorts() == null ? new ContainerPort[0] : c.getPorts()) {
                // Docker lists IPv4 and IPv6 bindings separately; one "host:container" line is enough.
                String line = p.getPublicPort() + ":" + p.getPrivatePort();
                if (p.getPublicPort() != null && "tcp".equals(p.getType()) && !ports.contains(line)) {
                    ports.add(line);
                }
            }
            List<String> volumes = new ArrayList<>();
            if (c.getMounts() != null) {
                c.getMounts().stream()
                        .filter(m -> m.getName() != null) // only named volumes have a name; bind mounts don't
                        .forEach(m -> volumes.add(m.getName() + ":" + m.getDestination()));
            }
            Map<String, String> env = new LinkedHashMap<>();
            try {
                String[] vars =
                        docker.inspectContainerCmd(c.getId()).exec().getConfig().getEnv();
                for (String v : vars == null ? new String[0] : vars) {
                    int eq = v.indexOf('=');
                    // PATH, HOME… come from the image itself; only the variables someone chose are worth copying.
                    if (eq > 0
                            && !Set.of(
                                            "PATH",
                                            "HOME",
                                            "HOSTNAME",
                                            "LANG",
                                            "GOSU_VERSION",
                                            "PG_MAJOR",
                                            "PG_VERSION",
                                            "PGDATA",
                                            "REDIS_VERSION",
                                            "REDIS_DOWNLOAD_URL",
                                            "REDIS_DOWNLOAD_SHA")
                                    .contains(v.substring(0, eq))) {
                        env.put(v.substring(0, eq), v.substring(eq + 1));
                    }
                }
            } catch (DockerException e) {
                // it stopped meanwhile: list it without its variables
            }
            found.add(new FolderScan.RunningContainer(
                    c.getNames()[0].substring(1),
                    c.getImage(),
                    ports,
                    env,
                    volumes,
                    labels.getOrDefault(COMPOSE_FOLDER_LABEL, ""),
                    labels.containsKey(PROJECT_LABEL)));
        });
        return found;
    }

    /** The images Docker already has, as "name:tag" (what the doctor compares devdesk.yaml's images with). */
    public Set<String> localImages() {
        Set<String> tags = new HashSet<>();
        docker.listImagesCmd().exec().forEach(i -> {
            if (i.getRepoTags() != null) {
                tags.addAll(List.of(i.getRepoTags()));
            }
        });
        return tags;
    }

    /**
     * The image id each reference ("postgres:18") points to on this machine now. A tag like ":latest" can point to a
     * new image after a pull: comparing ids shows it. Images that aren't here are left out.
     */
    public Map<String, String> imageIds(java.util.Collection<String> images) {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        for (String image : images) {
            try {
                out.put(image, docker.inspectImageCmd(image).exec().getId());
            } catch (RuntimeException e) {
                // not pulled (yet): nothing to compare
            }
        }
        return out;
    }

    /** Throwaway replicas for "Prove it", through the same Docker connection. */
    public DockerImageReplay imageReplay(java.util.random.RandomGenerator random) {
        return new DockerImageReplay(docker, random);
    }

    /**
     * Replicas of a service of {@code projectKey}: they and their database copies carry the project's key, so Remove
     * and the leftover check find one a DevDesk killed mid-proof left behind.
     */
    public DockerImageReplay imageReplay(java.util.random.RandomGenerator random, String projectKey) {
        return new DockerImageReplay(docker, random, Optional.of(projectKey), this::containersReachable);
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
