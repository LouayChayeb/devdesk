package io.github.louch.devdesk.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.HealthState;
import com.github.dockerjava.api.exception.DockerException;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Ports;
import io.github.louch.devdesk.core.changes.ImageReplay;
import io.github.louch.devdesk.core.crashpoint.CrashPoint;
import io.github.louch.devdesk.core.health.ProbeResult;
import io.github.louch.devdesk.core.process.LaunchException;
import io.github.louch.devdesk.core.project.Workload;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.random.RandomGenerator;

/**
 * Throwaway replicas for "Prove it". Each one gets its own bridge network ({@code devdesk-proof-<random>}), random
 * host ports on 127.0.0.1, no volumes, and the label {@code devdesk.proof}. Removing it removes exactly the container
 * and network it created, by id, and the anonymous volumes the image declared for itself.
 */
public final class DockerImageReplay implements ImageReplay {

    /** Marks replicas: a leftover (DevDesk killed mid-proof) is recognisable as a proof, never as a real service. */
    static final String PROOF_LABEL = "devdesk.proof";

    private final DockerClient docker;
    private final RandomGenerator random;
    /** Replica id → its network id, to remove both. */
    private final Map<String, String> networks = new java.util.concurrent.ConcurrentHashMap<>();
    /** Replica id → the ids of its database copies, removed with it. */
    private final Map<String, List<String>> databases = new java.util.concurrent.ConcurrentHashMap<>();
    /** The project whose service is replayed: its key goes on everything, so Remove finds a leftover copy too. */
    private final Optional<String> projectKey;
    /** Whether this machine reaches containers by their own address (not Docker Desktop, not rootless). */
    private final BooleanSupplier reachable;
    /** Downloads a database image that isn't here yet (and records it as DevDesk's, as a real start would). */
    private final ImageFetch fetch;

    /** Makes sure an image is on this machine. */
    interface ImageFetch {
        void ensure(String image) throws LaunchException;
    }

    /** How long a database copy may take to accept connections (MySQL's first start takes the longest). */
    private static final Duration DATABASE_READY = Duration.ofSeconds(90);

    DockerImageReplay(DockerClient docker, RandomGenerator random) {
        this(docker, random, Optional.empty(), () -> false, image -> pull(docker, image));
    }

    DockerImageReplay(
            DockerClient docker,
            RandomGenerator random,
            Optional<String> projectKey,
            BooleanSupplier reachable,
            ImageFetch fetch) {
        this.docker = docker;
        this.random = random;
        this.projectKey = projectKey;
        this.reachable = reachable;
        this.fetch = fetch;
    }

    @Override
    public boolean has(String image) {
        try {
            docker.inspectImageCmd(image).exec();
            return true;
        } catch (DockerException e) {
            return false; // not here (404), or Docker can't say: either way it can't be replayed
        }
    }

    /** Which DevDesk made a replica: its PID and start time, so a dead owner's replicas can be told apart. */
    static final String OWNER_LABEL = "devdesk.proof-owner";

    private Map<String, String> labels() {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put(PROOF_LABEL, "true");
        labels.put(OWNER_LABEL, owner(ProcessHandle.current()));
        // No service label: a copy must never be taken for the real service by the leftover check.
        projectKey.ifPresent(k -> labels.put(DockerContainerRuntime.PROJECT_KEY_LABEL, k));
        return labels;
    }

    /** "<pid>@<start instant>": a PID alone could be reused by another program after the owner died. */
    static String owner(ProcessHandle p) {
        return p.pid() + "@" + p.info().startInstant().map(Object::toString).orElse("?");
    }

