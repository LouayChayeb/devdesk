package io.github.louch.devdesk.cli;

import io.github.louch.devdesk.core.diagnosis.DiagnosisContext;
import io.github.louch.devdesk.core.diagnosis.DiagnosisEngine;
import io.github.louch.devdesk.core.diagnosis.Finding;
import io.github.louch.devdesk.core.diagnosis.Introspection;
import io.github.louch.devdesk.core.doctor.Doctor;
import io.github.louch.devdesk.docker.DockerConnection;
import io.github.louch.devdesk.linux.doctor.MachineFacts;
import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.function.Supplier;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * {@code devdesk doctor}: can this machine run the project? The right Node/Java/Python versions, Docker, images,
 * installed packages, free ports, settings and disk space, each problem with a fix. Runs nothing from devdesk.yaml.
 */
@Command(
        name = "doctor",
        description = "Check that this machine can run the project: tool versions, Docker, packages, ports, disk.")
final class DoctorCommand implements Callable<Integer> {

    @Option(
            names = {"-f", "--file"},
            defaultValue = "devdesk.yaml",
            description = "The project file.")
    Path file;

    private final Map<String, String> env;
    private final Introspection system;
    private final DiagnosisEngine diagnosis;
    private final Supplier<DockerConnection.Result> docker;
    private final PrintStream out;
    private final PrintStream err;

    DoctorCommand(
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
        out.println("Checking this machine for " + project.name() + " (nothing is started)...");
        var connection = docker.get();
        var facts = MachineFacts.gather(
                project.root(),
                env,
                () -> connection instanceof DockerConnection.Result.Unavailable u
                        ? Optional.of(u.reason())
                        : Optional.empty(),
                () -> connection instanceof DockerConnection.Result.Connected c
                        ? Optional.of(c.runtime().localImages())
                        : Optional.empty());
        var ctx = new DiagnosisContext(project, Map.of(), Map.of(), env, system, Instant.now());
        Doctor.Report report = Doctor.check(project, facts, diagnosis.preflight(ctx));

        report.passed().forEach(p -> out.println("  ✓ " + p));
        report.problems().forEach(f -> FindingPrinter.print(out, f.service().isEmpty() ? f : withService(f)));
        long errors = report.problems().stream()
                .filter(f -> f.severity() == Finding.Severity.ERROR)
                .count();
        out.println(
                errors > 0
                        ? errors + " problem(s) to fix before the project can run."
                        : report.problems().isEmpty()
                                ? "All good: this machine can run the project."
                                : "Only warnings: it should run.");
        return errors > 0 ? 1 : 0;
    }

    /** "api: Node 18 found…": the doctor lists everything in one go, so each line says which service it's about. */
    private static Finding withService(Finding f) {
        return new Finding(
                f.ruleId(),
                f.severity(),
                f.service(),
                f.service() + ": " + f.title(),
                f.explanation(),
                f.evidence(),
                f.actions());
    }
}
