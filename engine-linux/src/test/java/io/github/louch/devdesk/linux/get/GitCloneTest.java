package io.github.louch.devdesk.linux.get;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.louch.devdesk.core.get.GitLink;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Clones a git repository made on disk for the test: no network. */
class GitCloneTest {

    @TempDir
    Path tmp;

    private Path repository() throws Exception {
        Path repo = tmp.resolve("origin/shop");
        Files.createDirectories(repo);
        Files.writeString(repo.resolve("README.md"), "shop\n");
        git(repo, "init", "-q", "-b", "main");
        git(repo, "add", ".");
        git(repo, "-c", "user.name=t", "-c", "user.email=t@localhost", "commit", "-q", "-m", "first");
        git(repo, "branch", "dev");
        return repo;
    }

    private static void git(Path dir, String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of("git", "-C", dir.toString()));
        cmd.addAll(List.of(args));
        assertThat(new ProcessBuilder(cmd).inheritIO().start().waitFor()).isZero();
    }

    private static GitLink link(String text) {
        return ((GitLink.Parsed.Ok) GitLink.parse(text)).link();
    }

    @Test
    void aProjectIsClonedNextToNothingItWouldOverwriteAndRecorded() throws Exception {
        Path repo = repository();
        var clone = new GitClone(System.getenv(), tmp.resolve("state/clones.txt"), Clock.systemUTC());
        List<String> lines = new ArrayList<>();

        var first = clone.clone(link("file://" + repo + "#dev"), tmp.resolve("projects"), lines::add);
        var second = clone.clone(link("file://" + repo), tmp.resolve("projects"), lines::add);

        Path a = ((GitClone.Result.Cloned) first).folder();
        Path b = ((GitClone.Result.Cloned) second).folder();
        assertThat(a).isEqualTo(tmp.resolve("projects/shop"));
        assertThat(b).isEqualTo(tmp.resolve("projects/shop-2")); // the first one is never overwritten
        assertThat(a.resolve("README.md")).hasContent("shop");
        assertThat(lines.getFirst()).startsWith("$ git clone --depth 1 --branch dev file://");
        assertThat(clone.cloned(a)).isTrue();
        assertThat(clone.cloned(repo)).isFalse(); // a folder DevDesk didn't make

        clone.forget(a);

        assertThat(clone.cloned(a)).isFalse();
        assertThat(clone.cloned(b)).isTrue();
    }

    @Test
    void aMissingProjectOrBranchIsSaidPlainlyAndLeavesNoFolder() throws Exception {
        Path repo = repository();
        var clone = new GitClone(System.getenv(), tmp.resolve("state/clones.txt"), Clock.systemUTC());

        var noRepo = clone.clone(link("file://" + tmp.resolve("nothing/here")), tmp.resolve("projects"), l -> {});
        var noBranch = clone.clone(link("file://" + repo + "#nope"), tmp.resolve("projects"), l -> {});

        assertThat(((GitClone.Result.Failed) noRepo).why()).contains("doesn't exist, or it's private");
        assertThat(((GitClone.Result.Failed) noBranch).why()).contains("no branch or tag \"nope\"");
        assertThat(tmp.resolve("projects/here")).doesNotExist();
        assertThat(tmp.resolve("projects/shop")).doesNotExist();
    }

    @Test
    void withoutGitTheInstallCommandIsShown() throws Exception {
        var clone = new GitClone(Map.of("PATH", tmp.toString()), tmp.resolve("clones.txt"), Clock.systemUTC());

        var r = clone.clone(link("https://github.com/owner/shop"), tmp, l -> {});

        assertThat(((GitClone.Result.Failed) r).why()).contains("sudo apt install git");
    }
}
