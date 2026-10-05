package io.github.louch.devdesk.core.process;

import java.util.Objects;

/** One line of output from a process, without the trailing newline. */
public record OutputLine(StreamKind stream, String text) {

    public OutputLine {
        Objects.requireNonNull(stream, "stream");
        Objects.requireNonNull(text, "text");
    }
}
