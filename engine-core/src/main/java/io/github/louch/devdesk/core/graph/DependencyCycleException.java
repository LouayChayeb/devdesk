package io.github.louch.devdesk.core.graph;

import java.util.List;

/** Services depend on each other in a loop, so none of them could ever start first. */
public final class DependencyCycleException extends Exception {

    private static final long serialVersionUID = 1L;

    private final transient List<String> cycle;

    public DependencyCycleException(List<String> cycle) {
        super("dependency cycle: " + String.join(" → ", cycle) + ". Remove one of these depends_on entries");
        this.cycle = List.copyOf(cycle);
    }

    /** The loop, first service repeated at the end: [api, worker, api]. */
    public List<String> cycle() {
        return cycle;
    }
}
