package io.github.louch.devdesk.core.diagnosis;

import io.github.louch.devdesk.core.resources.ResourceSample;
import java.util.List;
import java.util.function.ToDoubleFunction;

/**
 * The best straight line through (minutes, value) points, by least squares. Its slope is the growth rate; its r²
 * (0..1) says how straight the climb is. Used by the "keeps growing" rules for memory and sockets.
 */
record Trend(double slopePerMinute, double rSquared) {

    static Trend of(List<ResourceSample> samples, ToDoubleFunction<ResourceSample> value) {
        long t0 = samples.getFirst().at().toEpochMilli();
        int n = samples.size();
        double sumX = 0;
        double sumY = 0;
        for (ResourceSample s : samples) {
            sumX += (s.at().toEpochMilli() - t0) / 60_000.0;
            sumY += value.applyAsDouble(s);
        }
        double meanX = sumX / n;
        double meanY = sumY / n;
        double sxx = 0;
        double sxy = 0;
        double syy = 0;
        for (ResourceSample s : samples) {
            double dx = (s.at().toEpochMilli() - t0) / 60_000.0 - meanX;
            double dy = value.applyAsDouble(s) - meanY;
            sxx += dx * dx;
            sxy += dx * dy;
            syy += dy * dy;
        }
        if (sxx == 0 || syy == 0) {
            return new Trend(0, 0); // all at one moment, or perfectly flat
        }
        return new Trend(sxy / sxx, (sxy * sxy) / (sxx * syy));
    }

    /** Average of the first or last tenth of the samples: steadier than one single reading. */
    static double start(List<ResourceSample> samples, ToDoubleFunction<ResourceSample> value) {
        return average(samples.subList(0, edge(samples)), value);
    }

    static double end(List<ResourceSample> samples, ToDoubleFunction<ResourceSample> value) {
        return average(samples.subList(samples.size() - edge(samples), samples.size()), value);
    }

    private static int edge(List<ResourceSample> samples) {
        return Math.max(1, samples.size() / 10);
    }

    private static double average(List<ResourceSample> samples, ToDoubleFunction<ResourceSample> value) {
        return samples.stream().mapToDouble(value).average().orElse(0);
    }
}
