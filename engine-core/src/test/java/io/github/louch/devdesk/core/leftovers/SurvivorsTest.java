package io.github.louch.devdesk.core.leftovers;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.louch.devdesk.core.project.ProjectSpec;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SurvivorsTest {

    private static final ProjectSpec PROJECT = new ProjectSpec("shop", Path.of("/work/shop"), Map.of(), Map.of());

    private static Leftover leftover(String service, Leftover.Kind kind, String id) {
        return new Leftover(service, kind, id, Set.of(), service, Optional.empty());
    }

    /** A finder that remembers what it was asked to remove, and answers with a fixed result. */
    private static final class Finder implements LeftoverFinder {
        final List<Leftover> found;
        final String result;
        final List<Leftover> removed = new ArrayList<>();

        Finder(String result, Leftover... found) {
            this.found = List.of(found);
            this.result = result;
        }

        @Override
        public List<Leftover> find(ProjectSpec project) {
            assertThat(project).isSameAs(PROJECT);
            return found;
        }

        @Override
        public String remove(Leftover leftover) {
            removed.add(leftover);
            return result;
        }
    }

    @Test
    void findAsksEveryFinderAndRemovesNothing() {
        var processes = new Finder("stopped", leftover("api", Leftover.Kind.PROCESS, "devdesk-shop-api.scope"));
        var containers = new Finder("removed", leftover("db", Leftover.Kind.CONTAINER, "c0ffee"));

        var found = new Survivors(List.of(processes, containers)).find(PROJECT);

        assertThat(found).extracting(Leftover::id).containsExactly("devdesk-shop-api.scope", "c0ffee");
        assertThat(processes.removed).isEmpty();
        assertThat(containers.removed).isEmpty();
    }

    @Test
    void cleanUpRemovesEachThroughTheFinderThatFoundItAndReportsItsResult() {
        var api = leftover("api", Leftover.Kind.PROCESS, "devdesk-shop-api.scope");
        var db = leftover("db", Leftover.Kind.CONTAINER, "c0ffee");
        var processes = new Finder("stopped", api);
        var containers = new Finder("could not remove: permission denied", db);

        var reports = new Survivors(List.of(processes, containers)).cleanUp(PROJECT);

        assertThat(reports)
                .containsExactly(
                        new Survivors.Report(api, "stopped"),
                        new Survivors.Report(db, "could not remove: permission denied"));
        assertThat(processes.removed).containsExactly(api);
        assertThat(containers.removed).containsExactly(db);
    }

    @Test
    void nothingLeftBehindMeansNoReports() {
        assertThat(new Survivors(List.of(new Finder("stopped"))).cleanUp(PROJECT))
                .isEmpty();
        assertThat(new Survivors(List.of()).find(PROJECT)).isEmpty();
    }
}
