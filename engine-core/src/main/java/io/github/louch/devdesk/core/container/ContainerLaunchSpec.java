package io.github.louch.devdesk.core.container;

import io.github.louch.devdesk.core.project.Workload;
import java.util.Map;

/**
 * What to run as a container, and who it belongs to.
 *
 * @param projectKey the project's key (name + folder hash): a label that proves which project a leftover belongs to
 * @param project and {@code service} become labels on the container: DevDesk only ever touches containers carrying
 *     its own labels
 * @param environment exactly what the container gets: never the host's whole environment
 */
public record ContainerLaunchSpec(
        String project,
        String projectKey,
        String service,
        Workload.Container container,
        Map<String, String> environment) {

    /** For tests and tools: the key is the project name. */
    public ContainerLaunchSpec(
            String project, String service, Workload.Container container, Map<String, String> environment) {
        this(project, project, service, container, environment);
    }

    public ContainerLaunchSpec {
        environment = Map.copyOf(environment);
    }
}
