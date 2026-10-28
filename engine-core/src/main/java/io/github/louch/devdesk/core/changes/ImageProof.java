package io.github.louch.devdesk.core.changes;

import io.github.louch.devdesk.core.health.HealthProbe;
import io.github.louch.devdesk.core.health.ProbeResult;
import io.github.louch.devdesk.core.process.LaunchException;
import io.github.louch.devdesk.core.project.Dependency;
import io.github.louch.devdesk.core.project.HealthCheckSpec;
import io.github.louch.devdesk.core.project.ProjectSpec;
import io.github.louch.devdesk.core.project.ServiceSpec;
import io.github.louch.devdesk.core.project.Workload;
import io.github.louch.devdesk.core.setup.FolderScan;
import io.github.louch.devdesk.core.supervisor.ServiceEnvironment;
import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * "Prove it" for an image change: runs the image the service used when it last worked and the image it uses now,
 * side by side in throwaway containers, with the same health check, for a limited time. Old passes and new fails:
 * the image change is proven to break it. Real services, ports and volumes are never touched (see
 * {@link ImageReplay}).
 */
public final class ImageProof {

    /** What the replay showed. */
    public enum Outcome {
        /** The previous image passes, the current one fails: proven. */
        PROVEN,
        /** Both pass in a clean container: the image alone doesn't break it (your data or other services may). */
        BOTH_PASS,
        /** Both fail in a clean container: probably not the image, or it needs things the replay leaves out. */
        BOTH_FAIL,
        /** The previous one fails, the current one passes: the replay says nothing about your failure. */
        ODD,
        /** Couldn't replay at all (old image gone, health check can't be pointed at a copy). */
        CANNOT_REPLAY
    }

    /** What a copy had to do to pass: said with every result, since the two prove different things. */
    public enum Kind {
        /** The service's own health check passed against the copy. */
        HEALTH_CHECK,
        /**
         * Its health check couldn't be used (a command, or it needs services a copy can't have): a copy passes when it
         * is still running at the end. Shows crashes, not "runs but doesn't work".
         */
        CRASH_CHECK
    }

    /**
     * @param evidence one line per image: what its check said
     * @param caveat how faithful the replay was ("replayed without your volume data; 12s")
     */
    public record Result(Outcome outcome, Kind kind, String summary, List<String> evidence, String caveat) {
        public Result {
            evidence = List.copyOf(evidence);
        }

        public Result(Outcome outcome, String summary, List<String> evidence, String caveat) {
            this(outcome, Kind.HEALTH_CHECK, summary, evidence, caveat);
        }

        /** The suspect, re-judged with this replay. Only PROVEN raises it; BOTH_FAIL lowers it. */
        public Suspect applyTo(Suspect s) {
            List<String> reasons = new ArrayList<>(s.reasons());
            reasons.add(summary);
            Suspect.Confidence c = switch (outcome) {
                case PROVEN -> Suspect.Confidence.PROVEN;
                case BOTH_FAIL -> Suspect.Confidence.UNKNOWN;
                default -> s.confidence();
            };
            return new Suspect(s.change(), c, s.score(), s.services(), reasons, caveat);
        }
    }

    private static final int OUTPUT_LINES = 5;

    private final ImageReplay replay;
    private final HealthProbe probe;
    private final Clock clock;
    private final Duration poll;

    public ImageProof(ImageReplay replay, HealthProbe probe, Clock clock, Duration poll) {
        this.replay = replay;
        this.probe = probe;
        this.clock = clock;
        this.poll = poll;
    }

    /**
     * Replays {@code service} of {@code project}: each copy gets fresh, empty copies of the databases it depends on
     * (same images, no data). When its health check can't be used against a copy (a command, or it needs services
     * that aren't databases), the copies are judged by whether they keep running instead, and the result says so.
     *
     * @param shell the environment DevDesk was started with, as for the real services
     */
    public Result prove(
            ProjectSpec project, ServiceSpec service, String previousImage, Map<String, String> shell, Duration cap)
            throws InterruptedException {
        List<ImageReplay.Helper> databases = new ArrayList<>();
        List<String> others = new ArrayList<>();
        for (Dependency d : service.dependsOn()) {
            ServiceSpec dep = project.services().get(d.service());
            if (dep != null && dep.workload() instanceof Workload.Container c && FolderScan.isDataStore(c.image())) {
                databases.add(new ImageReplay.Helper(dep.name(), c, ServiceEnvironment.values(project, dep, shell)));
            } else {
                others.add(d.service());
            }
        }
        Optional<String> crashCheck = crashCheckReason(service);
        if (crashCheck.isEmpty() && !others.isEmpty()) {
            crashCheck = Optional.of("it also needs " + String.join(", ", others)
                    + ", which a test copy can't have, so its health check would fail either way");
        }
        return run(
                service, previousImage, ServiceEnvironment.values(project, service, shell), databases, crashCheck, cap);
    }

    /**
     * Replays {@code service} on {@code previousImage} and on its current image, at most {@code cap} long, with no
     * other service.
     *
     * @param environment exactly what the real container gets
     */
    public Result prove(ServiceSpec service, String previousImage, Map<String, String> environment, Duration cap)
            throws InterruptedException {
        return run(service, previousImage, environment, List.of(), crashCheckReason(service), cap);
    }

