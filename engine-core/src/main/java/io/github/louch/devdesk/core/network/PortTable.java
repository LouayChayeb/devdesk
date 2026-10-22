package io.github.louch.devdesk.core.network;

import io.github.louch.devdesk.core.project.ProjectSpec;
import io.github.louch.devdesk.core.project.ServiceSpec;
import io.github.louch.devdesk.core.project.Workload;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/** Every listening port on the machine and who owns it, seen from one project (the app's Ports tab). */
public final class PortTable {

    /** Who holds a port, from this project's point of view. */
    public enum Owner {
        THIS_PROJECT,
        /** DevDesk's own engine (its local API). */
        DEVDESK,
        OTHER_DEVDESK_PROJECT,
        CONTAINER,
        NOT_DEVDESK
    }

    /**
     * One listening socket.
     *
     * @param who the service ("api"), the other project's key, or empty
     * @param conflict a port this project declares, held by something that isn't its service: it can't start
     */
    public record Row(Listener listener, Owner owner, Optional<String> who, boolean conflict) {}

    private PortTable() {}

    /**
     * @param pids this project's running services and their processes
     * @param devdeskProjectOf the DevDesk project key a process belongs to (from its systemd scope), if any
     * @param self the PID of the DevDesk asking
     */
    public static List<Row> of(
            ProjectSpec project,
            Map<String, Set<Long>> pids,
            List<Listener> listening,
            Function<Long, Optional<String>> devdeskProjectOf,
            long self) {
        return listening.stream()
                .map(l -> l.pid().filter(p -> p == self).isPresent()
                        ? new Row(l, Owner.DEVDESK, Optional.empty(), false)
                        : row(project, pids, l, devdeskProjectOf))
                .sorted(Comparator.comparing((Row r) -> !r.conflict())
                        .thenComparingInt(r -> r.listener().port()))
                .toList();
    }

    private static Row row(
            ProjectSpec project,
            Map<String, Set<Long>> pids,
            Listener l,
            Function<Long, Optional<String>> devdeskProjectOf) {
        Optional<String> declaredBy = project.services().values().stream()
                .filter(s -> s.ports().contains(l.port()))
                .map(ServiceSpec::name)
                .findFirst();
        Optional<String> service = l.pid()
                .flatMap(pid -> pids.entrySet().stream()
                        .filter(e -> e.getValue().contains(pid))
                        .map(Map.Entry::getKey)
                        .findFirst());
        if (service.isPresent()) {
            return new Row(l, Owner.THIS_PROJECT, service, false);
        }
        boolean dockerProxy =
                l.command().filter(c -> c.contains("docker-proxy")).isPresent();
        if (dockerProxy) {
            // Docker's port forwarder holds published ports on the host. Ours if a container service declares it.
            Optional<String> ours =
                    declaredBy.filter(name -> project.services().get(name).workload() instanceof Workload.Container);
            return ours.map(name -> new Row(l, Owner.THIS_PROJECT, Optional.of(name), false))
                    .orElse(new Row(l, Owner.CONTAINER, Optional.empty(), declaredBy.isPresent()));
        }
        Optional<String> other = l.pid().flatMap(devdeskProjectOf);
        if (other.isPresent() && !other.get().equals(project.key())) {
            return new Row(l, Owner.OTHER_DEVDESK_PROJECT, other, declaredBy.isPresent());
        }
        return new Row(l, Owner.NOT_DEVDESK, Optional.empty(), declaredBy.isPresent());
    }
}
