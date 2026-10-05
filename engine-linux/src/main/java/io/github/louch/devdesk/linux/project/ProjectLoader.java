package io.github.louch.devdesk.linux.project;

import io.github.louch.devdesk.core.config.ConfigException;
import io.github.louch.devdesk.core.config.ConfigParser;
import io.github.louch.devdesk.core.config.ConfigProblem;
import io.github.louch.devdesk.core.project.HealthCheckSpec;
import io.github.louch.devdesk.core.project.ProjectSpec;
import io.github.louch.devdesk.core.project.Workload;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Reads and validates a devdesk.yaml file. Every failure comes back as a plain-words result, never an exception. */
public final class ProjectLoader {

    /** The outcome of loading. */
    public sealed interface Result {
        /** Read and valid. {@code content} is the exact text, for the trust fingerprint. */
        record Loaded(Path file, String content, ProjectSpec project) implements Result {}

        /** The file has mistakes; {@code message} lists them all, one "file:line:col: ..." per line. */
        record Invalid(Path file, String message, List<ConfigProblem> problems) implements Result {}

        /** The file isn't there or can't be read. */
        record Missing(String message) implements Result {}
    }

    private ProjectLoader() {}

    /** Loads {@code file}. If it's a folder, loads the devdesk.yaml inside it. */
    public static Result load(Path file, Map<String, String> env) {
        Path path = file.toAbsolutePath().normalize();
        if (Files.isDirectory(path)) {
            path = path.resolve(ConfigParser.FILE_NAME);
        }
        if (!Files.isRegularFile(path)) {
            return new Result.Missing("no " + path.getFileName() + " in " + path.getParent()
                    + ". Run DevDesk in a project folder, or point to the file with -f path/to/devdesk.yaml");
        }
        Path root = path.getParent();
        try {
            String content = Files.readString(path);
            ProjectSpec project = ConfigParser.parse(content, root, env, name -> read(root.resolve(name)));
            return new Result.Loaded(path, content, project);
        } catch (ConfigException e) {
            return new Result.Invalid(path, e.format(path.getFileName().toString()), e.problems());
        } catch (IOException e) {
            return new Result.Missing("could not read " + path + ": " + e.getMessage());
        }
    }

    /** Every command and image this project would run, one line each, for the trust prompt: nothing runs unseen. */
    public static List<String> commands(ProjectSpec project) {
        List<String> lines = new ArrayList<>();
        project.services().values().forEach(s -> {
            String where = s.workload() instanceof Workload.Command
                    ? "   (in "
                            + (s.directory().equals(project.root())
                                    ? "."
                                    : project.root().relativize(s.directory())) + ")"
                    : "";
            lines.add(s.name() + ":  " + s.workload().describe() + where);
            if (s.health() instanceof HealthCheckSpec.Command c) {
                lines.add(s.name() + " health check:  " + c.command());
            }
        });
        return lines;
    }

    private static Optional<String> read(Path file) {
        try {
            return Files.isRegularFile(file) ? Optional.of(Files.readString(file)) : Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        }
    }
}