    private static Optional<String> crashCheckReason(ServiceSpec service) {
        return service.health() instanceof HealthCheckSpec.Command
                ? Optional.of("its health check is a command, which would talk to the real " + service.name())
                : Optional.empty();
    }

    private Result run(
            ServiceSpec service,
            String previousImage,
            Map<String, String> environment,
            List<ImageReplay.Helper> databases,
            Optional<String> crashCheck,
            Duration cap)
            throws InterruptedException {
        if (!(service.workload() instanceof Workload.Container container)) {
            return cannot(service.name() + " isn't a container: only image changes can be replayed.");
        }
        if (!replay.has(previousImage)) {
            return cannot("The image " + service.name() + " used before (" + shortId(previousImage)
                    + ") is no longer on this machine (docker image prune removes old images), so it can't be"
                    + " replayed.");
        }
        // A crash check: "still running at the end" passes, like a service with no health check.
        HealthCheckSpec check = crashCheck.isPresent()
                ? new HealthCheckSpec.ProcessAlive(service.health().timing())
                : service.health();
        Kind kind = crashCheck.isPresent() ? Kind.CRASH_CHECK : Kind.HEALTH_CHECK;
        Instant started = clock.instant();
        // Both at once: the whole proof takes one time cap, not two.
        var old = CompletableFuture.supplyAsync(
                () -> trial(previousImage, container, check, environment, databases, cap),
                r -> Thread.ofVirtual().name("prove-old").start(r));
        var now = CompletableFuture.supplyAsync(
                () -> trial(container.image(), container, check, environment, databases, cap),
                r -> Thread.ofVirtual().name("prove-new").start(r));
        Trial a;
        Trial b;
        try {
            a = old.get();
            b = now.get();
        } catch (java.util.concurrent.ExecutionException e) {
            return cannot("The replay itself failed: " + e.getCause());
        }
        long seconds = Math.max(1, Duration.between(started, clock.instant()).toSeconds());
        String with = databases.isEmpty()
                ? "without the other services"
                : "with fresh, empty copies of "
                        + String.join(
                                ", ",
                                databases.stream()
                                        .map(ImageReplay.Helper::service)
                                        .toList())
                        + " (no data)";
        String caveat = (kind == Kind.CRASH_CHECK
                        ? "Crash check only (" + crashCheck.get() + "): a copy passed if it was still running at the"
                                + " end. "
                        : "Its own health check. ")
                + "Replayed without your volume data, on its own network, " + with + ", on random ports; " + seconds
                + "s.";
        List<String> evidence = List.of(
                "before (" + shortId(previousImage) + "): " + a.detail(),
                "now (" + container.image() + "): " + b.detail());
        String name = service.name();
        if (kind == Kind.CRASH_CHECK) {
            return crashVerdict(a, b, name, container.image(), evidence, caveat);
        }
        if (a.passed() && !b.passed()) {
            return new Result(
                    Outcome.PROVEN,
                    "Proven: the image " + name + " used before passes the same health check, the current "
                            + container.image() + " fails it.",
                    evidence,
                    caveat);
        }
        if (a.passed()) {
            return new Result(
                    Outcome.BOTH_PASS,
                    "Both images pass in a clean container: the new image probably fails only with your data"
                            + " (e.g. a major version that can't read the old volume) or with the other services.",
                    evidence,
                    caveat);
        }
        if (!b.passed()) {
            return new Result(
                    Outcome.BOTH_FAIL,
                    "The image " + name + " used before fails too in a clean container: probably not the image"
                            + " change, or it needs something the replay leaves out (data, other services).",
                    evidence,
                    caveat);
        }
        return new Result(
                Outcome.ODD,
                "The previous image fails and the current one passes in a clean container: the replay doesn't"
                        + " explain your failure.",
                evidence,
                caveat);
    }

    /** The verdict of a crash check, worded so nobody reads more into it than "it stays up" or "it exits". */
    private static Result crashVerdict(
            Trial a, Trial b, String name, String image, List<String> evidence, String caveat) {
        Outcome outcome;
        String summary;
        if (a.passed() && !b.passed()) {
            outcome = Outcome.PROVEN;
            summary = "Proven by a crash check: the image " + name + " used before keeps running, the current " + image
                    + " exits.";
        } else if (a.passed()) {
            outcome = Outcome.BOTH_PASS;
            summary = "Both images keep running in a test copy. A crash check can't tell whether they work, only that"
                    + " neither crashes on its own.";
        } else if (!b.passed()) {
            outcome = Outcome.BOTH_FAIL;
            summary = "Both images exit in a test copy: probably not the image change, or they need something the"
                    + " copy leaves out (data, other services).";
        } else {
            outcome = Outcome.ODD;
            summary = "The previous image exits and the current one keeps running in a test copy: the replay doesn't"
                    + " explain your failure.";
        }
        return new Result(outcome, Kind.CRASH_CHECK, summary, evidence, caveat);
    }

    private record Trial(boolean passed, String detail) {}

