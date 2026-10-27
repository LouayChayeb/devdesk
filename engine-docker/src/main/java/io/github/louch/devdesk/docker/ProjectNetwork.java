package io.github.louch.devdesk.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.exception.ConflictException;
import com.github.dockerjava.api.exception.DockerException;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Network;
import io.github.louch.devdesk.core.process.LaunchException;
import java.util.Map;
import java.util.Optional;

/**
 * One private Docker network per project, so its containers reach each other by service name ("db:5432"), like
 * docker compose does. It carries the project's labels, so DevDesk only ever uses or removes its own.
 */
final class ProjectNetwork {

    private final DockerClient docker;

    ProjectNetwork(DockerClient docker) {
        this.docker = docker;
    }

    /** The project key already holds the name and a hash of the folder: two "shop" projects get two networks. */
    static String name(String projectKey) {
        return "devdesk-" + projectKey;
    }

    /** Creates the network if it's missing; refuses one of the same name that DevDesk didn't create. */
    String ensure(String project, String projectKey) throws LaunchException {
        String name = name(projectKey);
        Optional<Network> existing = find(name);
        if (existing.isEmpty()) {
            try {
                docker.createNetworkCmd()
                        .withName(name)
                        .withDriver("bridge")
                        .withLabels(Map.of(
                                DockerContainerRuntime.PROJECT_LABEL,
                                project,
                                DockerContainerRuntime.PROJECT_KEY_LABEL,
                                projectKey))
                        .exec();
                return name;
            } catch (ConflictException e) {
                // Services start in parallel: another one created it a moment ago. Check it like any existing one.
                existing = find(name);
            } catch (DockerException e) {
                throw new LaunchException(
                        "Docker could not create the network " + name + ": " + DockerContainerRuntime.clean(e), e);
            }
        }
        Map<String, String> labels = existing.map(Network::getLabels).orElse(Map.of());
        if (!projectKey.equals(labels == null ? null : labels.get(DockerContainerRuntime.PROJECT_KEY_LABEL))) {
            throw new LaunchException(
                    "a Docker network named " + name + " already exists and wasn't created by DevDesk for this "
                            + "project, so DevDesk won't use it. Remove it yourself if it's stale: docker network rm "
                            + name,
                    null);
        }
        return name;
    }

    /** Removes the project's network once no container uses it any more; a network still in use stays. */
    void removeIfUnused(String projectKey) {
        String name = name(projectKey);
        Optional<Network> network = find(name);
        Map<String, String> labels = network.map(Network::getLabels).orElse(Map.of());
        if (network.isEmpty()
                || !projectKey.equals(labels == null ? null : labels.get(DockerContainerRuntime.PROJECT_KEY_LABEL))) {
            return; // gone already, or not ours
        }
        Map<String, ?> attached = network.get().getContainers();
        if (attached != null && !attached.isEmpty()) {
            return; // another of the project's containers still runs on it
        }
        try {
            docker.removeNetworkCmd(network.get().getId()).exec();
        } catch (NotFoundException e) {
            // removed meanwhile by the stop of another service
        } catch (DockerException e) {
            // a container joined in between: the last one to stop removes it
        }
    }

    /** By exact name: Docker's name filter matches substrings, so "devdesk-shop" would also find "devdesk-shop2". */
    private Optional<Network> find(String name) {
        try {
            return Optional.of(docker.inspectNetworkCmd().withNetworkId(name).exec())
                    .filter(n -> name.equals(n.getName()));
        } catch (NotFoundException e) {
            return Optional.empty();
        }
    }
}
