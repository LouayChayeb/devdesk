package io.github.louch.devdesk.cli;

import io.github.louch.devdesk.core.changes.ChangeReport;
import io.github.louch.devdesk.core.changes.ImageProof;
import io.github.louch.devdesk.core.changes.Symptom;
import io.github.louch.devdesk.core.diagnosis.DiagnosisContext;
import io.github.louch.devdesk.core.diagnosis.DiagnosisEngine;
import io.github.louch.devdesk.core.diagnosis.Finding;
import io.github.louch.devdesk.core.diagnosis.Introspection;
import io.github.louch.devdesk.core.health.HealthProbe;
import io.github.louch.devdesk.core.history.HistoryEntry;
import io.github.louch.devdesk.core.project.ProjectSpec;
import io.github.louch.devdesk.core.project.ServiceSpec;
import io.github.louch.devdesk.core.project.Workload;
import io.github.louch.devdesk.core.supervisor.ServiceEnvironment;
import io.github.louch.devdesk.docker.DockerConnection;
import io.github.louch.devdesk.linux.changes.GitLog;
import io.github.louch.devdesk.linux.changes.KnownGoodFiles;
import io.github.louch.devdesk.linux.history.JsonlHistoryStore;
import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code devdesk why}: checks a project before starting it (taken ports, missing programs, unset settings, Docker), and
 * says what would go wrong and how to fix it. Runs nothing from devdesk.yaml, so it needs no trust.
 */
@Command(name = "why", description = "Check the project for problems before starting it, and explain how to fix them.")
final class WhyCommand implements Callable<Integer> {

    /** Both replicas run at once: the whole replay of one image takes at most this long. */
    private static final Duration PROOF_CAP = Duration.ofSeconds(30);

    @Option(
            names = {"-f", "--file"},
            defaultValue = "devdesk.yaml",
            description = "The project file.")
    Path file;

    @Parameters(arity = "0..1", paramLabel = "SERVICE", description = "Only check this service.")
    Optional<String> service;

    @Option(
            names = "--prove",
            description = "Replay changed images (the one from the last clean start and the current one) in throwaway"
                    + " containers, to prove an image change broke it. Up to ~30 s per image.")
    boolean prove;

    private final Map<String, String> env;
    private final Introspection system;
    private final DiagnosisEngine diagnosis;
    private final Supplier<DockerConnection.Result> docker;
    private final KnownGoodFiles knownGood;
    private final HealthProbe probe;
    private final RandomGenerator random;
    private final Clock clock;
    private final PrintStream out;
    private final PrintStream err;

    WhyCommand(
            Map<String, String> env,
            Introspection system,
            DiagnosisEngine diagnosis,
            Supplier<DockerConnection.Result> docker,
            KnownGoodFiles knownGood,
            HealthProbe probe,
            RandomGenerator random,
            Clock clock,
            PrintStream out,
            PrintStream err) {
        this.env = env;
        this.system = system;
        this.diagnosis = diagnosis;
        this.docker = docker;
        this.knownGood = knownGood;
        this.probe = probe;
        this.random = random;
        this.clock = clock;
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
            verdict(project, findings);
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
        verdict(project, findings);
        return errors > 0 ? 1 : 0;
    }

    /**
     * What changed since the last clean start, ranked against what fails: the problems found now, plus the findings
     * of the last run (from the flight recorder), since nothing runs while `why` checks.
     */
    private void verdict(ProjectSpec project, List<Finding> now) {
        List<Symptom> symptoms = new ArrayList<>(now.stream().map(Symptom::of).toList());
        symptoms.addAll(lastRun(project));
        service.ifPresent(name -> symptoms.removeIf(s -> !s.service().equals(name)));
        var report = new ChangeReport(knownGood::load, p -> knownGood.take(p, this::imageIds), GitLog::since);
        Optional<ChangeReport.Report> r = report.build(project, symptoms);
        if (r.isEmpty()) {
            out.println();
            out.println("(DevDesk hasn't seen this project start cleanly yet, so it can't say what changed since.)");
            return;
        }
        Map<String, ImageProof.Result> proofs = new LinkedHashMap<>();
        if (prove) {
            for (ChangeReport.Item item : r.get().items()) {
                if (item.suspect().provable()) {
                    String image = item.suspect().change().subject();
                    out.println("Replaying " + image + " (before and now) in throwaway containers, up to 30 s...");
                    prove(project, report, image, symptoms).ifPresent(p -> proofs.put(image, p));
                }
            }
        }
        VerdictPrinter.print(out, r.get().since(), r.get(), proofs, !prove);
    }

    private Optional<ImageProof.Result> prove(
            ProjectSpec project, ChangeReport report, String image, List<Symptom> symptoms) {
        Optional<String> previous = report.previousImage(project, image);
        Optional<ServiceSpec> svc = project.services().values().stream()
                .filter(s -> s.workload() instanceof Workload.Container c
                        && c.image().equals(image))
                .filter(s -> symptoms.stream().anyMatch(x -> x.service().equals(s.name())))
                .findFirst()
                .or(() -> project.services().values().stream()
                        .filter(s -> s.workload() instanceof Workload.Container c
                                && c.image().equals(image))
                        .findFirst());
        if (previous.isEmpty() || svc.isEmpty()) {
            return Optional.empty();
        }
        if (!(docker.get() instanceof DockerConnection.Result.Connected c)) {
            err.println("devdesk: Docker isn't reachable, so " + image + " can't be replayed");
            return Optional.empty();
        }
        try {
            return Optional.of(new ImageProof(c.runtime().imageReplay(random), probe, clock, Duration.ofSeconds(1))
                    .prove(svc.get(), previous.get(), ServiceEnvironment.values(project, svc.get(), env), PROOF_CAP));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    /** The findings of the last DevDesk session on this project, from the flight recorder (last 7 days). */
    private List<Symptom> lastRun(ProjectSpec project) {
        List<HistoryEntry> entries;
        try {
            entries = JsonlHistoryStore.forProject(env, project)
                    .read(clock.instant().minus(Duration.ofDays(7)));
        } catch (RuntimeException e) {
            return List.of(); // no history: only today's checks count
        }
        int start = 0;
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).kind() == HistoryEntry.Kind.SESSION
                    && "started".equals(entries.get(i).data().get("event"))) {
                start = i;
            }
        }
        List<Symptom> out = new ArrayList<>();
        for (HistoryEntry e : entries.subList(start, entries.size())) {
            if (e.kind() == HistoryEntry.Kind.FINDING && e.data().get("rule") instanceof String rule) {
                out.add(new Symptom(
                        e.service(), rule, e.summary() + " " + e.data().getOrDefault("explanation", "")));
            }
        }
        return out;
    }

    private Map<String, String> imageIds(Collection<String> images) {
        return docker.get() instanceof DockerConnection.Result.Connected c
                ? c.runtime().imageIds(images)
                : Map.of();
    }

    /** Containers need Docker; checked here because the Docker connection lives outside core. */
    private Optional<Finding> dockerFinding(ProjectSpec project) {
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
