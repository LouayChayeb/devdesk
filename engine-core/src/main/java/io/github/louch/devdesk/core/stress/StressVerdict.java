package io.github.louch.devdesk.core.stress;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Reads a stress run: when it broke (errors above 5%, or answers 4× slower than at the start), and which service gave
 * up first (its health failed or it crashed), or ran out of CPU when nothing gave up.
 *
 * @param breakingSecond the first second things broke, if they did
 * @param firstToGiveUp the service that broke first, if one did
 */
public record StressVerdict(
        Optional<Integer> breakingSecond,
        Optional<Integer> breakingConcurrency,
        Optional<String> firstToGiveUp,
        String text) {

    /** One look at a service during the run. */
    public record ServicePoint(int second, String service, String label, double cpuPercent, long memoryBytes) {}

    static final double ERROR_RATE = 0.05;
    static final double SLOWER = 4.0;
    private static final Set<String> GAVE_UP = Set.of("unhealthy", "crashed", "failed", "crash-loop", "stopped");

    public static StressVerdict of(List<LoadSecond> seconds, List<ServicePoint> points) {
        if (seconds.isEmpty()) {
            return new StressVerdict(Optional.empty(), Optional.empty(), Optional.empty(), "Nothing was sent yet.");
        }
        // Normal speed: the first seconds, with few callers. At least 20 ms, so a 2 ms → 9 ms change isn't "broken".
        double base = Math.max(
                20,
                seconds.stream().limit(3).mapToDouble(LoadSecond::p95Ms).max().orElse(20));
        Optional<LoadSecond> broke = seconds.stream()
                .filter(s -> s.requests() > 0 && (s.errorRate() > ERROR_RATE || s.p95Ms() > base * SLOWER))
                .findFirst();
        Optional<ServicePoint> gaveUp = points.stream()
                .filter(p -> GAVE_UP.contains(p.label()))
                .min((a, b) -> Integer.compare(a.second(), b.second()));
        Optional<String> first = gaveUp.map(ServicePoint::service);
        if (first.isEmpty() && broke.isPresent()) {
            // Nobody fell over: the one that was out of CPU when it broke is the bottleneck.
            int at = broke.get().second();
            first = points.stream()
                    .filter(p -> p.second() == at && p.cpuPercent() >= 90)
                    .max((a, b) -> Double.compare(a.cpuPercent(), b.cpuPercent()))
                    .map(ServicePoint::service);
        }
        StringBuilder text = new StringBuilder();
        LoadSecond last = seconds.getLast();
        if (broke.isEmpty()) {
            text.append("It held: up to ")
                    .append(last.concurrency())
                    .append(" callers at once (")
                    .append(last.requests())
                    .append(" requests/s), errors stayed under 5% and answers fast. Try more"
                            + " callers to find its limit.");
        } else {
            LoadSecond b = broke.get();
            text.append("It broke at second ")
                    .append(b.second() + 1)
                    .append(", with ")
                    .append(b.concurrency())
                    .append(" callers at once (")
                    .append(b.requests())
                    .append(" requests/s): ");
            text.append(
                    b.errorRate() > ERROR_RATE
                            ? Math.round(b.errorRate() * 100) + "% errors " + b.errorKinds()
                            : "answers took " + Math.round(b.p95Ms()) + " ms (95th percentile) instead of ~"
                                    + Math.round(base) + " ms");
            text.append(". ");
        }
        if (gaveUp.isPresent()) {
            text.append(gaveUp.get().service())
                    .append(" gave up first: ")
                    .append(gaveUp.get().label())
                    .append(" at second ")
                    .append(gaveUp.get().second() + 1)
                    .append(". See its logs and Diagnosis.");
        } else if (first.isPresent()) {
            text.append(first.get()).append(" was at full CPU then: it's the bottleneck.");
        } else if (broke.isPresent()) {
            text.append("No service fell over or ran out of CPU: look for a limit inside one (a connection pool, a"
                    + " lock, a slow query).");
        }
        return new StressVerdict(
                broke.map(LoadSecond::second), broke.map(LoadSecond::concurrency), first, text.toString());
    }
}
