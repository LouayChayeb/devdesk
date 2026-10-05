package io.github.louch.devdesk.core.process;

/** The OS refused to start a process (missing working directory, no permission, ...). */
public class LaunchException extends Exception {

    private static final long serialVersionUID = 1L;

    public LaunchException(String message, Throwable cause) {
        super(message, cause);
    }
}
