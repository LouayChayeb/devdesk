package io.github.louch.devdesk.core.data;

/**
 * A database said no, in words a developer can act on: "column "stauts" does not exist. Did you mean "status"?". The
 * message comes from the database, plus DevDesk's hint when it has one.
 */
public final class DataException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public DataException(String message, Throwable cause) {
        super(message, cause);
    }

    public DataException(String message) {
        super(message);
    }
}
