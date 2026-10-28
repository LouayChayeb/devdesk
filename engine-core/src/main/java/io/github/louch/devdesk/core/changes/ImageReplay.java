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

    /**
     * The same, with fresh, empty copies of the databases it needs started first on the replica's own network, each
     * reachable by its service name (so {@code db:5432} works), without volumes or published ports. They're removed
     * with the replica.
     */
    default Replica start(
            String image, Workload.Container container, Map<String, String> environment, List<Helper> databases)
            throws LaunchException {
        if (!databases.isEmpty()) {
            throw new LaunchException("this replay can't start copies of databases", null);
        }
        return start(image, container, environment);
    }

    /**
     * A database the replica needs: a fresh copy of it is started next to the replica.
     *
     * @param service its name in the project: the copy answers to it on the replica's network
     * @param environment what the real one gets (its user and password, so the app's settings match)
     */
    record Helper(String service, Workload.Container container, Map<String, String> environment) {
        public Helper {
            environment = Map.copyOf(environment);
        }
    }

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
