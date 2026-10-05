package io.github.louch.devdesk.core.project;

/** "This service needs {@code service} to reach {@code condition} first." */
public record Dependency(String service, Condition condition) {

    /** How far the dependency must get before the dependent may start. */
    public enum Condition {
        /** Its process is running (no health check needed). */
        STARTED,
        /** Its health check passed. */
        HEALTHY,
        /** It ran to completion with exit code 0 (e.g. a database migration). */
        COMPLETED_SUCCESSFULLY
    }
}