    /**
     * Removes replicas (and their networks) whose DevDesk died mid-proof (SIGKILL, crash): the owner label names a
     * process that no longer exists, or a PID now used by a later program. A replica whose owner still runs (a proof
     * in progress elsewhere) and anything without the proof label are never touched. Returns what was removed.
     */
    public List<String> removeAbandoned() {
        List<String> removed = new ArrayList<>();
        try {
            for (var c : docker.listContainersCmd()
                    .withShowAll(true)
                    .withLabelFilter(Map.of(PROOF_LABEL, "true"))
                    .exec()) {
                if (abandoned(c.getLabels())) {
                    docker.removeContainerCmd(c.getId())
                            .withForce(true)
                            .withRemoveVolumes(true)
                            .exec();
                    removed.add(c.getNames()[0].substring(1));
                }
            }
            for (var n : docker.listNetworksCmd().exec()) {
                if (n.getLabels() != null && n.getLabels().containsKey(PROOF_LABEL) && abandoned(n.getLabels())) {
                    removeNetwork(n.getId());
                    removed.add("network " + n.getName());
                }
            }
        } catch (DockerException e) {
            // Docker went away: tried again next time
        }
        return removed;
    }

    private static boolean abandoned(Map<String, String> labels) {
        String owner = labels == null ? null : labels.get(OWNER_LABEL);
        if (owner == null || !owner.contains("@")) {
            return false; // no owner recorded: can't prove it's abandoned, so it's left alone
        }
        try {
            long pid = Long.parseLong(owner.substring(0, owner.indexOf('@')));
            return ProcessHandle.of(pid).map(p -> !owner(p).equals(owner)).orElse(true);
        } catch (NumberFormatException e) {
            return false;
        }
    }

    @Override
    public Replica start(String image, Workload.Container c, Map<String, String> environment) throws LaunchException {
        return start(image, c, environment, List.of());
    }

    @Override
    public Replica start(String image, Workload.Container c, Map<String, String> environment, List<Helper> helpers)
            throws LaunchException {
        removeAbandoned(); // what a DevDesk killed mid-proof left
        byte[] bytes = new byte[4];
        random.nextBytes(bytes);
        String name = "devdesk-proof-" + HexFormat.of().formatHex(bytes);
        String network;
        try {
            // Its own network: the replica can't reach (or be reached by) the real services by their names.
            network = docker.createNetworkCmd()
                    .withName(name)
                    .withDriver("bridge")
                    .withLabels(labels())
                    .exec()
                    .getId();
        } catch (DockerException e) {
            throw new LaunchException(
                    "Docker could not create a network for the replay: " + DockerContainerRuntime.clean(e), e);
        }
        List<String> copies = new ArrayList<>();
        try {
            for (Helper h : helpers) {
                copies.add(startDatabase(h, name, network));
            }
            for (int i = 0; i < helpers.size(); i++) {
                waitUntilReady(helpers.get(i), copies.get(i), name);
            }
        } catch (DockerException | LaunchException e) {
            copies.forEach(this::removeContainer);
            removeNetwork(network);
            throw e instanceof LaunchException l
                    ? l
                    : new LaunchException(
                            "Docker could not start a copy of a database: "
                                    + DockerContainerRuntime.clean((DockerException) e),
                            e);
        }
        Ports ports = new Ports();
        // Port 0 on 127.0.0.1: Docker picks a free port, so a replica never collides with a real service.
        c.ports().forEach(p -> ports.bind(ExposedPort.tcp(p.containerPort()), Ports.Binding.bindIp("127.0.0.1")));
        String id;
        try {
            var create = docker.createContainerCmd(image)
                    .withName(name)
                    .withLabels(labels())
                    .withEnv(environment.entrySet().stream()
                            .map(e -> e.getKey() + "=" + e.getValue())
                            .toList())
                    .withExposedPorts(c.ports().stream()
                            .map(p -> ExposedPort.tcp(p.containerPort()))
                            .toList())
                    // No binds: the replica never sees the real volumes.
                    .withHostConfig(
                            HostConfig.newHostConfig().withPortBindings(ports).withNetworkMode(name));
            DockerContainerRuntime.arguments(c).ifPresent(create::withCmd);
            id = create.exec().getId();
        } catch (DockerException | LaunchException e) {
            copies.forEach(this::removeContainer);
            removeNetwork(network);
            throw new LaunchException(
                    "Docker could not create the replay of " + image + ": "
                            + (e instanceof DockerException d ? DockerContainerRuntime.clean(d) : e.getMessage()),
                    e);
        }
        networks.put(id, network);
        databases.put(id, copies);
        Replica replica = new Replica(id, name, Map.of());
        try {
            docker.startContainerCmd(id).exec();
            CrashPoint.reach(CrashPoint.PROOF_STARTED);
            return new Replica(id, name, hostPorts(id));
        } catch (DockerException e) {
            remove(replica);
            throw new LaunchException(
                    "Docker could not start the replay of " + image + ": " + DockerContainerRuntime.clean(e), e);
        }
    }

