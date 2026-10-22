package io.github.louch.devdesk.daemon;

import io.github.louch.devdesk.core.changes.ChangeReport;
import io.github.louch.devdesk.core.changes.ImageProof;
import io.github.louch.devdesk.core.changes.KnownGood;
import io.github.louch.devdesk.core.changes.Suspect;
import io.github.louch.devdesk.core.changes.Symptom;
import io.github.louch.devdesk.core.health.HealthProbe;
import io.github.louch.devdesk.core.project.ProjectSpec;
import io.github.louch.devdesk.core.project.ServiceSpec;
import io.github.louch.devdesk.core.project.Workload;
import io.github.louch.devdesk.core.state.ServiceState;
import io.github.louch.devdesk.core.supervisor.ServiceEnvironment;
import io.github.louch.devdesk.docker.DockerConnection;
import io.github.louch.devdesk.linux.changes.GitLog;
import io.github.louch.devdesk.linux.changes.KnownGoodFiles;
import java.time.Clock;
import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;

/**
 * "It worked yesterday": each time the whole project is up and healthy, saves what it took to get there (see
 * {@link KnownGood}); when something breaks, ranks what changed since against the current findings (the verdict), and
 * can replay an image change to prove it.
 */
final class ChangeTracker {

    private static final Set<String> GOOD = Set.of("healthy", "running", "completed");
    /** Both replicas run at the same time, so this is the whole proof's limit. */
    private static final Duration PROOF_CAP = Duration.ofSeconds(30);

    private final EngineSession session;
    private final Supplier<DockerConnection.Result> docker;
    private final KnownGoodFiles files;
    private final HealthProbe probe;
    private final Map<String, String> environment;
    private final Clock clock;
    private final RandomGenerator random;
    private final ChangeReport report;
    /** "image@current id" → the last proof: shown with the verdict until the image changes again. */
    private final Map<String, ImageProof.Result> proofs = new ConcurrentHashMap<>();
    /** True once this clean start was saved: the next save waits until the project goes down or breaks first. */
    private volatile boolean savedThisRun;

    ChangeTracker(
            EngineSession session,
            Supplier<DockerConnection.Result> docker,
            KnownGoodFiles files,
            HealthProbe probe,
            Map<String, String> environment,
            Clock clock,
            RandomGenerator random) {
        this.session = session;
        this.docker = docker;
        this.files = files;
        this.probe = probe;
        this.environment = environment;
        this.clock = clock;
        this.random = random;
        this.report = new ChangeReport(files::load, p -> files.take(p, this::imageIds), GitLog::since);
    }

