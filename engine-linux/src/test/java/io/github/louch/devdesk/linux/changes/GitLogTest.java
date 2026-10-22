package io.github.louch.devdesk.linux.changes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitLogTest {

    @TempDir
    Path repo;

    private String git(String... args) throws Exception {
        var cmd = new java.util.ArrayList<>(List.of(
                "git",
                "-C",
                repo.toString(),
                "-c",
                "user.name=t",
                "-c",
                "user.email=t@t",
                "-c",
                "commit.gpgsign=false"));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes()).strip();
        assertThat(p.waitFor()).as(out).isZero();
        return out;
    }

    private static boolean gitInstalled() {
        try {
            return new ProcessBuilder("git", "--version").start().waitFor() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    @Test
    void listsTheCommitsAfterTheGoodOneWithTheirFiles() throws Exception {
        assumeTrue(gitInstalled(), "git is not installed");
        git("init", "-q");
        Files.writeString(repo.resolve("a.txt"), "1");
        git("add", ".");
        git("commit", "-qm", "first");
        String good = git("rev-parse", "HEAD");
        Files.createDirectories(repo.resolve("api"));
        Files.writeString(repo.resolve("api/main.ts"), "2");
        git("add", ".");
        git("commit", "-qm", "change api");

        var commits = GitLog.since(repo, good).orElseThrow();

        assertThat(commits).hasSize(1);
        assertThat(commits.getFirst().subject()).isEqualTo("change api");
        assertThat(commits.getFirst().files()).containsExactly("api/main.ts");
    }

    @Test
    void anythingButACommitIdNeverReachesGit() {
        assertThat(GitLog.since(repo, "--output=/tmp/x")).isEmpty();
        assertThat(GitLog.since(repo, "HEAD; rm -rf /")).isEmpty();
    }

    @Test
    void notARepositoryGivesNothing() {
        assertThat(GitLog.since(repo, "abcdef1")).isEmpty();
    }

    @Test
    void parsesGitsOutput() {
        var commits = GitLog.parse("\u001eaaa\tfirst one\n\nx/y.txt\nz.txt\n\u001ebbb\tsecond\n");
        assertThat(commits).extracting(c -> c.id()).containsExactly("aaa", "bbb");
        assertThat(commits.getFirst().files()).containsExactly("x/y.txt", "z.txt");
        assertThat(commits.get(1).files()).isEmpty();
    }
}
