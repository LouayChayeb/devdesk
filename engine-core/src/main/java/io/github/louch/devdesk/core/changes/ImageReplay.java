package io.github.louch.devdesk.core.changes;

import io.github.louch.devdesk.core.health.ProbeResult;
import io.github.louch.devdesk.core.process.LaunchException;
import io.github.louch.devdesk.core.project.Workload;
import java.util.List;
import java.util.Map;

/**
 * The port "Prove it" uses to run throwaway copies of a container. Docker implements it in engine-docker.
 *
 * <p>Every implementation must keep a replica away from the real project: its own network, random host ports on
 * 127.0.0.1, no volumes, its own name and labels. Removing it must never touch anything else.
 */
public interface ImageReplay {

    /** Is this image (an id like "sha256:..." or a reference like "postgres:18") on this machine? */
    boolean has(String image);

    /** Starts a replica of {@code container} on {@code image}, without volumes, on its own network and random ports. */
    Replica start(String image, Workload.Container container, Map<String, String> environment) throws LaunchException;

    /** Is the replica's main process still running? */
    boolean running(Replica replica);

    /** The image's own HEALTHCHECK verdict, for {@code health: { type: docker }}. */
    ProbeResult dockerHealth(Replica replica);

    /** Its last output lines, as evidence when it fails. */
    List<String> lastOutput(Replica replica, int lines);

    /** Removes the replica and its network. Never throws; never touches anything it didn't create. */
    void remove(Replica replica);

    /**
     * A running throwaway copy.
     *
     * @param hostPorts container port → the random 127.0.0.1 port Docker gave it
     */
    record Replica(String id, String name, Map<Integer, Integer> hostPorts) {
        public Replica {
            hostPorts = Map.copyOf(hostPorts);
        }
    }
}
