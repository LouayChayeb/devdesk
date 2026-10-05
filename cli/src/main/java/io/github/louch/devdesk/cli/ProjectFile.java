package io.github.louch.devdesk.cli;

import io.github.louch.devdesk.core.project.ProjectSpec;
import io.github.louch.devdesk.linux.project.ProjectLoader;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/** A loaded devdesk.yaml for a CLI command; loading problems are printed instead of thrown. */
record ProjectFile(Path path, String content, ProjectSpec project) {

    /** Exit code for "your devdesk.yaml is missing or invalid". */
    static final int INVALID = 2;

    static Optional<ProjectFile> load(Path file, Map<String, String> env, PrintStream err) {
        return switch (ProjectLoader.load(file, env)) {
            case ProjectLoader.Result.Loaded l -> Optional.of(new ProjectFile(l.file(), l.content(), l.project()));
            case ProjectLoader.Result.Invalid i -> {
                err.println(i.message());
                yield Optional.empty();
            }
            case ProjectLoader.Result.Missing m -> {
                err.println("devdesk: " + m.message());
                yield Optional.empty();
            }
        };
    }

    /** Every command this project would run, for the trust prompt: nothing runs unseen. */
    void printCommands(PrintStream out) {
        ProjectLoader.commands(project).forEach(line -> out.println("  " + line));
    }
}
