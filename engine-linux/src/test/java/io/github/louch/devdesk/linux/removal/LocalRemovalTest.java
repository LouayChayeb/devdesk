package io.github.louch.devdesk.linux.removal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.louch.devdesk.core.config.ConfigParser;
import io.github.louch.devdesk.core.leftovers.Leftover;
import io.github.louch.devdesk.core.leftovers.LeftoverFinder;
import io.github.louch.devdesk.core.project.ProjectSpec;
import io.github.louch.devdesk.core.removal.ProjectRemoval;
import io.github.louch.devdesk.core.removal.Removable;
import io.github.louch.devdesk.linux.get.GitClone;
import io.github.louch.devdesk.linux.trust.TrustStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalRemovalTest {

    @TempDir
    Path tmp;

    private static final LeftoverFinder NOTHING_RUNS = new LeftoverFinder() {
        public List<Leftover> find(ProjectSpec project) {
            return List.of();
        }

        public String remove(Leftover leftover) {
            return "stopped";
        }
    };

    private ProjectSpec project(Path root) throws Exception {
        Files.createDirectories(root);
        Files.writeString(root.resolve("devdesk.yaml"), "version: 1\nservices:\n  api: { command: x }\n");
        return ConfigParser.parse(
                Files.readString(root.resolve("devdesk.yaml")), root, Map.of(), f -> Optional.empty());
    }

    private void file(Path p) throws Exception {
        Files.createDirectories(p.getParent());
        Files.writeString(p, "x");
    }

    @Test
    void itsStateAndTrustGoButNothingOfAnotherProject() throws Exception {
        Path state = tmp.resolve("state");
        var shop = project(tmp.resolve("code/shop"));
        var other = project(tmp.resolve("code/other"));
        file(state.resolve(shop.key() + ".lock"));
        file(state.resolve("logs/" + shop.key() + "/2026-10-27.jsonl"));
        file(state.resolve("known-good/" + shop.key() + ".json"));
        file(state.resolve("logs/" + other.key() + "/2026-10-27.jsonl"));
        file(state.resolve("known-good/" + other.key() + ".json"));
        var trust = new TrustStore(state.resolve("trust.txt"));
        trust.trust(shop.root().resolve("devdesk.yaml"), "a".repeat(64));
        trust.trust(other.root().resolve("devdesk.yaml"), "b".repeat(64));
        var clones = new GitClone(Map.of(), state.resolve("clones.txt"), Clock.systemUTC());
        var removal = new ProjectRemoval(List.of(new LocalRemoval(NOTHING_RUNS, state, trust, clones)));

        var plan = removal.plan(shop, true);
        var report = removal.remove(shop, true, plan);

        assertThat(plan.items()).hasSize(4).noneMatch(r -> r.kind() == Removable.Kind.FOLDER);
        assertThat(report.clean()).isTrue();
        assertThat(state.resolve("logs/" + shop.key())).doesNotExist();
        assertThat(state.resolve("logs/" + other.key() + "/2026-10-27.jsonl")).exists();
        assertThat(state.resolve("known-good/" + other.key() + ".json")).exists();
        assertThat(trust.knows(other.root().resolve("devdesk.yaml"))).isTrue();
        // An opened folder stays, even when "cloned" is claimed: only the clone record makes it DevDesk's.
        assertThat(shop.root()).exists();
    }

    /** A clone made for the test: its devdesk.yaml in {@code sub} ("" = the top), like Twenty's packages/twenty-docker. */
    private Path cloneWith(String sub, Path state) throws Exception {
        Path origin = tmp.resolve("origin/repo");
        Files.createDirectories(origin.resolve(sub));
        Files.writeString(
                origin.resolve(sub).resolve("devdesk.yaml"), "version: 1\nservices:\n  api: { command: x }\n");
        for (var args : List.of(
                List.of("init", "-q"),
                List.of("add", "."),
                List.of("-c", "user.name=t", "-c", "user.email=t@l", "commit", "-q", "-m", "x"))) {
            var cmd = new java.util.ArrayList<>(List.of("git", "-C", origin.toString()));
            cmd.addAll(args);
            assertThat(new ProcessBuilder(cmd).start().waitFor()).isZero();
        }
        var clones = new GitClone(System.getenv(), state.resolve("clones.txt"), Clock.systemUTC());
        var link = ((io.github.louch.devdesk.core.get.GitLink.Parsed.Ok)
                        io.github.louch.devdesk.core.get.GitLink.parse("file://" + origin))
                .link();
        return ((GitClone.Result.Cloned) clones.clone(link, tmp.resolve("DevDesk"), l -> {})).folder();
    }

    @Test
    void aProjectInASubfolderOfAClonedRepositoryTakesTheWholeCloneWithIt() throws Exception {
        Path state = tmp.resolve("state");
        Path clone = cloneWith("packages/docker", state);
        Path root = clone.resolve("packages/docker");
        var project = ConfigParser.parse(
                Files.readString(root.resolve("devdesk.yaml")), root, Map.of(), f -> Optional.empty());
        var clones = new GitClone(System.getenv(), state.resolve("clones.txt"), Clock.systemUTC());
        var removal = new ProjectRemoval(
                List.of(new LocalRemoval(NOTHING_RUNS, state, new TrustStore(state.resolve("trust.txt")), clones)));

        var plan = removal.plan(project, clones.cloned(root));
        var report = removal.remove(project, true, plan);

        assertThat(plan.items()).extracting(Removable::id).contains(clone.toString());
        assertThat(report.clean()).isTrue();
        assertThat(clone).doesNotExist();
    }

    @Test
    void aRepositoryTheUserClonedIsNeverDeletedEvenFromASubfolder() throws Exception {
        Path state = tmp.resolve("state");
        // The user's own clone: a git repository DevDesk has no record of.
        Path repo = tmp.resolve("code/theirs");
        Files.createDirectories(repo.resolve(".git"));
        var project = project(repo.resolve("packages/docker"));
        var clones = new GitClone(Map.of(), state.resolve("clones.txt"), Clock.systemUTC());
        var removal = new ProjectRemoval(
                List.of(new LocalRemoval(NOTHING_RUNS, state, new TrustStore(state.resolve("trust.txt")), clones)));

        // Even if a caller wrongly claims "cloned", only DevDesk's own record makes a folder its own.
        var report = removal.remove(project, true, removal.plan(project, true));

        assertThat(report.removed()).noneMatch(r -> r.kind() == Removable.Kind.FOLDER);
        assertThat(repo.resolve("packages/docker/devdesk.yaml")).exists();
    }

    @Test
    void aCloneThatHoldsAnotherKnownProjectStaysAndThePlanSaysWhy() throws Exception {
        Path state = tmp.resolve("state");
        Path clone = cloneWith("api", state);
        Path admin = clone.resolve("admin");
        Files.createDirectories(admin);
        Files.writeString(admin.resolve("devdesk.yaml"), "version: 1\nservices:\n  admin: { command: x }\n");
        var trust = new TrustStore(state.resolve("trust.txt"));
        trust.trust(admin.resolve("devdesk.yaml"), "a".repeat(64)); // DevDesk knows admin: it was allowed to run it
        Path root = clone.resolve("api");
        var project = ConfigParser.parse(
                Files.readString(root.resolve("devdesk.yaml")), root, Map.of(), f -> Optional.empty());
        var clones = new GitClone(System.getenv(), state.resolve("clones.txt"), Clock.systemUTC());
        var removal = new ProjectRemoval(List.of(new LocalRemoval(NOTHING_RUNS, state, trust, clones)));

        var plan = removal.plan(project, true);
        removal.remove(project, true, plan);

        assertThat(plan.items()).noneMatch(r -> r.kind() == Removable.Kind.FOLDER);
        assertThat(plan.kept())
                .singleElement()
                .asString()
                .contains("also holds another project (" + admin + ")")
                .contains("so it stays");
        assertThat(admin.resolve("devdesk.yaml")).exists();
        assertThat(clone).exists();
    }

    @Test
    void filesAContainerWroteAsAnotherUserAreHandedToTheHelperThenSaidPlainly() throws Exception {
        Path state = tmp.resolve("state");
        Path clone = cloneWith("", state);
        // Like postgres14/ in Mastodon's clone: a folder this user may not empty.
        Path data = clone.resolve("postgres14");
        Files.createDirectories(data);
        Files.writeString(data.resolve("PG_VERSION"), "14");
        Files.setPosixFilePermissions(data, java.nio.file.attribute.PosixFilePermissions.fromString("r-xr-xr-x"));
        var project = ConfigParser.parse(
                Files.readString(clone.resolve("devdesk.yaml")), clone, Map.of(), f -> Optional.empty());
        var clones = new GitClone(System.getenv(), state.resolve("clones.txt"), Clock.systemUTC());
        var trust = new TrustStore(state.resolve("trust.txt"));
        List<Path> helped = new java.util.ArrayList<>();
        LocalRemoval.RootFiles helper = folder -> {
            helped.add(folder);
            // What root can do in the real helper container: open the folder up and empty it.
            try {
                Files.setPosixFilePermissions(
                        data, java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
            return Optional.empty();
        };
        var withHelper =
                new ProjectRemoval(List.of(new LocalRemoval(NOTHING_RUNS, state, trust, clones, Optional.of(helper))));

        var report = withHelper.remove(project, true, withHelper.plan(project, true));

        assertThat(helped).containsExactly(clone);
        assertThat(report.clean()).isTrue();
        assertThat(clone).doesNotExist();
    }

    @Test
    void withoutAHelperWhatsLeftIsSaidWithTheCommandNotAStackTrace() throws Exception {
        Path state = tmp.resolve("state");
        Path clone = cloneWith("", state);
        Path data = clone.resolve("postgres14");
        Files.createDirectories(data);
        Files.writeString(data.resolve("PG_VERSION"), "14");
        Files.setPosixFilePermissions(data, java.nio.file.attribute.PosixFilePermissions.fromString("r-xr-xr-x"));
        var project = ConfigParser.parse(
                Files.readString(clone.resolve("devdesk.yaml")), clone, Map.of(), f -> Optional.empty());
        var clones = new GitClone(System.getenv(), state.resolve("clones.txt"), Clock.systemUTC());
        var removal = new ProjectRemoval(
                List.of(new LocalRemoval(NOTHING_RUNS, state, new TrustStore(state.resolve("trust.txt")), clones)));
        try {
            var report = removal.remove(project, true, removal.plan(project, true));

            assertThat(report.clean()).isFalse();
            assertThat(report.failed())
                    .singleElement()
                    .asString()
                    .contains("belong to another user")
                    .contains("sudo rm -rf " + clone);
        } finally {
            Files.setPosixFilePermissions(data, java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));
        }
    }

    @Test
    void aFolderDevDeskClonedGoesWithItsRecord() throws Exception {
        Path state = tmp.resolve("state");
        Path origin = tmp.resolve("origin/shop");
        Files.createDirectories(origin);
        Files.writeString(origin.resolve("devdesk.yaml"), "version: 1\nservices:\n  api: { command: x }\n");
        for (var args : List.of(
                List.of("init", "-q"),
                List.of("add", "."),
                List.of("-c", "user.name=t", "-c", "user.email=t@l", "commit", "-q", "-m", "x"))) {
            var cmd = new java.util.ArrayList<>(List.of("git", "-C", origin.toString()));
            cmd.addAll(args);
            assertThat(new ProcessBuilder(cmd).start().waitFor()).isZero();
        }
        var clones = new GitClone(System.getenv(), state.resolve("clones.txt"), Clock.systemUTC());
        var link = ((io.github.louch.devdesk.core.get.GitLink.Parsed.Ok)
                        io.github.louch.devdesk.core.get.GitLink.parse("file://" + origin))
                .link();
        Path cloned = ((GitClone.Result.Cloned) clones.clone(link, tmp.resolve("DevDesk"), l -> {})).folder();
        var shop = ConfigParser.parse(
                Files.readString(cloned.resolve("devdesk.yaml")), cloned, Map.of(), f -> Optional.empty());
        var removal = new ProjectRemoval(
                List.of(new LocalRemoval(NOTHING_RUNS, state, new TrustStore(state.resolve("trust.txt")), clones)));

        var plan = removal.plan(shop, true);
        var report = removal.remove(shop, true, plan);

        assertThat(plan.losesData()).isTrue();
        assertThat(report.clean()).isTrue();
        assertThat(cloned).doesNotExist();
        assertThat(clones.cloned(cloned)).isFalse();
        assertThat(origin).exists();
    }
}