    void start() {
        Thread.ofVirtual().name("known-good").start(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(5000);
                    look();
                } catch (InterruptedException e) {
                    return;
                } catch (RuntimeException e) {
                    // a failed look (Docker gone for a moment) is tried again next time
                }
            }
        });
    }

    /** Saves the state when every service is up (healthy, running, or a finished one-off job). */
    private void look() {
        Optional<EngineSession.Current> current = session.current();
        if (current.isEmpty() || current.get().states().isEmpty()) {
            savedThisRun = false;
            return;
        }
        boolean allGood = current.get().states().values().stream()
                .map(ServiceState::label)
                .allMatch(GOOD::contains);
        if (!allGood) {
            savedThisRun = false;
            return;
        }
        if (!savedThisRun) {
            ProjectSpec p = current.get().project();
            files.save(p, files.take(p, this::imageIds));
            savedThisRun = true;
        }
    }

    /** What changed since the project last started cleanly, as a plain list (crash bundles keep this shape). */
    EngineSession.Answer changes() {
        Optional<EngineSession.Current> current = session.current();
        if (current.isEmpty()) {
            return new EngineSession.Answer(404, ApiJson.error("no project is open"));
        }
        ProjectSpec p = current.get().project();
        Optional<KnownGood> then = files.load(p);
        if (then.isEmpty()) {
            return notKnown();
        }
        List<KnownGood.Change> changes = KnownGood.compare(then.get(), files.take(p, this::imageIds));
        return new EngineSession.Answer(
                200,
                Map.of(
                        "known", true,
                        "since", then.get().at().toString(),
                        "changes",
                                changes.stream()
                                        .map(c -> Map.of("area", c.area(), "what", c.what(), "detail", c.detail()))
                                        .toList()));
    }

    /** The ranked verdict: each change judged against what fails now, most likely first. */
    EngineSession.Answer verdict() {
        Optional<EngineSession.Current> current = session.current();
        if (current.isEmpty()) {
            return new EngineSession.Answer(404, ApiJson.error("no project is open"));
        }
        ProjectSpec p = current.get().project();
        List<Symptom> symptoms = session.findingsNow().stream().map(Symptom::of).toList();
        Optional<ChangeReport.Report> r = report.build(p, symptoms);
        if (r.isEmpty()) {
            return notKnown();
        }
        Map<String, String> idsNow = imageIds(r.get().items().stream()
                .map(i -> i.suspect().change())
                .filter(c -> c.kind() == KnownGood.Kind.IMAGE)
                .map(KnownGood.Change::subject)
                .toList());
        List<Map<String, Object>> items = r.get().items().stream()
                .map(i -> {
                    Suspect s = i.suspect();
                    Optional<ImageProof.Result> proof = s.provable()
                            ? Optional.ofNullable(proofs.get(proofKey(s.change().subject(), idsNow)))
                            : Optional.empty();
                    return ApiJson.verdictItem(proof.map(x -> x.applyTo(s)).orElse(s), i.bisect(), proof);
                })
                .toList();
        return new EngineSession.Answer(
                200,
                Map.of(
                        "known",
                        true,
                        "since",
                        r.get().since().toString(),
                        "failing",
                        !symptoms.isEmpty(),
                        "items",
                        items));
    }

    /**
     * "Prove it": replays the image {@code image} pointed to at the last clean start and the one it points to now, in
     * throwaway containers, with the service's health check. Takes up to {@link #PROOF_CAP}.
     */
    EngineSession.Answer prove(String image) throws InterruptedException {
        Optional<EngineSession.Current> current = session.current();
        if (current.isEmpty()) {
            return new EngineSession.Answer(404, ApiJson.error("no project is open"));
        }
        ProjectSpec p = current.get().project();
        Optional<ServiceSpec> service = serviceFor(
                p, image, session.findingsNow().stream().map(Symptom::of).toList());
        if (service.isEmpty()) {
            return new EngineSession.Answer(404, ApiJson.error("no service runs the image " + image));
        }
        Optional<String> previous = report.previousImage(p, image);
        if (previous.isEmpty()) {
            return new EngineSession.Answer(
                    409, ApiJson.error("DevDesk didn't see " + image + " at the last clean start: nothing to compare"));
        }
        if (!(docker.get() instanceof DockerConnection.Result.Connected c)) {
            return new EngineSession.Answer(503, ApiJson.error("Docker isn't reachable, so nothing can be replayed"));
        }
        var proof = new ImageProof(c.runtime().imageReplay(random), probe, clock, Duration.ofSeconds(1));
        ImageProof.Result result = proof.prove(
                service.get(), previous.get(), ServiceEnvironment.values(p, service.get(), environment), PROOF_CAP);
        proofs.put(proofKey(image, imageIds(List.of(image))), result);
        return new EngineSession.Answer(200, ApiJson.proof(service.get().name(), result));
    }

    /** The service to replay: one that runs this image and fails, else the first one that runs it. */
    static Optional<ServiceSpec> serviceFor(ProjectSpec p, String image, List<Symptom> symptoms) {
        List<ServiceSpec> running = p.services().values().stream()
                .filter(s -> s.workload() instanceof Workload.Container k
                        && k.image().equals(image))
                .toList();
        return running.stream()
                .filter(s -> symptoms.stream().anyMatch(x -> x.service().equals(s.name())))
                .findFirst()
                .or(() -> running.stream().findFirst());
    }

    private static String proofKey(String image, Map<String, String> idsNow) {
        return image + "@" + idsNow.getOrDefault(image, "?");
    }

    private Map<String, String> imageIds(Collection<String> images) {
        return images.isEmpty() || !(docker.get() instanceof DockerConnection.Result.Connected c)
                ? Map.of()
                : c.runtime().imageIds(images);
    }

    private static EngineSession.Answer notKnown() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("known", false);
        body.put(
                "message",
                "DevDesk hasn't seen this project start cleanly yet. Once every service is up, it remembers that"
                        + " state, and this compares with it.");
        return new EngineSession.Answer(200, body);
    }
}
