package io.github.louch.devdesk.core.health;

/** The outcome of one health probe, with a human-readable detail ("HTTP 200", "connection refused"). */
public sealed interface ProbeResult {

    /** What happened, in a few plain words. */
    String detail();

    /** The check passed. */
    record Passed(String detail) implements ProbeResult {}

    /** The check failed; {@code detail} says why in plain words. */
    record Failed(String detail) implements ProbeResult {}
}
