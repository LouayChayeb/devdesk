package io.github.louch.devdesk.core.graph;

import io.github.louch.devdesk.core.project.Dependency;
import io.github.louch.devdesk.core.project.ServiceSpec;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The services of a project and who depends on whom. Guaranteed free of cycles once built.
 *
 * <p>All results follow file order where the graph allows a choice, so output is stable from run to run.
 */
public final class DependencyGraph {

    /** service → the services it needs. */
    private final Map<String, Set<String>> needs = new LinkedHashMap<>();
    /** service → the services that need it (the same edges, reversed). */
    private final Map<String, Set<String>> neededBy = new LinkedHashMap<>();

    private DependencyGraph(Collection<ServiceSpec> services) {
        for (ServiceSpec s : services) {
            needs.put(s.name(), new LinkedHashSet<>());
            neededBy.put(s.name(), new LinkedHashSet<>());
        }
        for (ServiceSpec s : services) {
            for (Dependency d : s.dependsOn()) {
                needs.get(s.name()).add(d.service());
                neededBy.get(d.service()).add(s.name());
            }
        }
    }

    /** Builds the graph. Every dependency must name an existing service (the config parser checks that first). */
    public static DependencyGraph of(Collection<ServiceSpec> services) throws DependencyCycleException {
        DependencyGraph graph = new DependencyGraph(services);
        graph.checkNoCycle();
        return graph;
    }

    /**
     * Start order as levels: everything in one level can start in parallel, once the previous levels are done.
     * Kahn's algorithm: repeatedly take every service whose dependencies are all already placed.
     */
    public List<List<String>> startLevels() {
        Map<String, Integer> waitingFor = new HashMap<>();
        needs.forEach((service, deps) -> waitingFor.put(service, deps.size()));
        List<List<String>> levels = new ArrayList<>();
        List<String> ready =
                needs.keySet().stream().filter(s -> waitingFor.get(s) == 0).toList();
        while (!ready.isEmpty()) {
            levels.add(ready);
            Set<String> next = new LinkedHashSet<>();
            for (String done : ready) {
                for (String dependent : neededBy.get(done)) {
                    if (waitingFor.merge(dependent, -1, Integer::sum) == 0) {
                        next.add(dependent);
                    }
                }
            }
            // Keep file order inside a level, not discovery order.
            ready = needs.keySet().stream().filter(next::contains).toList();
        }
        return levels;
    }

    /** Stop order: dependents before what they depend on (the start order, reversed). */
    public List<String> stopOrder() {
        List<String> order = new ArrayList<>();
        startLevels().reversed().forEach(level -> order.addAll(level.reversed()));
        return order;
    }

    /** Everything that (directly or indirectly) needs {@code service}: what breaks if it stops. */
    public Set<String> dependentsOf(String service) {
        return reachable(service, neededBy);
    }

    /** {@code service} plus everything it (directly or indirectly) needs: what "start api" must start. */
    public Set<String> withDependencies(String service) {
        Set<String> result = new LinkedHashSet<>(reachable(service, needs));
        result.add(service);
        return result;
    }

    /** Services that list {@code service} in their own depends_on. */
    public Set<String> directDependents(String service) {
        return Set.copyOf(neededBy.get(service));
    }

    /** The services {@code service} names in its own depends_on, without their dependencies in turn. */
    public Set<String> directDependencies(String service) {
        return Set.copyOf(needs.get(service));
    }

    private static Set<String> reachable(String start, Map<String, Set<String>> edges) {
        Set<String> seen = new LinkedHashSet<>();
        Deque<String> todo = new ArrayDeque<>(edges.get(start));
        while (!todo.isEmpty()) {
            String s = todo.pop();
            if (seen.add(s)) {
                todo.addAll(edges.get(s));
            }
        }
        return seen;
    }

    /** Depth-first search. Reaching a service that is still "in progress" on the current path means a loop. */
    private void checkNoCycle() throws DependencyCycleException {
        Set<String> done = new LinkedHashSet<>();
        for (String service : needs.keySet()) {
            List<String> path = new ArrayList<>();
            if (findCycle(service, path, done)) {
                throw new DependencyCycleException(path);
            }
        }
    }

    private boolean findCycle(String service, List<String> path, Set<String> done) {
        int onPath = path.indexOf(service);
        if (onPath >= 0) {
            path.add(service);
            path.subList(0, onPath).clear(); // keep only the loop itself: [api, worker, api]
            return true;
        }
        if (done.contains(service)) {
            return false;
        }
        path.add(service);
        for (String dep : needs.get(service)) {
            if (findCycle(dep, path, done)) {
                return true;
            }
        }
        path.removeLast();
        done.add(service);
        return false;
    }
}