    /** Downloads {@code image} when it isn't here (without recording it: no project to record it for). */
    private static void pull(DockerClient docker, String image) throws LaunchException {
        try {
            docker.inspectImageCmd(image).exec();
        } catch (NotFoundException missing) {
            try {
                docker.pullImageCmd(image)
                        .exec(new com.github.dockerjava.api.command.PullImageResultCallback())
                        .awaitCompletion();
            } catch (DockerException e) {
                throw new LaunchException("could not download " + image + ": " + DockerContainerRuntime.clean(e), e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new LaunchException("stopped while downloading " + image, e);
            }
        }
    }

    /**
     * A fresh, empty copy of a database on the replica's network, answering to the service's name ({@code db}), so
     * the app's settings ({@code db:5432}) work unchanged. No volumes and no published ports: it is reachable only
     * from the replica, and its data dies with it.
     */
    private String startDatabase(Helper h, String replica, String network) throws LaunchException {
        // On a fresh machine the database's image may not be here yet: a copy needs it like the real one does.
        fetch.ensure(h.container().image());
        var create = docker.createContainerCmd(h.container().image())
                .withName(replica + "-" + h.service())
                .withLabels(labels())
                .withEnv(h.environment().entrySet().stream()
                        .map(e -> e.getKey() + "=" + e.getValue())
                        .toList())
                // Docker's DNS on this network answers "db" with this container's address.
                .withAliases(h.service())
                .withHostConfig(HostConfig.newHostConfig().withNetworkMode(network));
        DockerContainerRuntime.arguments(h.container()).ifPresent(create::withCmd);
        String id = create.exec().getId();
        docker.startContainerCmd(id).exec();
        return id;
    }

    /**
     * Waits until the copy accepts connections on its ports, at its own address on the replica's network, so the
     * app doesn't fail only because its database was still starting. Where containers can't be reached by address
     * (Docker Desktop), it can only wait a little and check the copy still runs.
     */
    private void waitUntilReady(Helper h, String id, String network) throws LaunchException {
        List<Integer> ports = h.container().ports().stream()
                .filter(p -> !p.udp())
                .map(Workload.PortMapping::containerPort)
                .toList();
        long deadline = System.nanoTime() + DATABASE_READY.toNanos();
        try {
            if (ports.isEmpty() || !reachable.getAsBoolean()) {
                Thread.sleep(3000);
                if (!running(id)) {
                    throw exited(h, id);
                }
                return;
            }
            String ip = docker.inspectContainerCmd(id)
                    .exec()
                    .getNetworkSettings()
                    .getNetworks()
                    .get(network)
                    .getIpAddress();
            while (System.nanoTime() < deadline) {
                if (!running(id)) {
                    throw exited(h, id);
                }
                if (ports.stream().allMatch(p -> accepts(ip, p))) {
                    return;
                }
                Thread.sleep(250);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LaunchException("interrupted while its copy of " + h.service() + " was starting", e);
        }
        throw new LaunchException(
                "its copy of " + h.service() + " didn't accept connections within " + DATABASE_READY.toSeconds() + " s",
                null);
    }

    private LaunchException exited(Helper h, String id) {
        return new LaunchException(
                "its copy of " + h.service() + " (" + h.container().image() + ") exited: "
                        + String.join(" | ", lastOutput(new Replica(id, id, Map.of()), 3)),
                null);
    }

    /** A TCP connection to the copy's own address (the kernel's three-way handshake completes only if it listens). */
    private static boolean accepts(String ip, int port) {
        try (var socket = new java.net.Socket()) {
            socket.connect(new java.net.InetSocketAddress(ip, port), 500);
            return true;
        } catch (java.io.IOException e) {
            return false;
        }
    }

    private boolean running(String id) {
        try {
            return Boolean.TRUE.equals(
                    docker.inspectContainerCmd(id).exec().getState().getRunning());
        } catch (DockerException e) {
            return false;
        }
    }

    private Map<Integer, Integer> hostPorts(String id) {
        Map<Integer, Integer> out = new LinkedHashMap<>();
        var bindings = docker.inspectContainerCmd(id)
                .exec()
                .getNetworkSettings()
                .getPorts()
                .getBindings();
        bindings.forEach((exposed, hosts) -> {
            if (hosts != null && hosts.length > 0) {
                out.put(exposed.getPort(), Integer.parseInt(hosts[0].getHostPortSpec()));
            }
        });
        return out;
    }

    @Override
    public boolean running(Replica r) {
        try {
            return Boolean.TRUE.equals(
                    docker.inspectContainerCmd(r.id()).exec().getState().getRunning());
        } catch (DockerException e) {
            return false;
        }
    }

    @Override
    public ProbeResult dockerHealth(Replica r) {
        HealthState health;
        try {
            health = docker.inspectContainerCmd(r.id()).exec().getState().getHealth();
        } catch (DockerException e) {
            return new ProbeResult.Failed("could not ask Docker: " + DockerContainerRuntime.clean(e));
        }
        if (health == null) {
            return new ProbeResult.Failed("the image has no HEALTHCHECK");
        }
        return "healthy".equals(health.getStatus())
                ? new ProbeResult.Passed("Docker reports it healthy")
                : new ProbeResult.Failed("Docker reports it " + health.getStatus());
    }

    @Override
    public List<String> lastOutput(Replica r, int lines) {
        // Filled by docker-java's callback thread, read here.
        List<String> out = java.util.Collections.synchronizedList(new ArrayList<>());
        try {
            docker.logContainerCmd(r.id())
                    .withStdOut(true)
                    .withStdErr(true)
                    .withTail(lines)
                    .exec(new ResultCallback.Adapter<Frame>() {
                        @Override
                        public void onNext(Frame frame) {
                            new String(frame.getPayload(), StandardCharsets.UTF_8)
                                    .lines()
                                    .filter(l -> !l.isBlank())
                                    .forEach(out::add);
                        }
                    })
                    .awaitCompletion(5, TimeUnit.SECONDS);
        } catch (DockerException e) {
            // no output to show
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        synchronized (out) {
            return List.copyOf(out.subList(Math.max(0, out.size() - lines), out.size()));
        }
    }

    @Override
    public void remove(Replica r) {
        List<String> copies = databases.remove(r.id());
        if (copies != null) {
            copies.forEach(this::removeContainer);
        }
        removeContainer(r.id());
        String network = networks.remove(r.id());
        if (network != null) {
            removeNetwork(network);
        }
    }

    private void removeContainer(String id) {
        try {
            // Force: SIGKILL is fine for a throwaway. Volumes(true) removes only the anonymous volumes the image
            // declared for this container (e.g. postgres' data folder), never named ones: none were mounted.
            docker.removeContainerCmd(id)
                    .withForce(true)
                    .withRemoveVolumes(true)
                    .exec();
        } catch (NotFoundException e) {
            // already gone
        } catch (DockerException e) {
            // reported by the leftover check (label devdesk.proof)
        }
    }

    private void removeNetwork(String id) {
        try {
            docker.removeNetworkCmd(id).exec();
        } catch (DockerException e) {
            // already gone, or a leftover the leftover check reports
        }
    }

    /** Replicas left by a DevDesk killed mid-proof: only things carrying the proof label. For tests and cleanup. */
    List<String> leftoverProofs() {
        List<String> names = new ArrayList<>();
        docker.listContainersCmd()
                .withShowAll(true)
                .withLabelFilter(Map.of(PROOF_LABEL, "true"))
                .exec()
                .forEach(c -> names.add(c.getNames()[0].substring(1)));
        docker.listNetworksCmd().exec().stream()
                .filter(n -> n.getLabels() != null && n.getLabels().containsKey(PROOF_LABEL))
                .forEach(n -> names.add(n.getName()));
        return names;
    }
}
