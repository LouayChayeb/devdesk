package io.github.louch.devdesk.core.config;

/**
 * One thing wrong in devdesk.yaml.
 *
 * @param path where in the structure, e.g. "services.backend.depends_on"
 */
public record ConfigProblem(Location location, String path, String message) {

    /** Formats like a compiler error, so editors and terminals can jump to the line. */
    public String format(String fileName) {
        return fileName + ":" + location + ": " + (path.isEmpty() ? "" : path + ": ") + message;
    }
}
