package io.github.louch.devdesk.linux.get;

import io.github.louch.devdesk.core.get.GitLink;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Clones a project with git, and remembers that DevDesk made the folder: "Remove" deletes a folder only when this
 * record says DevDesk cloned it, never one the user opened.
 */
public final class GitClone {

    /** Where it landed, or what went wrong in plain words. */
    public sealed interface Result {
        /** Cloned into this new folder. */
        record Cloned(Path folder) implements Result {}

        /** Nothing was cloned, and why. */
        record Failed(String why) implements Result {}
    }

    private final Map<String, String> env;
    private final Path log;
    private final Clock clock;

    /** @param log the record of cloned folders, one per line: path, link, time (tab-separated) */
    public GitClone(Map<String, String> env, Path log, Clock clock) {
        this.env = Map.copyOf(env);
        this.log = log;
        this.clock = clock;
    }

    /** {@code $XDG_STATE_HOME/devdesk/clones.txt}, next to DevDesk's other state. */
    public static Path defaultLog(Map<String, String> env) {
        String state = env.getOrDefault("XDG_STATE_HOME", "");
        Path base = state.isBlank() ? Path.of(env.getOrDefault("HOME", "/tmp"), ".local", "state") : Path.of(state);
        return base.resolve("devdesk").resolve("clones.txt");
    }

    /** {@code ~/DevDesk}: where projects from a link go unless the user picks a folder. */
    public static Path defaultParent(Map<String, String> env) {
        return Path.of(env.getOrDefault("HOME", "/tmp"), "DevDesk");
    }

    /**
     * Clones into {@code parent/<name>}, or {@code <name>-2}… when that exists: never into a folder that's there.
     * Only the latest commit is downloaded (--depth 1): running a project doesn't need its whole history.
     */
    public Result clone(GitLink link, Path parent, Consumer<String> lines) throws InterruptedException {
        Optional<Path> git = program("git");
        if (git.isEmpty()) {
            return new Result.Failed("git isn't installed, and DevDesk downloads projects with it. Install it"
                    + " (on Ubuntu: sudo apt install git), then try again");
        }
        Path target = parent.resolve(link.name());
        for (int n = 2; Files.exists(target); n++) {
            target = parent.resolve(link.name() + "-" + n);
        }
        try {
            Files.createDirectories(parent);
        } catch (IOException e) {
            return new Result.Failed("can't create " + parent + ": " + e.getMessage());
        }
        List<String> cmd = new ArrayList<>(List.of(git.get().toString(), "clone", "--depth", "1"));
        link.ref().ifPresent(r -> cmd.addAll(List.of("--branch", r)));
        cmd.add("--"); // nothing after this is read as an option, whatever the link says
        cmd.add(link.cloneUrl());
        cmd.add(target.toString());
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        pb.environment().clear();
        pb.environment().putAll(env);
        pb.environment().put("GIT_TERMINAL_PROMPT", "0"); // a private repo fails instead of waiting for a password
        lines.accept("$ git clone --depth 1"
                + link.ref().map(r -> " --branch " + r).orElse("") + " " + link.cloneUrl() + " " + target);
        Process p;
        try {
            p = pb.start();
            p.getOutputStream().close();
        } catch (IOException e) {
            return new Result.Failed("can't start git: " + e.getMessage());
        }
        List<String> last = new ArrayList<>();
        try (BufferedReader in =
                new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            for (String line = in.readLine(); line != null; line = in.readLine()) {
                lines.accept(line); // without a terminal, git prints a few plain lines, no progress bars
                last.add(line);
            }
        } catch (IOException e) {
            // git ended: its exit code says how
        } catch (RuntimeException e) {
            p.destroy();
            throw e;
        }
        int code;
        try {
            code = p.waitFor();
        } catch (InterruptedException e) {
            p.descendants().forEach(ProcessHandle::destroy);
            p.destroy();
            throw e;
        }
        if (code != 0) {
            deleteQuietly(target); // a half-cloned folder is worth nothing
            return new Result.Failed(explain(link, String.join("\n", last)));
        }
        try {
            Files.createDirectories(log.getParent());
            Files.writeString(
                    log,
                    target.toAbsolutePath().normalize() + "\t" + link.cloneUrl() + "\t" + clock.instant() + "\n",
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException e) {
            return new Result.Failed("cloned into " + target + ", but couldn't record it in " + log + ": "
                    + e.getMessage() + ". Remove would then leave the folder; delete it yourself when done");
        }
        return new Result.Cloned(target);
    }

    static String explain(GitLink link, String output) {
        String o = output.toLowerCase();
        if (o.contains("remote branch") && o.contains("not found")) {
            return "there's no branch or tag \"" + link.ref().orElse("") + "\" in " + link.cloneUrl();
        }
        if (o.contains("could not read username")
                || o.contains("authentication")
                || o.contains("not found")
                || o.contains("does not appear to be a git repository")
                || o.contains("could not read from remote repository")) {
            return "git couldn't get " + link.cloneUrl() + ": it doesn't exist, or it's private. Check the link;"
                    + " for a private project, clone it yourself (git knows your login) and open the folder";
        }
        if (o.contains("could not resolve host") || o.contains("unable to access")) {
            return "git couldn't reach " + link.cloneUrl() + ". Check you're online, then try again";
        }
        if (o.contains("no space left")) {
            return "the disk is full: free some space, then try again";
        }
        String lastLine = output.lines().reduce((a, b) -> b).orElse("");
        return "git clone failed: " + lastLine;
    }

    /** Whether DevDesk cloned this folder, or the repository it is part of (and so may delete that). */
    public boolean cloned(Path folder) {
        return cloneOf(folder).isPresent();
    }

    /**
     * The folder DevDesk cloned that holds {@code folder}: the folder itself, or the repository around it (Twenty's
     * compose file is in packages/twenty-docker of the clone).
     */
    public Optional<Path> cloneOf(Path folder) {
        Path wanted = folder.toAbsolutePath().normalize();
        return entries().stream().map(Path::of).filter(wanted::startsWith).findFirst();
    }

    /** Drops the record once the folder is gone. */
    public void forget(Path folder) throws IOException {
        if (!Files.exists(log)) {
            return;
        }
        String wanted = folder.toAbsolutePath().normalize().toString();
        List<String> kept = Files.readAllLines(log, StandardCharsets.UTF_8).stream()
                .filter(l -> !l.split("\t", 2)[0].equals(wanted))
                .toList();
        Files.write(log, kept, StandardCharsets.UTF_8);
    }

    private List<String> entries() {
        try {
            return Files.readAllLines(log, StandardCharsets.UTF_8).stream()
                    .map(l -> l.split("\t", 2)[0])
                    .toList();
        } catch (IOException e) {
            return List.of(); // no record: nothing was cloned
        }
    }

    private Optional<Path> program(String name) {
        for (String dir : env.getOrDefault("PATH", "").split(":")) {
            if (!dir.isEmpty() && Files.isExecutable(Path.of(dir, name))) {
                return Optional.of(Path.of(dir, name));
            }
        }
        return Optional.empty();
    }

    private static void deleteQuietly(Path folder) {
        try (var walk = Files.walk(folder)) {
            walk.sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> p.toFile().delete());
        } catch (IOException e) {
            // nothing there, or already partly gone
        }
    }
}
