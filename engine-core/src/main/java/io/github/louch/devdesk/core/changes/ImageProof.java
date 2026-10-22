package io.github.louch.devdesk.core.changes;

import io.github.louch.devdesk.core.health.HealthProbe;
import io.github.louch.devdesk.core.health.ProbeResult;
import io.github.louch.devdesk.core.process.LaunchException;
import io.github.louch.devdesk.core.project.HealthCheckSpec;
import io.github.louch.devdesk.core.project.ServiceSpec;
import io.github.louch.devdesk.core.project.Workload;
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

    /**
     * @param evidence one line per image: what its check said
     * @param caveat how faithful the replay was ("replayed without your volume data; 12s")
     */
    public record Result(Outcome outcome, String summary, List<String> evidence, String caveat) {
        public Result {
            evidence = List.copyOf(evidence);
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
     * Replays {@code service} on {@code previousImage} and on its current image, at most {@code cap} long.
     *
     * @param environment exactly what the real container gets
     */
    public Result prove(ServiceSpec service, String previousImage, Map<String, String> environment, Duration cap)
            throws InterruptedException {
        if (!(service.workload() instanceof Workload.Container container)) {
            return cannot(service.name() + " isn't a container: only image changes can be replayed.");
        }
        if (!replay.has(previousImage)) {
            return cannot("The image " + service.name() + " used before (" + shortId(previousImage)
                    + ") is no longer on this machine (docker image prune removes old images), so it can't be"
                    + " replayed.");
        }
        if (service.health() instanceof HealthCheckSpec.Command) {
            return cannot(service.name() + "'s health check is a command, which would talk to the real "
                    + service.name() + ". Replays only use tcp, http, docker or process checks.");
        }
        Instant started = clock.instant();
        Instant deadline = started.plus(cap);
        // Both at once: the whole proof takes one time cap, not two.
        var old = CompletableFuture.supplyAsync(
                () -> trial(previousImage, container, service.health(), environment, deadline),
                r -> Thread.ofVirtual().name("prove-old").start(r));
        var now = CompletableFuture.supplyAsync(
                () -> trial(container.image(), container, service.health(), environment, deadline),
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
        String caveat = "Replayed without your volume data, on its own network, without the other services, on"
                + " random ports; " + seconds + "s.";
        List<String> evidence = List.of(
                "before (" + shortId(previousImage) + "): " + a.detail(),
                "now (" + container.image() + "): " + b.detail());
        String name = service.name();
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

    private record Trial(boolean passed, String detail) {}

    /** One replica: start, probe until it passes or the deadline, always remove. */
    private Trial trial(
            String image,
            Workload.Container container,
            HealthCheckSpec check,
            Map<String, String> environment,
            Instant deadline) {
        ImageReplay.Replica r;
        try {
            r = replay.start(image, container, environment);
        } catch (LaunchException e) {
            return new Trial(false, "could not start: " + e.getMessage());
        }
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
