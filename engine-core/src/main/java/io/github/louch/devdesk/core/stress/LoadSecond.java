package io.github.louch.devdesk.core.stress;

import java.util.Map;

/**
 * One second of a stress run.
 *
 * @param errors answers that weren't 2xx/3xx, plus calls that got no answer at all
 * @param errorKinds how many of each: "500", "502", "timeout", "refused"
 */
public record LoadSecond(
        int second,
        int concurrency,
        int requests,
        int errors,
        double p50Ms,
        double p95Ms,
        double maxMs,
        Map<String, Integer> errorKinds) {

    public LoadSecond {
        errorKinds = Map.copyOf(errorKinds);
    }

    public double errorRate() {
        return requests == 0 ? 0 : (double) errors / requests;
    }
}
