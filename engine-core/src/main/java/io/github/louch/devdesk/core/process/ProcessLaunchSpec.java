package io.github.louch.devdesk.core.process;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/**
 * What to start: a shell command line, where to run it, and its complete environment.
 *
 * <p>{@code owner} is the project's key (see {@link io.github.louch.devdesk.core.project.ProjectSpec#key()}) or empty.
 * It goes into the OS-level name of the process family, so DevDesk can later prove which leftovers are its own.
 *
 * <p>The command goes through {@code /bin/sh -c}, so users can write pipes and {@code &&}.
 */
public record ProcessLaunchSpec(
        String owner, String name, String command, Path workingDirectory, Map<String, String> environment) {

    /** A process that belongs to no project (e.g. `devdesk run`, a health check command). */
    public ProcessLaunchSpec(String name, String command, Path workingDirectory, Map<String, String> environment) {
        this("", name, command, workingDirectory, environment);
    }

    public ProcessLaunchSpec {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(workingDirectory, "workingDirectory");
        if (name.isBlank()) {
            throw new IllegalArgumentException("Process name must not be blank");
        }
        if (command.isBlank()) {
            throw new IllegalArgumentException("Command for '" + name + "' is empty: nothing to run");
        }
        // Copy so a caller changing its map later can't change what we run.
        environment = Map.copyOf(environment);
    }
}
