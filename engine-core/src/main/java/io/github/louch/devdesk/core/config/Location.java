package io.github.louch.devdesk.core.config;

/** A position in devdesk.yaml, 1-based, as editors show it. */
public record Location(int line, int column) {

    @Override
    public String toString() {
        return line + ":" + column;
    }
}
