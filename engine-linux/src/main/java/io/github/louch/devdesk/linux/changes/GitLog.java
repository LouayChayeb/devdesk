package io.github.louch.devdesk.linux.changes;

import io.github.louch.devdesk.core.changes.Bisect;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/** Asks git which commits came after the last clean start, with the files each one touched. Only reads. */
public final class GitLog {

    /** Separates commits in git's output: a byte that never appears in a subject or a path. */
    private static final char RECORD = '\u001e';

    private GitLog() {}

    /**
     * Commits in {@code good..HEAD}, newest first. Paths are relative to {@code root} ({@code --relative}), and commits
     * that only touch files outside it are left out. Empty when git is missing, {@code root} isn't a repository, or
     * {@code good} is unknown (e.g. after a rebase).
     */
    public static Optional<List<Bisect.Commit>> since(Path root, String good) {
        if (!good.matches("[0-9a-fA-F]{7,64}")) {
            return Optional.empty(); // only a commit id goes on git's command line
        }
        ProcessBuilder pb = new ProcessBuilder(
                        "git",
                        "-C",
                        root.toString(),
                        "log",
                        "--relative",
                        "--name-only",
                        "--format=" + RECORD + "%H%x09%s",
                        good + "..HEAD")
                .redirectErrorStream(false)
                .redirectError(ProcessBuilder.Redirect.DISCARD);
        try {
            Process git = pb.start();
            byte[] out = git.getInputStream().readAllBytes();
            // A plain child (not a service): git log on a local repository finishes in well under 10 s.
            if (!git.waitFor(10, TimeUnit.SECONDS) || git.exitValue() != 0) {
                git.destroyForcibly();
                return Optional.empty();
            }
            return Optional.of(parse(new String(out, StandardCharsets.UTF_8)));
        } catch (IOException e) {
            return Optional.empty(); // git isn't installed
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    static List<Bisect.Commit> parse(String out) {
        List<Bisect.Commit> commits = new ArrayList<>();
        for (String block : out.split(String.valueOf(RECORD))) {
            List<String> lines = block.lines().filter(l -> !l.isBlank()).toList();
            if (lines.isEmpty()) {
                continue;
            }
            String head = lines.getFirst();
            int tab = head.indexOf('\t');
            commits.add(new Bisect.Commit(
                    tab < 0 ? head : head.substring(0, tab),
                    tab < 0 ? "" : head.substring(tab + 1),
                    lines.subList(1, lines.size())));
        }
        return commits;
    }
}
