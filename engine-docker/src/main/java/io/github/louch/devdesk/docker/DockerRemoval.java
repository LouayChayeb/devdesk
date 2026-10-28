package io.github.louch.devdesk.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.exception.DockerException;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Container;
import io.github.louch.devdesk.core.project.ProjectSpec;
import io.github.louch.devdesk.core.removal.Removable;
import io.github.louch.devdesk.core.removal.RemovalPort;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What DevDesk made in Docker for one project: containers, its network, volumes it created and images it built carry
 * the project's key as a label; images it downloaded are in the {@link PullLog}. Nothing else is ever listed.
 */
public final class DockerRemoval implements RemovalPort {

    private final DockerClient docker;
    private final PullLog pulls;

    DockerRemoval(DockerClient docker, PullLog pulls) {
        this.docker = docker;
        this.pulls = pulls;
    }

    @Override
    public List<Removable> find(ProjectSpec project, boolean cloned) {
        String key = project.key();
        Map<String, String> ours = Map.of(DockerContainerRuntime.PROJECT_KEY_LABEL, key);
        List<Removable> found = new ArrayList<>();
        try {
            for (Container c : docker.listContainersCmd()
                    .withShowAll(true)
                    .withLabelFilter(ours)
                    .exec()) {
                found.add(new Removable(Removable.Kind.CONTAINER, c.getId(), "container " + name(c), false));
            }
            docker.listNetworksCmd()
                    .withFilter("label", List.of(DockerContainerRuntime.PROJECT_KEY_LABEL + "=" + key))
                    .exec()
                    .forEach(n -> found.add(
                            new Removable(Removable.Kind.NETWORK, n.getId(), "network " + n.getName(), false)));
            var volumes = docker.listVolumesCmd()
                    .withFilter("label", List.of(DockerContainerRuntime.PROJECT_KEY_LABEL + "=" + key))
                    .exec()
                    .getVolumes();
            (volumes == null ? List.<com.github.dockerjava.api.command.InspectVolumeResponse>of() : volumes)
                    .forEach(v -> found.add(new Removable(
                            Removable.Kind.VOLUME,
                            v.getName(),
                            "volume " + v.getName() + " (its data goes too)",
                            true)));
            docker.listImagesCmd()
                    .withLabelFilter(ours)
                    .exec()
                    .forEach(i -> found.add(new Removable(
                            Removable.Kind.IMAGE,
                            i.getId(),
                            "image " + tag(i.getRepoTags(), i.getId()) + " (built by DevDesk)",
                            false)));
            for (String image : pulls.pulledFor(key)) {
                if (exists(image) && !pulls.usedByAnother(image, key) && !usedByOthers(image, key)) {
                    found.add(new Removable(
                            Removable.Kind.IMAGE, image, "image " + image + " (downloaded by DevDesk for it)", false));
                }
            }
        } catch (DockerException e) {
            // Docker can't answer now: nothing of it can be listed or removed. The local side still is.
        }
        return found;
    }

    @Override
    public Optional<String> delete(Removable r) {
        try {
            switch (r.kind()) {
                case CONTAINER ->
                    docker.removeContainerCmd(r.id())
                            .withForce(true)
                            .withRemoveVolumes(true) // anonymous volumes only: named ones are listed on their own
                            .exec();
                case NETWORK -> docker.removeNetworkCmd(r.id()).exec();
                case VOLUME -> docker.removeVolumeCmd(r.id()).exec();
                case IMAGE -> {
                    docker.removeImageCmd(r.id()).exec(); // never forced: an image in use stays, and says why
                    pulls.forget(r.id()); // a downloaded one: its record goes with it
                }
                default -> {
                    return Optional.of("not a Docker item");
                }
            }
            return Optional.empty();
        } catch (NotFoundException e) {
            return Optional.empty(); // gone already
        } catch (DockerException e) {
            return Optional.of(DockerContainerRuntime.clean(e));
        }
    }

    private boolean exists(String image) {
        try {
            docker.inspectImageCmd(image).exec();
            return true;
        } catch (NotFoundException e) {
            return false;
        }
    }

    /** A container that isn't this project's (running or not) still uses it: deleting it would break that one. */
    private boolean usedByOthers(String image, String key) {
        return docker.listContainersCmd().withShowAll(true).exec().stream()
                .filter(c -> c.getLabels() == null
                        || !key.equals(c.getLabels().get(DockerContainerRuntime.PROJECT_KEY_LABEL)))
                .anyMatch(c -> image.equals(c.getImage()) || normalized(image).equals(normalized(c.getImage())));
    }

    private static String normalized(String image) {
        String i = image.replaceFirst("^docker\\.io/", "").replaceFirst("^library/", "");
        return i.contains(":") ? i : i + ":latest";
    }

    private static String name(Container c) {
        return c.getNames() == null || c.getNames().length == 0 ? c.getId() : c.getNames()[0].replaceFirst("^/", "");
    }

    private static String tag(String[] tags, String id) {
        return tags == null || tags.length == 0 ? id : tags[0];
    }
}
