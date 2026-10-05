package io.github.louch.devdesk.core.project;

/** What to do when a service's process exits by itself. */
public enum RestartPolicy {
    /** Leave it stopped. */
    NO,
    /** Restart only after a non-zero exit code. */
    ON_FAILURE,
    /** Restart after any exit, except when the user stopped it. */
    ALWAYS
}
