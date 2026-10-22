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
import io.github.louch.devdesk.core.health.ProbeResult;
import io.github.louch.devdesk.core.process.LaunchException;
import io.github.louch.devdesk.core.project.Workload;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
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

    DockerImageReplay(DockerClient docker, RandomGenerator random) {
        this.docker = docker;
        this.random = random;
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

    @Override
    public Replica start(String image, Workload.Container c, Map<String, String> environment) throws LaunchException {
        byte[] bytes = new byte[4];
        random.nextBytes(bytes);
        String name = "devdesk-proof-" + HexFormat.of().formatHex(bytes);
        String network;
        try {
            // Its own network: the replica can't reach (or be reached by) the real services by their names.
            network = docker.createNetworkCmd()
                    .withName(name)
                    .withDriver("bridge")
                    .withLabels(Map.of(PROOF_LABEL, "true"))
                    .exec()
                    .getId();
        } catch (DockerException e) {
            throw new LaunchException(
                    "Docker could not create a network for the replay: " + DockerContainerRuntime.clean(e), e);
        }
        Ports ports = new Ports();
        // Port 0 on 127.0.0.1: Docker picks a free port, so a replica never collides with a real service.
        c.ports().forEach(p -> ports.bind(ExposedPort.tcp(p.containerPort()), Ports.Binding.bindIp("127.0.0.1")));
        String id;
        try {
            var create = docker.createContainerCmd(image)
                    .withName(name)
                    .withLabels(Map.of(PROOF_LABEL, "true"))
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
            removeNetwork(network);
            throw new LaunchException(
                    "Docker could not create the replay of " + image + ": "
                            + (e instanceof DockerException d ? DockerContainerRuntime.clean(d) : e.getMessage()),
                    e);
        }
        networks.put(id, network);
        Replica replica = new Replica(id, name, Map.of());
        try {
            docker.startContainerCmd(id).exec();
            return new Replica(id, name, hostPorts(id));
        } catch (DockerException e) {
            remove(replica);
            throw new LaunchException(
                    "Docker could not start the replay of " + image + ": " + DockerContainerRuntime.clean(e), e);
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
        try {
            // Force: SIGKILL is fine for a throwaway. Volumes(true) removes only the anonymous volumes the image
            // declared for this container (e.g. postgres' data folder), never named ones: none were mounted.
            docker.removeContainerCmd(r.id())
                    .withForce(true)
                    .withRemoveVolumes(true)
                    .exec();
        } catch (NotFoundException e) {
            // already gone
        } catch (DockerException e) {
            // reported by the leftover check (label devdesk.proof)
        }
        String network = networks.remove(r.id());
        if (network != null) {
            removeNetwork(network);
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
