package io.github.louch.devdesk.cli;

import io.github.louch.devdesk.core.diagnosis.DiagnosisContext;
import io.github.louch.devdesk.core.diagnosis.DiagnosisEngine;
import io.github.louch.devdesk.core.diagnosis.Finding;
import io.github.louch.devdesk.core.diagnosis.Introspection;
import io.github.louch.devdesk.core.project.Workload;
import io.github.louch.devdesk.docker.DockerConnection;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.function.Supplier;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code devdesk why}: checks a project before starting it (taken ports, missing programs, unset settings, Docker), and
 * says what would go wrong and how to fix it. Runs nothing from devdesk.yaml, so it needs no trust.
 */
@Command(name = "why", description = "Check the project for problems before starting it, and explain how to fix them.")
final class WhyCommand implements Callable<Integer> {

    @Option(
            names = {"-f", "--file"},
            defaultValue = "devdesk.yaml",
            description = "The project file.")
    Path file;

    @Parameters(arity = "0..1", paramLabel = "SERVICE", description = "Only check this service.")
    Optional<String> service;

    private final Map<String, String> env;
    private final Introspection system;
    private final DiagnosisEngine diagnosis;
    private final Supplier<DockerConnection.Result> docker;
    private final PrintStream out;
    private final PrintStream err;

    WhyCommand(
            Map<String, String> env,
            Introspection system,
            DiagnosisEngine diagnosis,
            Supplier<DockerConnection.Result> docker,
            PrintStream out,
            PrintStream err) {
        this.env = env;
        this.system = system;
        this.diagnosis = diagnosis;
        this.docker = docker;
        this.out = out;
        this.err = err;
    }

    @Override
    public Integer call() {
        Optional<ProjectFile> loaded = ProjectFile.load(file, env, err);
        if (loaded.isEmpty()) {
            return ProjectFile.INVALID;
        }
        var project = loaded.get().project();
        if (service.isPresent() && !project.services().containsKey(service.get())) {
            err.println("devdesk: no service \"" + service.get() + "\" in " + file + ". Services: "
                    + String.join(", ", project.services().keySet()));
            return ProjectFile.INVALID;
        }
        out.println("Checking " + project.name() + " (nothing is started)...");
        var ctx = new DiagnosisContext(project, Map.of(), Map.of(), env, system, java.time.Instant.now());
        List<Finding> findings = new ArrayList<>(diagnosis.preflight(ctx));
        dockerFinding(project).ifPresent(findings::add);
        service.ifPresent(name -> findings.removeIf(f -> !f.service().equals(name)));

        if (findings.isEmpty()) {
            out.println(
                    "✓ No problems found: ports are free, programs are installed, settings from .env.example are set.");
            return 0;
        }
        String current = "";
        for (Finding f : findings) {
            if (!f.service().equals(current)) {
                current = f.service();
                out.println(current + ":");
            }
            FindingPrinter.print(out, f);
        }
        long errors = findings.stream()
                .filter(f -> f.severity() == Finding.Severity.ERROR)
                .count();
        out.println(
                errors > 0
                        ? errors + " problem(s) would stop the project from starting."
                        : "Only warnings: it should start.");
        return errors > 0 ? 1 : 0;
    }

    /** Containers need Docker; checked here because the Docker connection lives outside core. */
    private Optional<Finding> dockerFinding(io.github.louch.devdesk.core.project.ProjectSpec project) {
        List<String> containers = project.services().values().stream()
                .filter(s -> s.workload() instanceof Workload.Container)
                .map(s -> s.name())
                .toList();
        if (containers.isEmpty()) {
            return Optional.empty();
        }
        if (docker.get() instanceof DockerConnection.Result.Unavailable u) {
            return Optional.of(new Finding(
                    "docker-unavailable",
                    Finding.Severity.ERROR,
                    containers.getFirst(),
                    "Docker is not available",
                    String.join(", ", containers) + " run in Docker, but " + u.reason(),
                    List.of(),
                    List.of("fix Docker first, then run devdesk why again")));
        }
        return Optional.empty();
    }
}
