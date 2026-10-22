package io.github.louch.devdesk.core.changes;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * For a code change: which commits since the last clean start touch the failing services' folders (those first), and
 * the exact git bisect commands to find the bad one. DevDesk never checks out or runs old code itself.
 */
public final class Bisect {

    /** One commit, as {@code git log --name-only} lists it. Files are relative to the repository root. */
    public record Commit(String id, String subject, List<String> files) {
        public Commit {
            files = List.copyOf(files);
        }
    }

    /**
     * @param touching how many of its files are inside a failing service's folder; 0 = elsewhere
     */
    public record Ranked(Commit commit, int touching) {}

    /** The plan: ranked commits, and the commands to paste. */
    public record Plan(List<Ranked> commits, List<String> commands) {
        public Plan {
            commits = List.copyOf(commits);
            commands = List.copyOf(commands);
        }
    }

    private Bisect() {}

    /**
     * @param commits newest first, from the last good commit (excluded) to HEAD
     * @param folders the failing services' folders relative to the repository root ("" = the root itself)
     * @param good the commit checked out when the project last started cleanly
     */
    public static Plan plan(List<Commit> commits, List<String> folders, String good) {
        List<Ranked> ranked = new ArrayList<>();
        for (Commit c : commits) {
            int touching = (int) c.files().stream()
                    .filter(f -> folders.stream().anyMatch(d -> inside(f, d)))
                    .count();
            ranked.add(new Ranked(c, touching));
        }
        // Stable: equal counts stay newest first.
        ranked.sort(Comparator.comparingInt(Ranked::touching).reversed());
        List<String> commands = commits.isEmpty()
                ? List.of()
                : List.of(
                        "git bisect start",
                        "git bisect bad HEAD",
                        "git bisect good " + good,
                        "# start the project at each step git checks out, then: git bisect good  (or: git bisect bad)",
                        "git bisect reset");
        return new Plan(ranked, commands);
    }

    private static boolean inside(String file, String folder) {
        String d = folder.replaceAll("/+$", "");
        return d.isEmpty() || d.equals(".") || file.equals(d) || file.startsWith(d + "/");
    }
}
