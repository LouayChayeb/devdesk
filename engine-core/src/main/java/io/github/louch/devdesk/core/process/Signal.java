package io.github.louch.devdesk.core.process;

/** The signals DevDesk sends. Core only names them; the Linux adapter maps them to kernel numbers. */
public enum Signal {
    /** "Please stop": the app may clean up first. */
    TERM,
    /** "Stop now": cannot be caught or ignored. */
    KILL,
    /** What Ctrl+C sends. */
    INT
}
