package io.github.louch.devdesk.core.removal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.louch.devdesk.core.config.ConfigParser;
import io.github.louch.devdesk.core.project.ProjectSpec;
import io.github.louch.devdesk.core.removal.Removable.Kind;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

class ProjectRemovalTest {

    private final List<String> deleted = new CopyOnWriteArrayList<>();

    /** A place holding some things; deleting one makes it disappear, unless it's stuck. */
    private final class Place implements RemovalPort {
        final List<Removable> things;
        final List<String> stuck = new ArrayList<>();

        Place(Removable... things) {
            this.things = new CopyOnWriteArrayList<>(things);
        }

        @Override
        public List<Removable> find(ProjectSpec project, boolean cloned) {
            return things.stream()
                    .filter(r -> cloned || r.kind() != Kind.FOLDER)
                    .toList();
        }

        @Override
        public Optional<String> delete(Removable r) {
            if (stuck.contains(r.id())) {
                return Optional.of("in use");
            }
            deleted.add(r.id());
            things.remove(r);
            return Optional.empty();
        }
    }

    private static ProjectSpec project() throws Exception {
        return ConfigParser.parse(
                "version: 1\nservices:\n  api: { command: x }\n",
                Path.of("/home/me/DevDesk/shop"),
                Map.of(),
                f -> Optional.empty());
    }

    private static Removable item(Kind kind, String id, boolean data) {
        return new Removable(kind, id, kind + " " + id, data);
    }

    @Test
    void everythingGoesInOrderAndAFreshLookFindsNothing() throws Exception {
        var docker = new Place(
                item(Kind.IMAGE, "shop-api", false),
                item(Kind.VOLUME, "shop_pgdata", true),
                item(Kind.NETWORK, "devdesk-shop", false),
                item(Kind.CONTAINER, "c1", false));
        var machine = new Place(item(Kind.FOLDER, "/home/me/DevDesk/shop", true), item(Kind.STATE, "logs/shop", false));
        var removal = new ProjectRemoval(List.of(docker, machine));

        var plan = removal.plan(project(), true);
        var report = removal.remove(project(), true, plan);

        assertThat(plan.losesData()).isTrue(); // the confirmation must say the database data goes
        // What runs, then what it used, then what describes it, the folder last.
        assertThat(deleted)
                .containsExactly("c1", "devdesk-shop", "shop_pgdata", "shop-api", "logs/shop", "/home/me/DevDesk/shop");
        assertThat(report.clean()).isTrue();
    }

    @Test
    void aFolderTheUserOpenedIsNeverInThePlan() throws Exception {
        var machine = new Place(item(Kind.FOLDER, "/home/me/code/shop", true), item(Kind.STATE, "logs/shop", false));

        var plan = new ProjectRemoval(List.of(machine)).plan(project(), false);

        assertThat(plan.items()).extracting(Removable::kind).containsExactly(Kind.STATE);
    }

    @Test
    void onlyWhatAPortFindsNowIsDeletedWhateverThePlanSays() throws Exception {
        var docker = new Place(item(Kind.CONTAINER, "c1", false));
        var removal = new ProjectRemoval(List.of(docker));
        // A plan that somehow names the user's own volume: nothing finds it, so nothing deletes it.
        var forged = new ProjectRemoval.Plan(
                List.of(item(Kind.VOLUME, "my-precious-data", true), item(Kind.CONTAINER, "c1", false)));

        removal.remove(project(), false, forged);

        assertThat(deleted).containsExactly("c1");
    }

    @Test
    void aPortThatThrowsNeitherStopsTheOthersNorEndsInAStackTrace() throws Exception {
        var docker = new Place(item(Kind.CONTAINER, "c1", false));
        RemovalPort broken = new RemovalPort() {
            public List<Removable> find(ProjectSpec project, boolean cloned) {
                return List.of(item(Kind.FOLDER, "/clone", true));
            }

            public Optional<String> delete(Removable r) {
                throw new java.io.UncheckedIOException(new java.nio.file.AccessDeniedException("/clone/postgres14"));
            }
        };
        RemovalPort unreachable = new RemovalPort() {
            public List<Removable> find(ProjectSpec project, boolean cloned) {
                throw new IllegalStateException("Docker went away");
            }

            public Optional<String> delete(Removable r) {
                return Optional.empty();
            }
        };
        var removal = new ProjectRemoval(List.of(docker, broken, unreachable));

        var report = removal.remove(project(), true, removal.plan(project(), true));

        assertThat(deleted).containsExactly("c1");
        assertThat(report.failed())
                .singleElement()
                .asString()
                .startsWith("FOLDER /clone: ")
                .contains("postgres14");
    }

    @Test
    void whatCouldntGoIsReportedAndStillFound() throws Exception {
        var docker = new Place(item(Kind.CONTAINER, "c1", false), item(Kind.IMAGE, "shop-api", false));
        docker.stuck.add("shop-api");
        var removal = new ProjectRemoval(List.of(docker));

        var report = removal.remove(project(), false, removal.plan(project(), false));

        assertThat(report.clean()).isFalse();
        assertThat(report.failed()).containsExactly("IMAGE shop-api: in use");
        assertThat(report.leftovers()).extracting(Removable::id).containsExactly("shop-api");
    }
}
