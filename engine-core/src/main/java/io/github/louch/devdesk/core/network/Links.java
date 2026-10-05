package io.github.louch.devdesk.core.network;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Turns raw connections into "who talks to whom": {@code web → api :3001 (2 open)}. Pure logic; the sockets come from
 * {@link io.github.louch.devdesk.core.diagnosis.Introspection#connectionsOf}.
 */
public final class Links {

    /**
     * Connections from one service to one place.
     *
     * @param to the service that listens there, or empty when it's outside the project (another program, the internet)
     * @param target "127.0.0.1:5433"
     */
    public record Link(String from, Optional<String> to, String target, int open) {}

    private Links() {}

    /**
     * @param connections each service's connections (from its own PIDs)
     * @param listening each service's ports it listens on (from its sockets, plus ports it declares, so containers
     *     whose host side is held by docker-proxy still count)
     */
    public static List<Link> between(Map<String, List<Connection>> connections, Map<String, Set<Integer>> listening) {
        Map<String, Link> links = new LinkedHashMap<>();
        connections.forEach((service, conns) -> {
            Set<Integer> own = listening.getOrDefault(service, Set.of());
            for (Connection c : conns) {
                if (own.contains(c.localPort())) {
                    continue; // someone connected *to* this service: counted on the caller's side, not twice
                }
                Optional<String> to = c.remoteIsLocal() ? serviceOn(c.remotePort(), listening) : Optional.empty();
                String target = c.remoteAddress() + ":" + c.remotePort();
                String key = service + "|" + to.orElse(target) + "|" + c.remotePort();
                links.merge(
                        key,
                        new Link(service, to, target, 1),
                        (a, b) -> new Link(a.from(), a.to(), a.target(), a.open() + 1));
            }
        });
        List<Link> sorted = new ArrayList<>(links.values());
        sorted.sort(Comparator.comparing(Link::from)
                .thenComparing(l -> l.to().isEmpty()) // services first, then the outside world
                .thenComparing(Link::target));
        return sorted;
    }

    private static Optional<String> serviceOn(int port, Map<String, Set<Integer>> listening) {
        return listening.entrySet().stream()
                .filter(e -> e.getValue().contains(port))
                .map(Map.Entry::getKey)
                .findFirst();
    }
}
