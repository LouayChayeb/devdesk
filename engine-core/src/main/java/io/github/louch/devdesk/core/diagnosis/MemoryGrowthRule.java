package io.github.louch.devdesk.core.diagnosis;

import io.github.louch.devdesk.core.resources.ResourceSample;
import java.time.Duration;
import java.util.List;

/**
 * A running service's memory keeps climbing, steadily, which usually means a leak. Checked continuously while the
 * project runs, from the resource history (no extra measuring).
 *
 * <p>Method: see {@link Trend}. Steady growth (r² close to 1) looks like a leak; a one-off spike doesn't.
 */
public final class MemoryGrowthRule implements DiagnosisRule {

    static final Duration MIN_WINDOW = Duration.ofMinutes(10);
    static final long MIN_GROWTH_BYTES = 100L * 1024 * 1024;
    static final double MIN_GROWTH_RATIO = 1.5;
    static final double MIN_R_SQUARED = 0.8;

    @Override
    public String id() {
        return "memory-growth";
    }

    @Override
    public boolean continuous() {
        return true;
    }

    @Override
    public List<Finding> evaluate(DiagnosisContext ctx, String service) {
        List<ResourceSample> samples = ctx.historyOf(service).resources();
        if (samples.size() < 20) {
            return List.of();
        }
        ResourceSample first = samples.getFirst();
        ResourceSample last = samples.getLast();
        Duration window = Duration.between(first.at(), last.at());
        if (window.compareTo(MIN_WINDOW) < 0) {
            return List.of(); // too little history to call it a trend
        }
        Trend fit = Trend.of(samples, ResourceSample::memoryBytes);
        double start = Trend.start(samples, ResourceSample::memoryBytes);
        double end = Trend.end(samples, ResourceSample::memoryBytes);
        boolean grew = end - start >= MIN_GROWTH_BYTES && end >= start * MIN_GROWTH_RATIO;
        if (fit.slopePerMinute() <= 0 || !grew || fit.rSquared() < MIN_R_SQUARED) {
            return List.of();
        }
        long minutes = Math.max(1, window.toMinutes());
        String rate = mb(fit.slopePerMinute()) + " per minute";
        return List.of(new Finding(
                id(),
                Finding.Severity.WARNING,
                service,
                service + "'s memory keeps growing",
                service + " grew from " + mb(start) + " to " + mb(end) + " in " + minutes + " minutes (" + rate
                        + "), steadily. A climb that never levels off usually means a memory leak: something keeps "
                        + "objects (caches, listeners, open connections) that are never released. At this rate it uses "
                        + mb(end + fit.slopePerMinute() * 60) + " in an hour.",
                List.of(
                        samples.size() + " samples over " + minutes + " min, straight-line fit r² = "
                                + String.format("%.2f", fit.rSquared()) + " (1.00 = perfectly steady)",
                        "memory: " + mb(first.memoryBytes()) + " at " + time(first) + " → " + mb(last.memoryBytes())
                                + " at " + time(last)),
                List.of(
                        "restart " + service + " to free the memory for now (it will grow again if it's a leak)",
                        "find what keeps growing with a memory profiler or a heap snapshot of " + service
                                + "'s PID (see `devdesk ps`), e.g. Node: --inspect, JVM: jcmd <pid> GC.heap_dump")));
    }

    private static String mb(double bytes) {
        double mb = bytes / (1024 * 1024);
        return mb >= 1024 ? String.format("%.1f GB", mb / 1024) : Math.round(mb) + " MB";
    }

    private static String time(ResourceSample s) {
        return s.at().toString().substring(11, 16) + " UTC";
    }
}
