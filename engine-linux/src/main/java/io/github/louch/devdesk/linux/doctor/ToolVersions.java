package io.github.louch.devdesk.linux.doctor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Asks installed tools for their version ({@code node --version}, {@code java -version}…) for the doctor. Searches the
 * same PATH the services get, so "found" here means the services will find it too.
 */
public final class ToolVersions {

    /** The program and arguments per tool. Java prints its version on stderr, so both streams are read. */
    private static final Map<String, List<List<String>>> COMMANDS = Map.of(
            "node", List.of(List.of("node", "--version")),
            "python", List.of(List.of("python3", "--version"), List.of("python", "--version")),
            "java", List.of(List.of("java", "-version")),
            "pnpm", List.of(List.of("pnpm", "--version")),
            "yarn", List.of(List.of("yarn", "--version")),
            "mvn", List.of(List.of("mvn", "--version")),
            "gradle", List.of(List.of("gradle", "--version", "--quiet")),
            "go", List.of(List.of("go", "version")),
            "dotnet", List.of(List.of("dotnet", "--version")));

    private final Map<String, String> environment;

    /** @param environment the variables services start with (its PATH decides what is "installed") */
    public ToolVersions(Map<String, String> environment) {
        this.environment = environment;
    }

    /** The tool's version output, or empty when it isn't on the PATH or doesn't answer within 15 s. */
    public Optional<String> of(String tool) {
        for (List<String> command : COMMANDS.getOrDefault(tool, List.of(List.of(tool, "--version")))) {
            Optional<Path> program = find(command.getFirst());
            if (program.isEmpty()) {
                continue;
            }
            Optional<String> out = run(program.get(), command.subList(1, command.size()));
            if (out.isPresent()) {
                return out;
            }
        }
        return Optional.empty();
    }

    private Optional<Path> find(String name) {
        for (String dir : environment.getOrDefault("PATH", "/usr/bin:/bin").split(":")) {
            Path p = Path.of(dir.isEmpty() ? "." : dir, name);
            if (Files.isRegularFile(p) && Files.isExecutable(p)) {
                return Optional.of(p);
            }
        }
        return Optional.empty();
    }

    private Optional<String> run(Path program, List<String> args) {
        List<String> command = new ArrayList<>();
        command.add(program.toString());
        command.addAll(args);
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().clear();
        builder.environment().putAll(environment);
        try {
            Process p = builder.start();
            p.getOutputStream().close(); // no input: a tool waiting for some would hang until the timeout
            if (!p.waitFor(15, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return Optional.empty();
            }
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            return p.exitValue() == 0 && !out.isEmpty() ? Optional.of(firstUsefulLine(out)) : Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    /** The first line with a digit: banners like Gradle's "-----" or Maven's colours come first sometimes. */
    private static String firstUsefulLine(String out) {
        return out.lines()
                .filter(l -> l.matches(".*\\d.*"))
                .findFirst()
                .orElse(out.lines().findFirst().orElse(out));
    }
}
