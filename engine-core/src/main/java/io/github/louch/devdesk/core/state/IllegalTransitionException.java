package io.github.louch.devdesk.core.state;

/** An event arrived that makes no sense in the current state. Always a bug in DevDesk, never a user error. */
public final class IllegalTransitionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public IllegalTransitionException(ServiceState from, ServiceEvent event) {
        super("Bug: event " + event + " is not allowed in state " + from);
    }
}