    /** One replica: start, probe until it passes or {@code cap} has passed, always remove. */
    private Trial trial(
            String image,
            Workload.Container container,
            HealthCheckSpec check,
            Map<String, String> environment,
            List<ImageReplay.Helper> databases,
            Duration cap) {
        ImageReplay.Replica r;
        try {
            r = replay.start(image, container, environment, databases);
        } catch (LaunchException e) {
            return new Trial(false, "could not start: " + e.getMessage());
        }
        // The time starts once its databases are ready: a slow database's first start isn't the app's fault.
        Instant deadline = clock.instant().plus(cap);
        try {
            // An image that exits at once has no published ports: "it exited" is the real answer, so check that first.
            if (!replay.running(r)) {
                return new Trial(false, "exited. " + output(r));
            }
            Optional<HealthCheckSpec> pointed = pointAt(check, container, r);
            if (pointed.isEmpty()) {
                // It may have exited between starting and Docker listing its ports: that's the real answer then.
                Thread.sleep(poll);
                return replay.running(r)
                        ? new Trial(false, "its health check's port isn't one of the ports it publishes")
                        : new Trial(false, "exited. " + output(r));
            }
            String last = "no check ran";
            while (true) {
                if (!replay.running(r)) {
                    return new Trial(false, "exited. " + output(r));
                }
                boolean timeUp = !clock.instant().isBefore(deadline);
                ProbeResult p = switch (pointed.get()) {
                    case HealthCheckSpec.Docker d -> replay.dockerHealth(r);
                    // No real check: still running at the end counts as passing.
                    case HealthCheckSpec.ProcessAlive pa ->
                        timeUp
                                ? new ProbeResult.Passed("still running after the whole replay")
                                : new ProbeResult.Failed("still running");
                    default -> probe.probe(pointed.get(), Path.of("/"), Map.of());
                };
                if (p instanceof ProbeResult.Passed && confirmed(r, pointed.get())) {
                    return new Trial(true, "check passed: " + p.detail());
                }
                last = p.detail();
                if (timeUp) {
                    return new Trial(false, "check never passed: " + last + ". " + output(r));
                }
                Thread.sleep(poll);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Trial(false, "interrupted");
        } finally {
            replay.remove(r);
        }
    }

    /** How many more times a pass must repeat, the replica still running, before it counts. */
    private static final int CONFIRMATIONS = 2;

    /**
     * A single pass can be a fluke: Docker's port forwarder accepts a TCP connection for a moment even when the
     * container is already exiting. So a pass counts only if the replica is still running and passes again, twice.
     */
    private boolean confirmed(ImageReplay.Replica r, HealthCheckSpec check) throws InterruptedException {
        if (check instanceof HealthCheckSpec.ProcessAlive) {
            return true; // its pass already means "still running at the end"
        }
        for (int i = 0; i < CONFIRMATIONS; i++) {
            Thread.sleep(poll);
            ProbeResult again = check instanceof HealthCheckSpec.Docker
                    ? replay.dockerHealth(r)
                    : probe.probe(check, Path.of("/"), Map.of());
            if (!replay.running(r) || !(again instanceof ProbeResult.Passed)) {
                return false;
            }
        }
        return true;
    }

    private String output(ImageReplay.Replica r) {
        List<String> lines = replay.lastOutput(r, OUTPUT_LINES);
        return lines.isEmpty() ? "It printed nothing." : "Last output: " + String.join(" | ", lines);
    }

    /**
     * The same check, aimed at the replica: the real host port is swapped for the replica's random one. Empty when the
     * check's port isn't published by the container, so the replica can't be reached the same way.
     */
    static Optional<HealthCheckSpec> pointAt(
            HealthCheckSpec check, Workload.Container container, ImageReplay.Replica r) {
        return switch (check) {
            case HealthCheckSpec.Tcp t ->
                replicaPort(t.port(), container, r).map(p -> new HealthCheckSpec.Tcp("127.0.0.1", p, t.timing()));
            case HealthCheckSpec.Http h -> {
                int port = h.url().getPort() > 0 ? h.url().getPort() : 80;
                yield replicaPort(port, container, r)
                        .map(p -> new HealthCheckSpec.Http(
                                URI.create(h.url().getScheme() + "://127.0.0.1:" + p
                                        + h.url().getRawPath()
                                        + (h.url().getRawQuery() == null
                                                ? ""
                                                : "?" + h.url().getRawQuery())),
                                h.minStatus(),
                                h.maxStatus(),
                                h.timing()));
            }
            default -> Optional.of(check);
        };
    }

    private static Optional<Integer> replicaPort(int hostPort, Workload.Container c, ImageReplay.Replica r) {
        return c.ports().stream()
                .filter(m -> m.hostPort() == hostPort)
                .findFirst()
                .map(m -> r.hostPorts().get(m.containerPort()));
    }

    private static Result cannot(String why) {
        return new Result(Outcome.CANNOT_REPLAY, why, List.of(), "Nothing was replayed.");
    }

    static String shortId(String id) {
        String s = id.replaceFirst("^sha256:", "");
        return s.length() > 12 ? s.substring(0, 12) : s;
    }
}
