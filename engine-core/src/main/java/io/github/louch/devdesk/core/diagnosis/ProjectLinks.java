package io.github.louch.devdesk.core.diagnosis;

import io.github.louch.devdesk.core.network.Connection;
import io.github.louch.devdesk.core.network.Links;
import io.github.louch.devdesk.core.project.ProjectSpec;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Who talks to whom in a running project, asked from the machine. Shared by `devdesk ps`, the app and the rules. */
public final class ProjectLinks {

    private ProjectLinks() {}

    /**
     * @param pids each running service's processes. Containers have none on the host (their sockets live in their own
     *     network namespace), but their declared ports still make them a destination.
     */
    public static List<Links.Link> of(ProjectSpec project, Map<String, Set<Long>> pids, Introspection system) {
        Map<String, Set<Integer>> listening = new HashMap<>();
        project.services().values().forEach(s -> {
            Set<Integer> ports = new HashSet<>(s.ports());
            system.listenersOf(pids.getOrDefault(s.name(), Set.of())).forEach(l -> ports.add(l.port()));
            listening.put(s.name(), ports);
        });
        Map<String, List<Connection>> connections = new HashMap<>();
        pids.forEach((service, p) -> connections.put(service, system.connectionsOf(p)));
        return Links.between(connections, listening);
    }
}
